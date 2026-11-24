package com.ineedmypills.vibemis.shadows

import android.app.GameManager
import android.app.GameState
import android.content.Context
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = GameManager::class, isInAndroidSdk = true)
class ShadowGameManager {
    @Implementation
    protected fun __constructor__(context: Context?) {
        // no-op constructor to avoid ServiceManager lookup
    }

    @Implementation
    protected fun setGameState(state: GameState?) {
        // stub method – nothing to do in tests
    }
}
