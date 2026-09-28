package com.hermes.agent.data.tools

import android.content.Context
import com.hermes.agent.domain.settings.SettingsRepository
import java.io.File
import java.net.URLDecoder

/**
 * The folder the file tools work in.
 *
 * Settings > Advanced > Workspace stores what the system folder picker returns: a document-tree
 * URI such as content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FJeeves.
 * The tools treated that string as a file path, which never exists, so every tool quietly fell
 * back to the app's own folder and the granted folder was never used. The tree URI is now mapped
 * to the folder it names. When a folder was granted but cannot be opened (no all-files access, a
 * removed SD card), the tools say so instead of silently writing somewhere else.
 *
 * With nothing granted they keep using the app's own workspace folder, which the settings screen
 * shows as the default and full backups carry.
 */
object WorkspaceRoots {

    private const val EXTERNAL_TREE = "content://com.android.externalstorage.documents/tree/"

    /** The folder a stored root names, or null when it names none this can map. */
    fun pathOf(stored: String): String? {
        val value = stored.trim()
        if (value.startsWith("/") || File(value).isAbsolute) return value
        if (!value.startsWith(EXTERNAL_TREE)) return null
        val docId = URLDecoder.decode(value.removePrefix(EXTERNAL_TREE).substringBefore('/'), "UTF-8")
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "").trim('/')
        if (volume.isBlank()) return null
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /**
     * The folder to use. Throws [IllegalStateException] with a message for the user when a folder
     * was granted and cannot be used; the tool executor reports that as the tool's error.
     */
    suspend fun resolve(context: Context?, settings: SettingsRepository?): File {
        val stored = settings?.current()?.filesRootUri.orEmpty()
        if (stored.isNotBlank()) {
            val dir = pathOf(stored)?.let(::File)
                ?: throw IllegalStateException(
                    "The workspace folder granted in Settings > Advanced ($stored) is not on this device's " +
                        "shared storage, so the file tools cannot open it. Pick a folder under internal storage.",
                )
            if (!dir.isDirectory || !dir.canRead()) {
                throw IllegalStateException(
                    "The workspace folder ${dir.path} cannot be opened. It may have been moved, or the app " +
                        "needs All files access (Settings > Assistant > Grant storage access).",
                )
            }
            return dir
        }
        val ctx = context ?: return File(System.getProperty("java.io.tmpdir"), "hermes_workspace").apply { mkdirs() }
        val workspace = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "workspace")
        if (!workspace.exists()) workspace.mkdirs()
        return workspace
    }
}
