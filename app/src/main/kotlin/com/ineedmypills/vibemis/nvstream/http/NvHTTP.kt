package com.ineedmypills.vibemis.nvstream.http

import com.ineedmypills.vibemis.BuildConfig
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.ConnectionContext
import com.ineedmypills.vibemis.nvstream.http.PairingManager.PairState
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.utils.DeviceUtils
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.Reader
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.URLEncoder
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Stack
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class NvHTTP @Throws(IOException::class) constructor(
    address: ComputerDetails.AddressTuple,
    private var httpsPort: Int,
    private val uniqueId: String,
    initialServerCert: X509Certificate?,
    cryptoProvider: VibemisCryptoProvider
) {
    private val deviceName: String = DeviceUtils.getModel()
    val pairingManager: PairingManager

    @Volatile
    var lastLaunchClientWarning: String? = null
        private set

    private val baseUrlHttp: HttpUrl
    private lateinit var httpClientLongConnectTimeout: OkHttpClient
    private lateinit var httpClientLongConnectNoReadTimeout: OkHttpClient
    private lateinit var httpClientShortConnectTimeout: OkHttpClient

    private lateinit var defaultTrustManager: X509TrustManager
    private lateinit var trustManager: X509TrustManager
    private lateinit var keyManager: X509KeyManager

    init {
        initializeHttpState(cryptoProvider)

        try {
            var addressString = address.address
            if (addressString.contains(":") && addressString.contains(".")) {
                val addr = InetAddress.getByName(addressString)
                if (addr is Inet4Address) {
                    addressString = addr.hostAddress ?: addressString
                }
            }

            this.baseUrlHttp = HttpUrl.Builder()
                .scheme("http")
                .host(addressString)
                .port(address.port)
                .build()
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        }

        this.pairingManager = PairingManager(this, cryptoProvider)
    }

    private var serverCert: X509Certificate?
        get() = _serverCert
        set(value) {
            _serverCert = value
            _serverCertSha256 = computeCertSha256(value)
        }

    @Volatile
    private var _serverCert: X509Certificate? = initialServerCert

    @Volatile
    private var _serverCertSha256: ByteArray? = computeCertSha256(initialServerCert)

    internal fun setServerCert(serverCert: X509Certificate?) {
        this.serverCert = serverCert
        if (::httpConnectionPool.isInitialized) {
            httpConnectionPool.evictAll()
        }
    }

    private fun computeCertSha256(cert: X509Certificate?): ByteArray? {
        return try {
            cert?.encoded?.let { MessageDigest.getInstance("SHA-256").digest(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun isPinnedCertMatch(
        peer: Certificate,
        pinned: X509Certificate?,
        pinnedSha256: ByteArray?
    ): Boolean {
        if (pinned == null) return false
        if (pinnedSha256 != null && pinnedSha256.size == 32) {
            try {
                if (VibemisBridge.verifyPinnedCertSha256(peer.encoded, pinnedSha256)) {
                    return true
                }
            } catch (_: UnsatisfiedLinkError) {
                // Fall back to JVM constant-time check in unit tests without .so
            }
        }
        return peer == pinned
    }

    private lateinit var httpConnectionPool: ConnectionPool

    private fun initializeHttpState(cryptoProvider: VibemisCryptoProvider) {
        keyManager = object : X509KeyManager {
            override fun chooseClientAlias(keyTypes: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String = "Vibemis-RSA"
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cryptoProvider.clientCertificate)
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
            override fun getPrivateKey(alias: String?): PrivateKey = cryptoProvider.clientPrivateKey
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
        }

        defaultTrustManager = getDefaultTrustManager()
        trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) {
                throw IllegalStateException("Should never be called")
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                try {
                    defaultTrustManager.checkServerTrusted(certs, authType)
                } catch (e: CertificateException) {
                    val pinned = _serverCert
                    val pinnedHash = _serverCertSha256
                    if (certs.size == 1 && pinned != null) {
                        if (!isPinnedCertMatch(certs[0], pinned, pinnedHash)) {
                            throw CertificateException("Certificate mismatch")
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

        val hv = HostnameVerifier { hostname, session ->
            try {
                val certificates = session.peerCertificates
                val pinned = _serverCert
                val pinnedHash = _serverCertSha256
                if (certificates.size == 1 && isPinnedCertMatch(certificates[0], pinned, pinnedHash)) {
                    return@HostnameVerifier true
                }
            } catch (e: SSLPeerUnverifiedException) {
                e.printStackTrace()
            }
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        }

        val sslContext = try {
            SSLContext.getInstance("TLS").apply {
                init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
            }
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (e: KeyManagementException) {
            throw RuntimeException(e)
        }

        httpConnectionPool = ConnectionPool(4, 30, TimeUnit.SECONDS)

        httpClientLongConnectTimeout = OkHttpClient.Builder()
            .connectionPool(httpConnectionPool)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hv)
            .readTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(LONG_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .proxy(Proxy.NO_PROXY)
            .build()

        httpClientShortConnectTimeout = httpClientLongConnectTimeout.newBuilder()
            .connectTimeout(SHORT_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .build()

        httpClientLongConnectNoReadTimeout = httpClientLongConnectTimeout.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @Throws(IOException::class)
    fun getHttpsUrl(likelyOnline: Boolean): HttpUrl {
        if (httpsPort == 0) {
            httpsPort = getHttpsPort(
                openHttpConnectionToString(
                    if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout,
                    baseUrlHttp,
                    "serverinfo"
                )
            )
        }
        return HttpUrl.Builder().scheme("https").host(baseUrlHttp.host).port(httpsPort).build()
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getServerInfo(likelyOnline: Boolean): String {
        val client = if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout
        if (serverCert != null) {
            val resp: String
            try {
                resp = try {
                    openHttpConnectionToString(client, getHttpsUrl(likelyOnline), "serverinfo")
                } catch (e: SSLHandshakeException) {
                    if (e.cause is CertificateException) {
                        throw HostHttpResponseException(401, "Server certificate mismatch")
                    } else {
                        throw e
                    }
                }
                getServerVersion(resp)
            } catch (e: HostHttpResponseException) {
                if (e.errorCode == 401) {
                    return openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
                }
                throw e
            }
            return resp
        } else {
            return openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(serverInfo: String): ComputerDetails {
        val parsed = parseXmlSinglePass(serverInfo)
        val details = ComputerDetails()
        val hostname = parsed.firstValue("hostname")
        details.name = if (hostname.isNullOrEmpty()) "UNKNOWN" else hostname

        details.uuid = parsed.requireValue("uniqueid")

        val permStr = parsed.firstValue("Permission")
        if (permStr != null) {
            try {
                details.permission = permStr.toInt()
            } catch (_: Exception) {
                details.permission = -1
            }
        }

        details.httpsPort = parsed.firstValue("HttpsPort")?.toIntOrNull() ?: DEFAULT_HTTPS_PORT
        details.macAddress = parsed.firstValue("mac")
        details.localAddress = makeTuple(parsed.firstValue("LocalIP"), baseUrlHttp.port)
        details.externalPort = parsed.firstValue("ExternalPort")?.toIntOrNull() ?: baseUrlHttp.port
        details.remoteAddress = makeTuple(parsed.firstValue("ExternalIP"), details.externalPort)

        details.vDisplaySupported = parsed.firstValue("VirtualDisplayCapable") == "true"
        if (details.vDisplaySupported) {
            details.vDisplayDriverReady = parsed.firstValue("VirtualDisplayDriverReady") == "true"
        }

        details.serverCommands = parsed.allValues("ServerCommand")
        details.vibepolloCapabilities = VibepolloCapabilities.fromParsedXml(parsed)

        val pairStatus = parsed.requireValue("PairStatus")
        details.pairState = if (pairStatus == "1") PairState.PAIRED else PairState.NOT_PAIRED

        val serverState = parsed.requireValue("state")
        details.runningGameId = if (serverState.endsWith("_SERVER_BUSY")) {
            parsed.requireValue("currentgame").toInt()
        } else {
            0
        }
        details.runningGameUUID = parsed.firstValue("currentgameuuid")

        details.nvidiaServer = serverState.contains("MJOLNIR")
        details.state = ComputerDetails.State.ONLINE

        return details
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGameUUID(serverInfo: String): String? {
        return getXmlString(serverInfo, "currentgameuuid", false)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(likelyOnline: Boolean): ComputerDetails {
        return getComputerDetails(getServerInfo(likelyOnline))
    }

    private fun getCompleteUrl(
        baseUrl: HttpUrl,
        path: String,
        query: String?,
        appendClientIdentity: Boolean = true
    ): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegments(path)
            .query(query)
        if (appendClientIdentity) {
            builder
                .addQueryParameter("devicename", deviceName)
                .addQueryParameter("uniqueid", uniqueId)
                .addQueryParameter("uuid", UUID.randomUUID().toString())
        }
        return builder.build()
    }

    @Throws(IOException::class)
    private fun openHttpConnection(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        path: String,
        query: String?,
        requestBody: RequestBody?,
        appendClientIdentity: Boolean = true
    ): ResponseBody {
        val completeUrl = getCompleteUrl(baseUrl, path, query, appendClientIdentity)
        val builder = Request.Builder().url(completeUrl)
        val request = if (requestBody == null) builder.get().build() else builder.post(requestBody).build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("HTTP request interrupted").apply { initCause(e) }
        }
        val body = response.body

        if (response.isSuccessful) {
            return body
        }

        if (response.code == 404) {
            body.close()
            throw FileNotFoundException(redactUrlForLog(completeUrl))
        }

        val errMessage = try {
            val raw = body.string()
            val trimmed = raw.trimStart()
            when {
                trimmed.startsWith("{") ->
                    org.json.JSONObject(trimmed).optString("error").takeIf { it.isNotBlank() }
                trimmed.isNotBlank() && trimmed.length <= 512 -> trimmed
                else -> null
            }
        } catch (_: Throwable) {
            body.close()
            null
        }
        throw HostHttpResponseException(response.code, errMessage ?: response.message)
    }

    @Throws(IOException::class)
    private fun openHttpConnectionToString(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        path: String,
        query: String? = null,
        requestBody: RequestBody? = null,
        appendClientIdentity: Boolean = true
    ): String {
        val redactedUrl by lazy(LazyThreadSafetyMode.NONE) {
            redactUrlForLog(getCompleteUrl(baseUrl, path, query, appendClientIdentity))
        }
        try {
            val resp = openHttpConnection(client, baseUrl, path, query, requestBody, appendClientIdentity)
            val respString = resp.string()
            resp.close()

            if (verbose && path != "serverinfo" && path != "pair") {
                VibemisLog.info("$redactedUrl -> $respString")
            }

            return respString
        } catch (e: IOException) {
            if (verbose && path != "serverinfo") {
                VibemisLog.warning("$redactedUrl -> ${e.message}")
            }
            throw e
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVersion(serverInfo: String): String? {
        return getXmlString(serverInfo, "appversion", true)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerSupportsVDisplay(serverInfo: String): Boolean {
        return getXmlString(serverInfo, "VirtualDisplayCapable", false) == "true"
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVDisplayDriverReady(serverInfo: String): Boolean {
        return getXmlString(serverInfo, "VirtualDisplayDriverReady", false) == "true"
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCmds(serverInfo: String): List<String> {
        return getXmlArray(serverInfo, "ServerCommand", false)
    }

    val pairState: PairState
        @Throws(IOException::class, XmlPullParserException::class)
        get() = getPairState(getServerInfo(true))

    @Throws(IOException::class, XmlPullParserException::class)
    fun getPairState(serverInfo: String): PairState {
        return if (getXmlString(serverInfo, "PairStatus", true) == "1") PairState.PAIRED else PairState.NOT_PAIRED
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsH264(serverInfo: String): Long {
        return getXmlString(serverInfo, "MaxLumaPixelsH264", false)?.toLong() ?: 0L
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsHEVC(serverInfo: String): Long {
        return getXmlString(serverInfo, "MaxLumaPixelsHEVC", false)?.toLong() ?: 0L
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCodecModeSupport(serverInfo: String): Long {
        return getXmlString(serverInfo, "ServerCodecModeSupport", false)?.toLong() ?: 0L
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGpuType(serverInfo: String): String? {
        return getXmlString(serverInfo, "gputype", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGfeVersion(serverInfo: String): String? {
        return getXmlString(serverInfo, "GfeVersion", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun supports4K(serverInfo: String): Boolean {
        val gfeVersionStr = getXmlString(serverInfo, "GfeVersion", false)
        return !(gfeVersionStr == null || gfeVersionStr.startsWith("2."))
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGame(serverInfo: String): Int {
        return if (getXmlString(serverInfo, "state", true)!!.endsWith("_SERVER_BUSY")) {
            getXmlString(serverInfo, "currentgame", true)!!.toInt()
        } else {
            0
        }
    }

    fun getHttpsPort(serverInfo: String): Int {
        return try {
            getXmlString(serverInfo, "HttpsPort", true)!!.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
            DEFAULT_HTTPS_PORT
        }
    }

    fun getExternalPort(serverInfo: String): Int {
        return try {
            getXmlString(serverInfo, "ExternalPort", true)!!.toInt()
        } catch (_: XmlPullParserException) {
            baseUrlHttp.port
        } catch (e: IOException) {
            e.printStackTrace()
            baseUrlHttp.port
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppById(appId: Int): NvApp? {
        return getAppList().firstOrNull { it.appId == appId }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppByName(appName: String): NvApp? {
        return getAppList().firstOrNull { it.appName.equals(appName, ignoreCase = true) }
    }

    @Throws(IOException::class)
    fun getAppListRaw(): String {
        return openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true), "applist")
    }

    @Throws(HostHttpResponseException::class, IOException::class, XmlPullParserException::class)
    fun getAppList(): LinkedList<NvApp> {
        return if (verbose) {
            getAppListByReader(StringReader(getAppListRaw()))
        } else {
            openHttpConnection(httpClientLongConnectTimeout, getHttpsUrl(true), "applist", null, null).use { resp ->
                getAppListByReader(InputStreamReader(resp.byteStream()))
            }
        }
    }

    @Throws(HostHttpResponseException::class, IOException::class)
    internal fun executePairingCommand(additionalArguments: String, enableReadTimeout: Boolean): String {
        return openHttpConnectionToString(
            if (enableReadTimeout) httpClientLongConnectTimeout else httpClientLongConnectNoReadTimeout,
            baseUrlHttp,
            "pair",
            "updateState=1&$additionalArguments"
        )
    }

    @Throws(HostHttpResponseException::class, IOException::class)
    internal fun executePairingChallenge(): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            "pair",
            "updateState=1&phrase=pairchallenge"
        )
    }

    @Throws(IOException::class)
    fun unpair() {
        openHttpConnectionToString(httpClientLongConnectTimeout, baseUrlHttp, "unpair")
    }

    @Throws(IOException::class)
    fun getBoxArt(app: NvApp): InputStream {
        val resp = openHttpConnection(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            "appasset",
            "appid=${app.appId}&AssetType=2&AssetIdx=0",
            null
        )
        return resp.byteStream()
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerMajorVersion(serverInfo: String): Int {
        return getServerAppVersionQuad(serverInfo)[0]
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerAppVersionQuad(serverInfo: String): IntArray {
        val serverVersion = getServerVersion(serverInfo)
            ?: throw IllegalArgumentException("Missing server version field")
        val serverVersionSplit = serverVersion.split("\\.".toRegex())
        require(serverVersionSplit.size == 4) { "Malformed server version field: $serverVersion" }
        return IntArray(serverVersionSplit.size) { serverVersionSplit[it].toInt() }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun launchApp(
        context: ConnectionContext,
        verb: String,
        appUUID: String?,
        appId: Int,
        enableHdr: Boolean
    ): Boolean {
        val streamConfig = context.streamConfig!!
        val fps = if (context.isNvidiaServerSoftware && streamConfig.launchRefreshRate > 60) {
            0f
        } else {
            streamConfig.launchRefreshRate.toFloat()
        }

        var fpsInt = fps.toInt()
        if (fpsInt.toFloat() != fps) {
            fpsInt = (fps * 1000).toInt()
        }

        var enableSops = streamConfig.sops
        if (context.isNvidiaServerSoftware) {
            val pixels = context.negotiatedWidth * context.negotiatedHeight
            if (pixels > 1280 * 720 && pixels != 1920 * 1080 && pixels != 3840 * 2160) {
                VibemisLog.info("Disabling SOPS due to non-standard resolution: ${context.negotiatedWidth}x${context.negotiatedHeight}")
                enableSops = false
            }
        }

        lastLaunchClientWarning = null

        fun buildLaunchQuery(useVirtualDisplay: Boolean): String =
            "appid=$appId" +
                (if (appUUID == null) "" else "&appuuid=$appUUID") +
                "&mode=${context.negotiatedWidth}x${context.negotiatedHeight}x$fpsInt" +
                "&scaleFactor=${streamConfig.resolutionScaleFactor}" +
                "&additionalStates=1&sops=${if (enableSops) 1 else 0}" +
                "&rikey=${bytesToHex(context.riKey!!.encoded)}" +
                "&rikeyid=${context.riKeyId}" +
                (if (!enableHdr) "" else "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0") +
                "&virtualDisplay=${if (useVirtualDisplay) 1 else 0}" +
                "&localAudioPlayMode=${if (streamConfig.playLocalAudio) 1 else 0}" +
                "&surroundAudioInfo=${streamConfig.audioConfiguration.surroundAudioInfo}" +
                "&remoteControllersBitmap=${streamConfig.attachedGamepadMask}" +
                "&gcmap=${streamConfig.attachedGamepadMask}" +
                "&psmap=${streamConfig.attachedPlayStationControllerMask}" +
                "&clientVrrRequested=${if (streamConfig.clientVrrRequested) 1 else 0}" +
                (if (streamConfig.clientName.isBlank()) "" else "&clientName=${URLEncoder.encode(streamConfig.clientName, "UTF-8")}") +
                "&gcpersist=${if (streamConfig.persistGamepadsAfterDisconnect) 1 else 0}" +
                VibemisBridge.getLaunchUrlQueryParameters()

        val xmlStr = try {
            openHttpConnectionToString(
                httpClientLongConnectNoReadTimeout,
                getHttpsUrl(true),
                verb,
                buildLaunchQuery(streamConfig.virtualDisplay)
            )
        } catch (e: HostHttpResponseException) {
            if (e.errorCode == 503 && !streamConfig.virtualDisplay) {
                VibemisLog.warning("Host display wake returned HTTP 503; retrying $verb with virtualDisplay=1")
                openHttpConnectionToString(
                    httpClientLongConnectNoReadTimeout,
                    getHttpsUrl(true),
                    verb,
                    buildLaunchQuery(true)
                )
            } else {
                throw e
            }
        }
        lastLaunchClientWarning = try {
            getXmlString(xmlStr, "clientWarning", false)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        if ((verb == "launch" && getXmlString(xmlStr, "gamesession", true) != "0") ||
            (verb == "resume" && getXmlString(xmlStr, "resume", true) != "0")
        ) {
            context.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false)
            return true
        }
        return false
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun executeVibepolloControlAction(app: NvApp): String? {
        val query = "appid=${app.appId}" +
            (if (app.appUUID.isNullOrBlank()) "" else "&appuuid=${URLEncoder.encode(app.appUUID, "UTF-8")}") +
            "&mode=1280x720x60&additionalStates=1&sops=0" +
            "&rikey=00000000000000000000000000000000&rikeyid=0" +
            "&virtualDisplay=0&localAudioPlayMode=0&surroundAudioInfo=196610" +
            "&remoteControllersBitmap=0&gcmap=0&gcpersist=0"
        return try {
            val xmlStr = openHttpConnectionToString(
                httpClientLongConnectTimeout,
                getHttpsUrl(true),
                "launch",
                query
            )
            getXmlString(xmlStr, "gamesession", false)
            null
        } catch (e: HostHttpResponseException) {
            if (e.errorCode == 410 || e.errorCode == 200) {
                e.errorMessage?.takeIf { it.isNotBlank() }
            } else {
                throw e
            }
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun quitApp(): Boolean {
        val xmlStr = openHttpConnectionToString(httpClientLongConnectNoReadTimeout, getHttpsUrl(true), "cancel")
        if (getXmlString(xmlStr, "cancel", true) == "0") {
            return false
        }
        if (getCurrentGame(getServerInfo(true)) != 0) {
            throw HostHttpResponseException(599, "")
        }
        return true
    }

    @Throws(IOException::class)
    fun getClipboard(): String {
        return openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true), "actions/clipboard", "type=text")
    }

    @Throws(IOException::class)
    fun sendClipboard(content: String): Boolean {
        val resp = openHttpConnectionToString(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            "actions/clipboard",
            "type=text",
            content.toRequestBody("text/plain".toMediaTypeOrNull())
        )
        return resp.isEmpty()
    }

    @JvmOverloads
    @Throws(IOException::class)
    fun executePairedHttpsGet(path: String, query: String?, appendClientIdentity: Boolean = true): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            path,
            query,
            null,
            appendClientIdentity
        )
    }

    @JvmOverloads
    @Throws(IOException::class)
    fun executePairedHttpsPost(
        path: String,
        query: String?,
        body: RequestBody,
        appendClientIdentity: Boolean = true
    ): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            path,
            query,
            body,
            appendClientIdentity
        )
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun requestHostSleep(): Boolean {
        return VibepolloClient.requestHostSleep(this)
    }

    class ParsedXmlDocument internal constructor(
        private val fields: Map<String, List<String>>
    ) {
        fun firstValue(tag: String): String? = fields[tag]?.firstOrNull()

        @Throws(XmlPullParserException::class)
        fun requireValue(tag: String): String =
            firstValue(tag) ?: throw XmlPullParserException("Missing mandatory field in host response: $tag")

        fun allValues(tag: String): List<String> = fields[tag] ?: emptyList()
    }

    companion object {
        private const val DEFAULT_HTTPS_PORT = 47984
        const val DEFAULT_HTTP_PORT = 47989
        const val SHORT_CONNECTION_TIMEOUT = 3000
        const val LONG_CONNECTION_TIMEOUT = 5000
        const val READ_TIMEOUT = 7000

        private val verbose = BuildConfig.DEBUG
        private val hexArray = "0123456789ABCDEF".toCharArray()
        private val SENSITIVE_QUERY_KEYS = setOf(
            "rikey",
            "rikeyid",
            "otpauth",
            "clientpairingsecret",
            "serverchallengeresp",
            "clientchallenge",
            "pin",
            "passphrase",
            "salt",
            "aesiv",
            "token",
            "clientcert"
        )

        private val xmlFactoryThreadLocal = ThreadLocal.withInitial {
            XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
                try {
                    setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                } catch (_: Throwable) {
                }
            }
        }

        private fun newPullParser(reader: Reader): XmlPullParser {
            return xmlFactoryThreadLocal.get()!!.newPullParser().apply {
                setInput(reader)
            }
        }

        @JvmStatic
        fun redactUrlForLog(url: HttpUrl): String {
            val names = url.queryParameterNames
            if (names.isEmpty()) return url.toString()
            val builder = url.newBuilder().query(null)
            for (name in names) {
                val redacted = name.lowercase() in SENSITIVE_QUERY_KEYS
                for (value in url.queryParameterValues(name)) {
                    builder.addQueryParameter(name, if (redacted) "[REDACTED]" else value)
                }
            }
            return builder.build().toString()
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun parseXmlSinglePass(xml: String): ParsedXmlDocument {
            val xpp = newPullParser(StringReader(xml))
            var eventType = xpp.eventType
            val tagStack = ArrayList<Pair<String, StringBuilder>>(8)
            val fields = LinkedHashMap<String, MutableList<String>>(32)

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        tagStack.add(xpp.name to StringBuilder())
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagStack.isNotEmpty()) {
                            val (tag, sb) = tagStack.removeAt(tagStack.lastIndex)
                            if (sb.isNotEmpty()) {
                                fields.getOrPut(tag) { ArrayList(1) }.add(sb.toString())
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (tagStack.isNotEmpty()) {
                            val text = xpp.text
                            if (text != null) {
                                tagStack[tagStack.lastIndex].second.append(text)
                            }
                        }
                    }
                }
                eventType = xpp.next()
            }
            return ParsedXmlDocument(fields)
        }

        private fun getDefaultTrustManager(): X509TrustManager {
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                for (tm in tmf.trustManagers) {
                    if (tm is X509TrustManager) {
                        return tm
                    }
                }
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            } catch (e: KeyStoreException) {
                throw RuntimeException(e)
            }
            throw IllegalStateException("No X509 trust manager found")
        }

        private fun makeTuple(address: String?, port: Int): ComputerDetails.AddressTuple? {
            if (address == null) return null
            return ComputerDetails.AddressTuple(address, port)
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(r: Reader, tagname: String, throwIfMissing: Boolean): String? {
            val xpp = newPullParser(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                    }
                    XmlPullParser.END_TAG -> currentTag.pop()
                    XmlPullParser.TEXT -> {
                        if (currentTag.peek() == tagname) {
                            return xpp.text
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (throwIfMissing) {
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }
            return null
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(str: String, tagname: String, throwIfMissing: Boolean): String? {
            return getXmlString(StringReader(str), tagname, throwIfMissing)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlArray(r: Reader, tagname: String, throwIfMissing: Boolean): List<String> {
            val xpp = newPullParser(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String>()
            val array = ArrayList<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> currentTag.push(xpp.name)
                    XmlPullParser.END_TAG -> currentTag.pop()
                    XmlPullParser.TEXT -> {
                        if (currentTag.peek() == tagname) {
                            array.add(xpp.text)
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (throwIfMissing && array.isEmpty()) {
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }
            return array
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlArray(str: String, tagname: String, throwIfMissing: Boolean): List<String> {
            return getXmlArray(StringReader(str), tagname, throwIfMissing)
        }

        @Throws(HostHttpResponseException::class)
        private fun verifyResponseStatus(xpp: XmlPullParser) {
            var statusCode = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code").toLong().toInt()
            if (statusCode != 200) {
                var statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message")
                if (statusCode == -1 && statusMsg == "Invalid") {
                    statusCode = 418
                    statusMsg = "Missing audio capture device. Reinstall GeForce Experience."
                }
                throw HostHttpResponseException(statusCode, statusMsg)
            }
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getAppListByReader(r: Reader): LinkedList<NvApp> {
            val xpp = newPullParser(r)
            var eventType = xpp.eventType
            val appList = LinkedList<NvApp>()
            val currentTag = Stack<String>()
            var rootTerminated = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                        if (xpp.name == "App") {
                            appList.addLast(NvApp())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag.pop()
                        if (xpp.name == "root") {
                            rootTerminated = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (appList.isNotEmpty()) {
                            val app = appList.last()
                            when (currentTag.peek()) {
                                "AppTitle" -> app.appName = xpp.text?.trim().orEmpty()
                                "UUID" -> app.appUUID = xpp.text?.trim()
                                "IDX" -> app.appIndex = xpp.text.trim().toInt()
                                "ID" -> app.setAppId(xpp.text?.trim())
                                "IsHdrSupported" -> app.isHdrSupported = xpp.text?.trim() == "1"
                            }
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (!rootTerminated) {
                throw XmlPullParserException("Malformed XML: Root tag was not terminated")
            }

            val i = appList.listIterator()
            while (i.hasNext()) {
                val app = i.next()
                if (!app.isInitialized) {
                    VibemisLog.warning("GFE returned incomplete app: ${app.appId} ${app.appName}")
                    i.remove()
                }
            }

            return appList
        }
    }
}
