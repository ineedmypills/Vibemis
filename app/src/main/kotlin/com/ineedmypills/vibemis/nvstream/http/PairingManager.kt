package com.ineedmypills.vibemis.nvstream.http

import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.security.Key
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Locale

class PairingManager(
    private val http: NvHTTP,
    cryptoProvider: VibemisCryptoProvider
) {
    private val pk: PrivateKey = cryptoProvider.clientPrivateKey
    private val cert: X509Certificate = cryptoProvider.clientCertificate
    private val pemCertBytes: ByteArray = cryptoProvider.pemEncodedClientCertificate

    var pairedCert: X509Certificate? = null
        private set

    enum class PairState {
        NOT_PAIRED,
        PAIRED,
        PIN_WRONG,
        FAILED,
        ALREADY_IN_PROGRESS
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun extractPlainCert(text: String): X509Certificate? {
        val certText = NvHTTP.getXmlString(text, "plaincert", false)
        return if (certText != null) {
            val certBytes = hexToBytes(certText)
            try {
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            } catch (e: CertificateException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        } else {
            null
        }
    }

    private fun generateRandomBytes(length: Int): ByteArray {
        val rand = ByteArray(length)
        SecureRandom().nextBytes(rand)
        return rand
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun pair(serverInfo: String, pin: String, passphrase: String?): PairState {
        val serverMajorVersion = http.getServerMajorVersion(serverInfo)
        VibemisLog.info("Pairing with server generation: $serverMajorVersion")
        val hashAlgo: PairingHashAlgorithm = if (serverMajorVersion >= 7) {
            Sha256PairingHash()
        } else {
            Sha1PairingHash()
        }

        val salt = generateRandomBytes(16)
        val aesKey = if (serverMajorVersion >= 7) {
            try {
                VibemisBridge.derivePairingAesKey(salt, pin)
                    ?.takeIf { it.size == 16 }
                    ?: generateAesKey(hashAlgo, saltPin(salt, pin))
            } catch (_: UnsatisfiedLinkError) {
                generateAesKey(hashAlgo, saltPin(salt, pin))
            }
        } else {
            generateAesKey(hashAlgo, saltPin(salt, pin))
        }
        val saltStr = bytesToHex(salt)

        var pairingArguments = "phrase=getservercert&salt=$saltStr&clientcert=${bytesToHex(pemCertBytes)}"

        if (passphrase != null) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val plainText = pin + saltStr + passphrase
                val hash = digest.digest(plainText.toByteArray())
                val hexString = StringBuilder()
                for (b in hash) {
                    hexString.append(String.format("%02X", b))
                }
                pairingArguments += "&otpauth=$hexString"
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            }
        }

        val getCert = http.executePairingCommand(pairingArguments, false)
        if (NvHTTP.getXmlString(getCert, "paired", true) != "1") {
            return PairState.FAILED
        }

        pairedCert = extractPlainCert(getCert)
        if (pairedCert == null) {
            http.unpair()
            return PairState.ALREADY_IN_PROGRESS
        }

        http.setServerCert(pairedCert)

        val randomChallenge = generateRandomBytes(16)
        val encryptedChallenge = encryptAes(randomChallenge, aesKey)

        val challengeResp = http.executePairingCommand("clientchallenge=${bytesToHex(encryptedChallenge)}", true)
        if (NvHTTP.getXmlString(challengeResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        val encServerChallengeResponse = hexToBytes(NvHTTP.getXmlString(challengeResp, "challengeresponse", true)!!)
        val decServerChallengeResponse = decryptAes(encServerChallengeResponse, aesKey)

        val serverResponse = Arrays.copyOfRange(decServerChallengeResponse, 0, hashAlgo.hashLength)
        val serverChallenge = Arrays.copyOfRange(decServerChallengeResponse, hashAlgo.hashLength, hashAlgo.hashLength + 16)

        val clientSecret = generateRandomBytes(16)
        val challengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(serverChallenge, cert.signature), clientSecret))
        val challengeRespEncrypted = encryptAes(challengeRespHash, aesKey)
        val secretResp = http.executePairingCommand("serverchallengeresp=${bytesToHex(challengeRespEncrypted)}", true)
        if (NvHTTP.getXmlString(secretResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        val serverSecretResp = hexToBytes(NvHTTP.getXmlString(secretResp, "pairingsecret", true)!!)
        val serverSecret = Arrays.copyOfRange(serverSecretResp, 0, 16)
        val serverSignature = Arrays.copyOfRange(serverSecretResp, 16, serverSecretResp.size)

        if (!verifySignature(serverSecret, serverSignature, pairedCert!!)) {
            http.unpair()
            return PairState.FAILED
        }

        val serverChallengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(randomChallenge, pairedCert!!.signature), serverSecret))
        if (!Arrays.equals(serverChallengeRespHash, serverResponse)) {
            http.unpair()
            return PairState.PIN_WRONG
        }

        val clientPairingSecret = concatBytes(clientSecret, signData(clientSecret, pk))
        val clientSecretResp = http.executePairingCommand("clientpairingsecret=${bytesToHex(clientPairingSecret)}", true)
        if (NvHTTP.getXmlString(clientSecretResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        val pairChallenge = http.executePairingChallenge()
        if (NvHTTP.getXmlString(pairChallenge, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        return PairState.PAIRED
    }

    private interface PairingHashAlgorithm {
        val hashLength: Int
        fun hashData(data: ByteArray): ByteArray
    }

    private class Sha1PairingHash : PairingHashAlgorithm {
        override val hashLength: Int = 20
        override fun hashData(data: ByteArray): ByteArray {
            try {
                val md = MessageDigest.getInstance("SHA-1")
                return md.digest(data)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    private class Sha256PairingHash : PairingHashAlgorithm {
        override val hashLength: Int = 32
        override fun hashData(data: ByteArray): ByteArray {
            try {
                val md = MessageDigest.getInstance("SHA-256")
                return md.digest(data)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    companion object {
        private val hexArray = "0123456789ABCDEF".toCharArray()

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        private fun hexToBytes(s: String): ByteArray {
            val len = s.length
            require(len % 2 == 0) { "Illegal string length: $len" }
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        @Throws(UnsupportedEncodingException::class)
        private fun saltPin(salt: ByteArray, pin: String): ByteArray {
            val saltedPin = ByteArray(salt.size + pin.length)
            System.arraycopy(salt, 0, saltedPin, 0, salt.size)
            System.arraycopy(pin.toByteArray(charset("UTF-8")), 0, saltedPin, salt.size, pin.length)
            return saltedPin
        }

        @Throws(NoSuchAlgorithmException::class)
        private fun getSha256SignatureInstanceForKey(key: Key): Signature {
            return when (key.algorithm) {
                "RSA" -> Signature.getInstance("SHA256withRSA")
                "EC" -> Signature.getInstance("SHA256withECDSA")
                else -> throw NoSuchAlgorithmException("Unhandled key algorithm: ${key.algorithm}")
            }
        }

        private fun verifySignature(data: ByteArray, signature: ByteArray, cert: Certificate): Boolean {
            try {
                val sig = getSha256SignatureInstanceForKey(cert.publicKey)
                sig.initVerify(cert.publicKey)
                sig.update(data)
                return sig.verify(signature)
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun signData(data: ByteArray, key: PrivateKey): ByteArray {
            try {
                val sig = getSha256SignatureInstanceForKey(key)
                sig.initSign(key)
                sig.update(data)
                return sig.sign()
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun decryptAes(encryptedData: ByteArray, aesKey: ByteArray): ByteArray {
            return VibemisBridge.decryptAes128Ecb(encryptedData, aesKey)
        }

        private fun encryptAes(plaintextData: ByteArray, aesKey: ByteArray): ByteArray {
            return VibemisBridge.encryptAes128Ecb(plaintextData, aesKey)
        }

        private fun generateAesKey(hashAlgo: PairingHashAlgorithm, keyData: ByteArray): ByteArray {
            return Arrays.copyOf(hashAlgo.hashData(keyData), 16)
        }

        private fun concatBytes(a: ByteArray, b: ByteArray): ByteArray {
            val c = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, c, 0, a.size)
            System.arraycopy(b, 0, c, a.size, b.size)
            return c
        }

        @JvmStatic
        fun generatePinString(): String {
            val r = SecureRandom()
            return String.format(
                null as Locale?,
                "%d%d%d%d",
                r.nextInt(10), r.nextInt(10),
                r.nextInt(10), r.nextInt(10)
            )
        }
    }
}
