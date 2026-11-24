package com.ineedmypills.vibemis.grid.assets

import android.content.Context
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.binding.PlatformBinding
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.utils.ServerHelper
import java.io.IOException
import java.io.InputStream

class NetworkAssetLoader(
    private val context: Context,
    private val uniqueId: String
) {
    fun getBitmapStream(tuple: CachedAppAssetLoader.LoaderTuple): InputStream? {
        var inputStream: InputStream? = null
        try {
            val http = NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                tuple.computer.httpsPort,
                uniqueId,
                tuple.computer.serverCert,
                PlatformBinding.getCryptoProvider(context)
            )
            inputStream = http.getBoxArt(tuple.app)
        } catch (_: IOException) {}

        if (inputStream != null) {
            VibemisLog.info("Network asset load complete: $tuple")
        } else {
            VibemisLog.info("Network asset load failed: $tuple")
        }

        return inputStream
    }
}
