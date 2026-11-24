package com.ineedmypills.vibemis.binding.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.http.VibemisCryptoProvider
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidCryptoProvider(c: Context) : VibemisCryptoProvider {
    private val certFile: File
    private val keyFile: File

    private var cert: X509Certificate? = null
    private var key: PrivateKey? = null
    private var pemCertBytes: ByteArray? = null

    init {
        val dataPath = c.filesDir.absolutePath
        certFile = File("$dataPath${File.separator}client.crt")
        keyFile = File("$dataPath${File.separator}client.key")
    }

    private fun loadFileToBytes(f: File): ByteArray? {
        if (!f.exists()) return null
        return try {
            FileInputStream(f).use { fin ->
                val len = f.length().toInt()
                if (len <= 0 || len > 1024 * 1024) return null
                val fileData = ByteArray(len)
                var offset = 0
                while (offset < len) {
                    val read = fin.read(fileData, offset, len - offset)
                    if (read <= 0) return null
                    offset += read
                }
                fileData
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun getOrCreateKeystoreSecretKey(createIfMissing: Boolean): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) {
                return existing
            }
            if (!createIfMissing) return null
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            kg.init(spec)
            kg.generateKey()
        } catch (t: Throwable) {
            VibemisLog.warning("AndroidKeyStore unavailable for client.key sealing: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun loadCertKeyPair(): Boolean {
        val certBytes = loadFileToBytes(certFile)
        val rawOrSealedKeyBytes = loadFileToBytes(keyFile)

        if (certBytes == null || rawOrSealedKeyBytes == null) {
            VibemisLog.info("Missing cert or key; need to generate a new one")
            return false
        }

        val isSealed = hasSealedMagic(rawOrSealedKeyBytes)
        val pkcs8Bytes: ByteArray = if (isSealed) {
            val masterKey = getOrCreateKeystoreSecretKey(createIfMissing = false)
            if (masterKey == null) {
                VibemisLog.warning("Sealed client.key present but AndroidKeyStore key is unavailable")
                return false
            }
            unsealPrivateKeyWithKey(rawOrSealedKeyBytes, masterKey) ?: run {
                VibemisLog.warning("Failed to decrypt sealed client.key")
                return false
            }
        } else {
            rawOrSealedKeyBytes
        }

        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            pemCertBytes = certBytes
            val keyFactory = KeyFactory.getInstance("RSA")
            key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))

            // Transparently migrate legacy plaintext client.key into AndroidKeyStore-sealed storage.
            if (!isSealed) {
                val masterKey = getOrCreateKeystoreSecretKey(createIfMissing = true)
                if (masterKey != null) {
                    val sealed = sealPrivateKeyWithKey(pkcs8Bytes, masterKey)
                    writeAtomicBytes(keyFile, sealed)
                    VibemisLog.info("Migrated client.key to AndroidKeyStore AES-GCM encryption")
                }
            }
            true
        } catch (_: CertificateException) {
            VibemisLog.warning("Corrupted certificate")
            false
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (_: InvalidKeySpecException) {
            VibemisLog.warning("Corrupted key")
            false
        } finally {
            pkcs8Bytes.fill(0)
        }
    }

    @SuppressLint("TrulyRandom")
    private fun generateCertKeyPair(): Boolean {
        val snBytes = ByteArray(8)
        SecureRandom().nextBytes(snBytes)

        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()

            val tbsDer = VibemisBridge.buildTbsCertificateDer(keyPair.public.encoded, snBytes)
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(keyPair.private)
            signer.update(tbsDer)
            val sigBytes = signer.sign()

            val pemBytes = VibemisBridge.buildX509CertificatePem(tbsDer, sigBytes)
            val certFactory = CertificateFactory.getInstance("X.509")
            cert = certFactory.generateCertificate(ByteArrayInputStream(pemBytes)) as X509Certificate
            key = keyPair.private
            pemCertBytes = pemBytes

            VibemisLog.info("Generated a new RSA-2048 X.509v3 key pair via Rust core")
            saveCertKeyPair(pemBytes, keyPair.private.encoded)
            true
        } catch (e: Exception) {
            VibemisLog.severe("Failed to generate X.509 key pair: ${e.message}")
            throw RuntimeException(e)
        }
    }

    private fun saveCertKeyPair(pemBytes: ByteArray, privateKeyDer: ByteArray) {
        try {
            val masterKey = getOrCreateKeystoreSecretKey(createIfMissing = true)
            val keyPayload = if (masterKey != null) {
                sealPrivateKeyWithKey(privateKeyDer, masterKey)
            } else {
                privateKeyDer
            }
            writeAtomicBytes(certFile, pemBytes)
            writeAtomicBytes(keyFile, keyPayload)
            VibemisLog.info("Saved generated key pair to disk (sealed=${masterKey != null})")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun writeAtomicBytes(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            FileOutputStream(target).use { out ->
                out.write(data)
                out.fd.sync()
            }
            tmp.delete()
        }
    }

    override val clientCertificate: X509Certificate
        get() = synchronized(globalCryptoLock) {
            cert?.let { return it }
            if (loadCertKeyPair()) {
                return cert!!
            }
            if (!generateCertKeyPair()) {
                throw IllegalStateException("Failed to generate client certificate")
            }
            cert!!
        }

    override val clientPrivateKey: PrivateKey
        get() = synchronized(globalCryptoLock) {
            key?.let { return it }
            if (loadCertKeyPair()) {
                return key!!
            }
            if (!generateCertKeyPair()) {
                throw IllegalStateException("Failed to generate client private key")
            }
            key!!
        }

    override val pemEncodedClientCertificate: ByteArray
        get() = synchronized(globalCryptoLock) {
            clientCertificate
            pemCertBytes!!
        }

    override fun encodeBase64String(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "vibemis_client_key_wrap_v1"
        private const val GCM_TAG_BITS = 128
        private val SEALED_MAGIC = byteArrayOf('V'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 0x01)
        private val globalCryptoLock = Any()

        internal fun hasSealedMagic(data: ByteArray): Boolean {
            if (data.size < SEALED_MAGIC.size + 2) return false
            for (i in SEALED_MAGIC.indices) {
                if (data[i] != SEALED_MAGIC[i]) return false
            }
            return true
        }

        internal fun sealPrivateKeyWithKey(rawPkcs8: ByteArray, secretKey: SecretKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(rawPkcs8)
            val out = ByteArray(SEALED_MAGIC.size + 1 + iv.size + ciphertext.size)
            System.arraycopy(SEALED_MAGIC, 0, out, 0, SEALED_MAGIC.size)
            out[SEALED_MAGIC.size] = iv.size.toByte()
            System.arraycopy(iv, 0, out, SEALED_MAGIC.size + 1, iv.size)
            System.arraycopy(ciphertext, 0, out, SEALED_MAGIC.size + 1 + iv.size, ciphertext.size)
            return out
        }

        internal fun unsealPrivateKeyWithKey(blob: ByteArray, secretKey: SecretKey): ByteArray? {
            if (!hasSealedMagic(blob)) return null
            val ivLen = blob[SEALED_MAGIC.size].toInt() and 0xFF
            val headerLen = SEALED_MAGIC.size + 1 + ivLen
            if (ivLen < 12 || blob.size <= headerLen + 16) return null
            val iv = blob.copyOfRange(SEALED_MAGIC.size + 1, headerLen)
            val ciphertext = blob.copyOfRange(headerLen, blob.size)
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(ciphertext)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
