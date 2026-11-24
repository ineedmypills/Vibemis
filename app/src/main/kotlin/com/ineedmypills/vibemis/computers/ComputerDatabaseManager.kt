package com.ineedmypills.vibemis.computers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Locale

class ComputerDatabaseManager(c: Context) {
    private var computerDb: SQLiteDatabase

    private object AddressFields {
        const val LOCAL = "local"
        const val REMOTE = "remote"
        const val MANUAL = "manual"
        const val IPv6 = "ipv6"
        const val ADDRESS = "address"
        const val PORT = "port"
    }

    init {
        computerDb = try {
            c.openOrCreateDatabase(COMPUTER_DB_NAME, 0, null)
        } catch (_: SQLiteException) {
            c.deleteDatabase(COMPUTER_DB_NAME)
            c.openOrCreateDatabase(COMPUTER_DB_NAME, 0, null)
        }
        initializeDb(c)
    }

    fun close() {
        computerDb.close()
    }

    private fun initializeDb(c: Context) {
        computerDb.execSQL(
            String.format(
                null as Locale?,
                "CREATE TABLE IF NOT EXISTS %s(%s TEXT PRIMARY KEY, %s TEXT NOT NULL, %s TEXT NOT NULL, %s TEXT, %s TEXT)",
                COMPUTER_TABLE_NAME,
                COMPUTER_UUID_COLUMN_NAME,
                COMPUTER_NAME_COLUMN_NAME,
                ADDRESSES_COLUMN_NAME,
                MAC_ADDRESS_COLUMN_NAME,
                SERVER_CERT_COLUMN_NAME
            )
        )
    }

    fun deleteComputer(details: ComputerDetails) {
        computerDb.delete(COMPUTER_TABLE_NAME, "$COMPUTER_UUID_COLUMN_NAME=?", arrayOf(details.uuid))
    }

    fun updateComputer(details: ComputerDetails): Boolean {
        val values = ContentValues()
        values.put(COMPUTER_UUID_COLUMN_NAME, details.uuid)
        values.put(COMPUTER_NAME_COLUMN_NAME, details.name)

        try {
            val addresses = JSONObject().apply {
                put(AddressFields.LOCAL, tupleToJson(details.localAddress))
                put(AddressFields.REMOTE, tupleToJson(details.remoteAddress))
                put(AddressFields.MANUAL, tupleToJson(details.manualAddress))
                put(AddressFields.IPv6, tupleToJson(details.ipv6Address))
            }
            values.put(ADDRESSES_COLUMN_NAME, addresses.toString())
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        values.put(MAC_ADDRESS_COLUMN_NAME, details.macAddress)
        try {
            val cert = details.serverCert
            if (cert != null) {
                values.put(SERVER_CERT_COLUMN_NAME, cert.encoded)
            } else {
                values.put(SERVER_CERT_COLUMN_NAME, null as ByteArray?)
            }
        } catch (e: CertificateEncodingException) {
            values.put(SERVER_CERT_COLUMN_NAME, null as ByteArray?)
            e.printStackTrace()
        }

        return computerDb.insertWithOnConflict(
            COMPUTER_TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    private fun getComputerFromCursor(c: Cursor): ComputerDetails {
        val details = ComputerDetails()
        details.uuid = c.getString(0) ?: ""
        details.name = c.getString(1) ?: ""
        try {
            val addresses = JSONObject(c.getString(2))
            details.localAddress = tupleFromJson(addresses, AddressFields.LOCAL)
            details.remoteAddress = tupleFromJson(addresses, AddressFields.REMOTE)
            details.manualAddress = tupleFromJson(addresses, AddressFields.MANUAL)
            details.ipv6Address = tupleFromJson(addresses, AddressFields.IPv6)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        details.externalPort = details.remoteAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT
        details.macAddress = c.getString(3)

        try {
            val derCertData = c.getBlob(4)
            if (derCertData != null) {
                details.serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            }
        } catch (e: CertificateException) {
            e.printStackTrace()
        }

        details.state = ComputerDetails.State.UNKNOWN
        return details
    }

    val allComputers: List<ComputerDetails>
        get() {
            computerDb.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
                val computerList = LinkedList<ComputerDetails>()
                while (c.moveToNext()) {
                    computerList.add(getComputerFromCursor(c))
                }
                return computerList
            }
        }

    fun getComputerByName(name: String?): ComputerDetails? {
        if (name == null) return null
        computerDb.query(
            COMPUTER_TABLE_NAME,
            null,
            "$COMPUTER_NAME_COLUMN_NAME=?",
            arrayOf(name),
            null,
            null,
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return getComputerFromCursor(c)
        }
    }

    fun getComputerByUUID(uuid: String?): ComputerDetails? {
        if (uuid == null) return null
        computerDb.query(
            COMPUTER_TABLE_NAME,
            null,
            "$COMPUTER_UUID_COLUMN_NAME=?",
            arrayOf(uuid),
            null,
            null,
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return getComputerFromCursor(c)
        }
    }

    companion object {
        private const val COMPUTER_DB_NAME = "computers4.db"
        private const val COMPUTER_TABLE_NAME = "Computers"
        private const val COMPUTER_UUID_COLUMN_NAME = "UUID"
        private const val COMPUTER_NAME_COLUMN_NAME = "ComputerName"
        private const val ADDRESSES_COLUMN_NAME = "Addresses"
        private const val MAC_ADDRESS_COLUMN_NAME = "MacAddress"
        private const val SERVER_CERT_COLUMN_NAME = "ServerCert"

        @JvmStatic
        @Throws(JSONException::class)
        fun tupleToJson(tuple: ComputerDetails.AddressTuple?): JSONObject? {
            if (tuple == null) return null
            return JSONObject().apply {
                put(AddressFields.ADDRESS, tuple.address)
                put(AddressFields.PORT, tuple.port)
            }
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun tupleFromJson(json: JSONObject, name: String): ComputerDetails.AddressTuple? {
            if (!json.has(name)) return null
            val address = json.getJSONObject(name)
            return ComputerDetails.AddressTuple(
                address.getString(AddressFields.ADDRESS),
                address.getInt(AddressFields.PORT)
            )
        }
    }
}
