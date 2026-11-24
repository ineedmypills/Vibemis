package com.ineedmypills.vibemis.nvstream

import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import java.security.cert.X509Certificate
import javax.crypto.SecretKey

class ConnectionContext {
    @JvmField var serverAddress: ComputerDetails.AddressTuple? = null
    @JvmField var httpsPort: Int = 0
    @JvmField var isNvidiaServerSoftware: Boolean = false
    @JvmField var serverCert: X509Certificate? = null
    @JvmField var streamConfig: StreamConfiguration? = null
    @JvmField var connListener: NvConnectionListener? = null
    @JvmField var riKey: SecretKey? = null
    @JvmField var riKeyId: Int = 0

    @JvmField var serverAppVersion: String? = null
    @JvmField var serverGfeVersion: String? = null
    @JvmField var serverCodecModeSupport: Int = 0

    @JvmField var rtspSessionUrl: String? = null

    @JvmField var negotiatedWidth: Int = 0
    @JvmField var negotiatedHeight: Int = 0
    @JvmField var negotiatedHdr: Boolean = false

    @JvmField var negotiatedRemoteStreaming: Int = 0
    @JvmField var negotiatedPacketSize: Int = 0

    @JvmField var videoCapabilities: Int = 0
}
