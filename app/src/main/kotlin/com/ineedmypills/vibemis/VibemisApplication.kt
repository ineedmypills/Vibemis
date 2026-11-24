package com.ineedmypills.vibemis

import android.app.Application
import com.ineedmypills.vibemis.profiles.ProfilesManager

class VibemisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val profilesManager = ProfilesManager.getInstance()
        if (!profilesManager.load(this)) {
            val msg = runCatching { getString(R.string.profile_manager_failed_to_load) }
                .getOrDefault("ProfilesManager failed to load profiles")
            VibemisLog.severe(msg)
        }
    }
}
