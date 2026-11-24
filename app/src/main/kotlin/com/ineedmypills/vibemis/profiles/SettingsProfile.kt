package com.ineedmypills.vibemis.profiles

import java.util.UUID

class SettingsProfile(
    val uuid: UUID,
    var name: String,
    val createdUtc: Long,
    var modifiedUtc: Long,
    var options: Map<String, Any?>?
) {
    @Transient
    var isActive: Boolean = false
}
