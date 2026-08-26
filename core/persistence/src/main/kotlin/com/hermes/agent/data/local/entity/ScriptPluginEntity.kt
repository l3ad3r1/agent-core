package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One installed script module.
 *
 * The full manifest is kept as raw JSON rather than exploded into columns: it
 * is the exact bytes fetched and shown to the user at approval time, and the
 * granted permission set is only meaningful against that snapshot. Re-deriving
 * it from columns would let a schema change silently widen what an already
 * approved module is allowed to do.
 */
@Entity(
    tableName = "script_plugins",
    // getEnabled() runs on every module reload, including app startup.
    indices = [Index(value = ["enabled"])],
)
data class ScriptPluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    /** The manifest exactly as fetched, including its JavaScript. */
    val manifestJson: String,
    /** Permissions the user approved, comma-separated. */
    val grantedPermissions: String = "",
    val enabled: Boolean = true,
    val sourceUrl: String = "",
    val installedAt: Long = System.currentTimeMillis(),
)
