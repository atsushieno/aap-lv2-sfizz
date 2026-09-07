package org.androidaudioplugin.samples.aap_sfizz

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream

/** A selected tree is the namespace root; document IDs are opaque, never filesystem paths. */
object SfzFolders {
    val revision = MutableStateFlow(0)
    private fun prefs(context: Context) = context.getSharedPreferences("sfz-folders", Context.MODE_PRIVATE)
    fun roots(context: Context): List<Uri> =
        prefs(context).getStringSet("trees", emptySet())!!.sorted().map(Uri::parse)

    fun add(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(context).edit().putStringSet("trees", roots(context).map { it.toString() }.toSet() + uri.toString()).apply()
        revision.value++
    }

    fun remove(context: Context, uri: Uri) {
        prefs(context).edit().putStringSet("trees", roots(context).map { it.toString() }.toSet() - uri.toString()).apply()
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        revision.value++
    }

    private data class Document(val path: String, val uri: Uri)

    private fun documents(context: Context, tree: Uri): List<Document> {
        val result = mutableListOf<Document>()
        val visited = mutableSetOf<String>()
        fun walk(id: String, prefix: String, depth: Int) {
            require(depth <= 64 && visited.add(id)) { "Folder is too deep or contains a cycle" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor = context.contentResolver.query(children, columns, null, null, null)
                ?: error("Cannot read SFZ folder. Select it again.")
            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getString(0)
                    val name = it.getString(1) ?: continue
                    if (name.startsWith(".")) continue // AppleDouble and other hidden metadata are not instruments.
                    require(name != ".." && '/' !in name && '\\' !in name && ':' !in name)
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    require(path.length <= 4096 && result.size < 65536) { "SFZ folder is too large" }
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                        walk(childId, path, depth + 1)
                    else result += Document(path, DocumentsContract.buildDocumentUriUsingTree(tree, childId))
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return result.sortedBy { it.path }
    }

    fun discover(context: Context): List<SfzResourceClient.Choice> = roots(context).flatMap { tree ->
        documents(context, tree).filter { it.path.endsWith(".sfz", ignoreCase = true) }.map {
            SfzResourceClient.Choice(it.path, Uri.Builder().scheme("aap-sfz-folder")
                .authority("local").appendQueryParameter("tree", tree.toString())
                .appendQueryParameter("entry", it.path).build().toString())
        }
    }

    /** Index names only. Sample bytes are opened individually when the engine requests them. */
    fun open(context: Context, identity: Uri): AudioPluginLV2ResourceBridge.Snapshot {
        val tree = Uri.parse(requireNotNull(identity.getQueryParameter("tree")))
        require(tree in roots(context)) { "SFZ folder is no longer registered. Select it again." }
        val entry = requireNotNull(identity.getQueryParameter("entry"))
        val files = documents(context, tree).associate { it.path to it.uri }
        require(entry in files) { "SFZ file no longer exists" }
        return AudioPluginLV2ResourceBridge.Snapshot(entry, files.keys.toTypedArray(), emptyArray(),
            opener = { path -> openDocument(context, requireNotNull(files[path])) })
    }

    private fun openDocument(context: Context, uri: Uri): AssetFileDescriptor {
        val asset = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("Cannot open $uri")
        // Local SAF providers expose seekable files: map the requested range, without copying.
        try {
            val stat = Os.fstat(asset.fileDescriptor)
            if (OsConstants.S_ISREG(stat.st_mode)) {
                val length = if (asset.declaredLength >= 0) asset.declaredLength else stat.st_size - asset.startOffset
                require(length > 0) { "Empty resource: $uri" }
                Log.d("AAP.SFZ", "Open document directly: $uri ($length bytes)")
                return AssetFileDescriptor(asset.parcelFileDescriptor, asset.startOffset, length)
            }
        } catch (error: Throwable) {
            asset.close()
            throw error
        }
        // A pipe/cloud stream cannot be mapped. Cache only this requested file, never its tree.
        var cache: File? = null
        try {
            cache = File.createTempFile("sfz-stream-", ".sample", context.cacheDir)
            var length = 0L
            asset.createInputStream().use { input ->
                FileOutputStream(cache).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        length += count
                        require(length <= (8L shl 30)) { "Sample exceeds 8 GiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(length > 0) { "Empty resource: $uri" }
            Log.d("AAP.SFZ", "Cache requested stream: $uri ($length bytes)")
            return AssetFileDescriptor(ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY), 0, length)
        } finally {
            asset.close()
            cache?.delete() // The returned descriptor/mapping retains this file until released.
        }
    }
}
