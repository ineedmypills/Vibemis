package com.ineedmypills.vibemis.utils

import org.json.JSONObject

object KeyConfigHelper {
    class ShortcutFile @JvmOverloads constructor(
        @JvmField var data: List<Shortcut> = ArrayList()
    )

    class Shortcut @JvmOverloads constructor(
        @JvmField var id: String = "",
        @JvmField var name: String = "",
        @JvmField var sticky: Boolean = false,
        @JvmField var keys: List<String> = ArrayList()
    )

    @JvmStatic
    fun parseShortcutFile(json: String?): ShortcutFile? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            val dataArr = root.optJSONArray("data") ?: return ShortcutFile(emptyList())
            val shortcuts = ArrayList<Shortcut>(dataArr.length())
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val keysArr = item.optJSONArray("keys")
                val keys = ArrayList<String>(keysArr?.length() ?: 0)
                if (keysArr != null) {
                    for (j in 0 until keysArr.length()) {
                        keysArr.optString(j).takeIf { it.isNotEmpty() }?.let { keys.add(it) }
                    }
                }
                shortcuts.add(
                    Shortcut(
                        id = item.optString("id", ""),
                        name = item.optString("name", ""),
                        sticky = item.optBoolean("sticky", false),
                        keys = keys
                    )
                )
            }
            ShortcutFile(shortcuts)
        } catch (_: Exception) {
            null
        }
    }
}
