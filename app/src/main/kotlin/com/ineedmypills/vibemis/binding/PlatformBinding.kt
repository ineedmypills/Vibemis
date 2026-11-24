package com.ineedmypills.vibemis.binding

import android.content.Context
import com.ineedmypills.vibemis.binding.crypto.AndroidCryptoProvider
import com.ineedmypills.vibemis.nvstream.http.VibemisCryptoProvider

object PlatformBinding {
    @JvmStatic
    fun getCryptoProvider(c: Context): VibemisCryptoProvider = AndroidCryptoProvider(c)
}
