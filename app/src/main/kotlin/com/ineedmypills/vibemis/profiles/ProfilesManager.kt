package com.ineedmypills.vibemis.profiles

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.VibemisLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class ProfilesManager private constructor() {
    private val profileMap = LinkedHashMap<UUID, SettingsProfile>()
    private var activeProfileId: UUID? = null
    private val listeners = ArrayList<ProfileChangeListener>()
    private var appContext: Context? = null

    fun load(context: Context?): Boolean {
        VibemisLog.info("VibemisProfile: Loading profile...")
        if (context == null) return false

        appContext = try {
            context.applicationContext ?: context
        } catch (_: Exception) {
            context
        }

        val ctx = appContext ?: return false

        return try {
            val dir = File(ctx.filesDir, PROFILES_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                return false
            }
            val file = File(dir, PROFILES_FILE)
            if (!file.exists()) {
                return true
            }
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) {
                return true
            }
            val root = JSONObject(text)
            val profilesArr = root.optJSONArray("profiles")
            if (profilesArr != null) {
                profileMap.clear()
                for (i in 0 until profilesArr.length()) {
                    val obj = profilesArr.optJSONObject(i) ?: continue
                    val uuidStr = obj.optString("uuid", "")
                    if (uuidStr.isEmpty()) continue
                    val uuid = UUID.fromString(uuidStr)
                    val name = obj.optString("name", "")
                    val createdUtc = obj.optLong("createdUtc", 0L)
                    val modifiedUtc = obj.optLong("modifiedUtc", 0L)
                    val optionsObj = obj.optJSONObject("options")
                    val options = optionsObj?.let(::jsonObjectToOptionsMap)
                    profileMap[uuid] = SettingsProfile(uuid, name, createdUtc, modifiedUtc, options)
                }
            }
            activeProfileId = if (root.isNull("activeProfileId")) {
                null
            } else {
                root.optString("activeProfileId", "").takeIf { it.isNotEmpty() }?.let(UUID::fromString)
            }
            true
        } catch (e: Exception) {
            VibemisLog.warning("VibemisProfile: Failed to load profiles: $e")
            false
        }
    }

    fun save(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val dir = File(context.filesDir, PROFILES_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                return false
            }
            val file = File(dir, PROFILES_FILE)
            val tmpFile = File(dir, "$PROFILES_FILE.tmp")
            val root = JSONObject()
            val profilesArr = JSONArray()
            for (p in profileMap.values) {
                val obj = JSONObject()
                obj.put("uuid", p.uuid.toString())
                obj.put("name", p.name)
                obj.put("createdUtc", p.createdUtc)
                obj.put("modifiedUtc", p.modifiedUtc)
                val opts = p.options
                if (opts != null) {
                    obj.put("options", optionsMapToJsonObject(opts))
                } else {
                    obj.put("options", JSONObject.NULL)
                }
                profilesArr.put(obj)
            }
            root.put("profiles", profilesArr)
            root.put("activeProfileId", activeProfileId?.toString() ?: JSONObject.NULL)
            tmpFile.writeText(root.toString(), Charsets.UTF_8)
            if (!tmpFile.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmpFile.delete()
            }
            true
        } catch (e: IOException) {
            VibemisLog.warning("VibemisProfile: Failed to save profiles: $e")
            false
        } catch (e: Exception) {
            VibemisLog.warning("VibemisProfile: Failed to save profiles: $e")
            false
        }
    }

    private fun jsonObjectToOptionsMap(obj: JSONObject): MutableMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            map[k] = when (v) {
                JSONObject.NULL -> null
                is JSONArray -> {
                    val set = LinkedHashSet<String>(v.length())
                    for (i in 0 until v.length()) {
                        set.add(v.optString(i))
                    }
                    set
                }
                else -> v
            }
        }
        return map
    }

    private fun optionsMapToJsonObject(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            when (v) {
                null -> obj.put(k, JSONObject.NULL)
                is Iterable<*> -> {
                    val arr = JSONArray()
                    for (item in v) {
                        arr.put(item)
                    }
                    obj.put(k, arr)
                }
                else -> obj.put(k, v)
            }
        }
        return obj
    }

    val profiles: List<SettingsProfile>
        get() = ArrayList(profileMap.values)

    fun add(profile: SettingsProfile) {
        profileMap[profile.uuid] = profile
        notifyListeners()
        saveIfPossible()
    }

    fun update(profile: SettingsProfile) {
        profileMap[profile.uuid] = profile
        notifyListeners()
        saveIfPossible()
    }

    fun delete(uuid: UUID) {
        profileMap.remove(uuid)
        if (uuid == activeProfileId) {
            activeProfileId = null
        }
        notifyListeners()
        saveIfPossible()
    }

    fun setActive(uuid: UUID?) {
        activeProfileId = uuid
        notifyListeners()
        saveIfPossible()
    }

    val active: SettingsProfile?
        get() = activeProfileId?.let { profileMap[it] }

    val activeName: String
        get() = active?.name ?: ""

    fun addListener(listener: ProfileChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ProfileChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.onProfilesChanged()
        }
    }

    private fun saveIfPossible() {
        appContext?.let { save(it) }
    }

    fun getOverlayingSharedPreferences(context: Context): SharedPreferences {
        val base = PreferenceConfiguration.getDefaultSharedPreferences(context)
        val activeProfile = active
        val opts = activeProfile?.options
        if (activeProfile == null || opts == null) {
            return base
        }
        return OverlaySharedPreferences(base, opts)
    }

    fun interface ProfileChangeListener {
        fun onProfilesChanged()
    }

    private class OverlaySharedPreferences(
        private val base: SharedPreferences,
        private val patch: Map<String, Any?>
    ) : SharedPreferences {
        override fun getAll(): Map<String, *> {
            val combined = LinkedHashMap<String, Any?>(base.all)
            combined.putAll(patch)
            return combined
        }

        override fun getString(key: String, defValue: String?): String? {
            if (patch.containsKey(key)) return patch[key] as? String
            return base.getString(key, defValue)
        }

        override fun getInt(key: String, defValue: Int): Int {
            if (patch.containsKey(key)) return (patch[key] as? Number)?.toInt() ?: defValue
            return base.getInt(key, defValue)
        }

        override fun getLong(key: String, defValue: Long): Long {
            if (patch.containsKey(key)) return (patch[key] as? Number)?.toLong() ?: defValue
            return base.getLong(key, defValue)
        }

        override fun getFloat(key: String, defValue: Float): Float {
            if (patch.containsKey(key)) return (patch[key] as? Number)?.toFloat() ?: defValue
            return base.getFloat(key, defValue)
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            if (patch.containsKey(key)) return (patch[key] as? Boolean) ?: defValue
            return base.getBoolean(key, defValue)
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            if (patch.containsKey(key)) {
                val raw = patch[key]
                if (raw is Set<*>) {
                    return raw.filterIsInstance<String>().toMutableSet()
                }
            }
            return base.getStringSet(key, defValues)
        }

        override fun contains(key: String): Boolean = patch.containsKey(key) || base.contains(key)

        override fun edit(): SharedPreferences.Editor = base.edit()

        override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {
            base.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {
            base.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    companion object {
        private const val PROFILES_DIR = "profiles"
        private const val PROFILES_FILE = "profiles.json"

        @JvmField
        internal var instance: ProfilesManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): ProfilesManager {
            if (instance == null) {
                instance = ProfilesManager()
            }
            return instance!!
        }
    }
}
