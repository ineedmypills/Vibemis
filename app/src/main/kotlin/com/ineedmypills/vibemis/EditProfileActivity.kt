package com.ineedmypills.vibemis

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.preferences.VibemisSettingsContent
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.profiles.SettingsProfile
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import com.ineedmypills.vibemis.utils.UiHelper
import java.util.UUID

class EditProfileActivity : AppCompatActivity() {
    private var profileUuid: String? = null
    private var currentProfile: SettingsProfile? = null
    lateinit var inMemoryPrefs: SharedPreferences
        private set
    private var pendingProfileName: String? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)

        profileUuid = intent.getStringExtra("profileUuid")
        val initialTitle: String
        if (profileUuid != null) {
            currentProfile = ProfilesManager.getInstance().profiles
                .firstOrNull { it.uuid.toString() == profileUuid }
            val profile = currentProfile
            if (profile != null) {
                initialTitle = getString(R.string.profile_manager_edit_profile) + profile.name
                inMemoryPrefs = InMemorySharedPreferences(profile.options)
            } else {
                VibemisLog.warning(getString(R.string.profile_manager_profile_not_found))
                finish()
                return
            }
        } else {
            initialTitle = getString(R.string.profile_manager_new_profile)
            inMemoryPrefs = InMemorySharedPreferences(
                PreferenceConfiguration.getDefaultSharedPreferences(this).all
            )
        }

        setContent {
            var screenTitle by remember { mutableStateOf(initialTitle) }
            var showRenameDialog by remember { mutableStateOf(false) }
            var renameInput by remember {
                mutableStateOf(currentProfile?.name ?: pendingProfileName ?: "")
            }

            VibemisExpressiveTheme {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = screenTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = stringResource(R.string.wizard_btn_back)
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    renameInput = currentProfile?.name ?: pendingProfileName ?: ""
                                    showRenameDialog = true
                                }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_rename))
                                }
                                IconButton(onClick = { saveProfile() }) {
                                    Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.action_save))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                ) { innerPadding ->
                    VibemisSettingsContent(
                        prefs = inMemoryPrefs,
                        context = this@EditProfileActivity,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }

                if (showRenameDialog) {
                    AlertDialog(
                        onDismissRequest = { showRenameDialog = false },
                        title = {
                            Text(
                                text = getString(R.string.rename_profile_title),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            OutlinedTextField(
                                value = renameInput,
                                onValueChange = { renameInput = it },
                                label = { Text(getString(R.string.profile_name_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                val newName = renameInput.trim()
                                if (newName.isNotEmpty()) {
                                    val profile = currentProfile
                                    if (profile != null) {
                                        profile.name = newName
                                        screenTitle = getString(R.string.profile_manager_edit_profile) + newName
                                        val pm = ProfilesManager.getInstance()
                                        pm.update(profile)
                                        pm.save(this@EditProfileActivity)
                                        VibemisLog.info("Profile renamed: $newName")
                                    } else {
                                        pendingProfileName = newName
                                        screenTitle = getString(R.string.profile_manager_new_profile) + ": " + newName
                                    }
                                    showRenameDialog = false
                                }
                            }) {
                                Text(getString(android.R.string.ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRenameDialog = false }) {
                                Text(getString(android.R.string.cancel))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun saveProfile() {
        val pm = ProfilesManager.getInstance()
        val basePrefs = PreferenceConfiguration.getDefaultSharedPreferences(this).all
        val editedPrefs = inMemoryPrefs.all
        val diffPrefs = HashMap<String, Any?>()

        for ((key, editedVal) in editedPrefs) {
            val baseVal = basePrefs[key]
            if (editedVal != null && editedVal != baseVal) {
                diffPrefs[key] = editedVal
            }
        }

        val profile = currentProfile
        if (profile != null) {
            val updated = SettingsProfile(
                profile.uuid,
                profile.name,
                profile.createdUtc,
                System.currentTimeMillis(),
                diffPrefs
            )
            pm.update(updated)
            pm.save(this)
            VibemisLog.info(getString(R.string.profile_manager_profile_saved))
            setResult(RESULT_OK)
            finish()
        } else {
            val name = pendingProfileName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.profile_default_name_fmt, pm.profiles.size + 1)
            val created = SettingsProfile(
                UUID.randomUUID(),
                name,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                diffPrefs
            )
            pm.add(created)
            pm.save(this)
            VibemisLog.info("Profile created: $name")
            setResult(RESULT_OK)
            finish()
        }
    }

    private class InMemorySharedPreferences(initial: Map<String, *>?) : SharedPreferences {
        private val map = HashMap<String, Any?>()
        private val listeners = HashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        init {
            if (initial != null) {
                map.putAll(initial)
            }
        }

        override fun getAll(): MutableMap<String, *> = HashMap(map)

        override fun getString(key: String?, defValue: String?): String? =
            (map[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int {
            val v = map[key]
            return when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: defValue
                else -> defValue
            }
        }

        override fun getLong(key: String?, defValue: Long): Long {
            val v = map[key]
            return when (v) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: defValue
                else -> defValue
            }
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            val v = map[key]
            return when (v) {
                is Number -> v.toFloat()
                is String -> v.toFloatOrNull() ?: defValue
                else -> defValue
            }
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            val v = map[key]
            return when (v) {
                is Boolean -> v
                is String -> v.toBoolean()
                else -> defValue
            }
        }

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners.remove(listener)
        }

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val modified = HashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) modified[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) modified[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) modified[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) modified[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) modified[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) modified[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) modified[key] = REMOVED
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) map.clear()
                for ((k, v) in modified) {
                    if (v === REMOVED) {
                        map.remove(k)
                    } else {
                        map[k] = v
                    }
                    for (l in listeners) {
                        l.onSharedPreferenceChanged(this@InMemorySharedPreferences, k)
                    }
                }
            }
        }

        companion object {
            private val REMOVED = Any()
        }
    }
}
