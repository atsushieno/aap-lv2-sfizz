package org.androidaudioplugin.samples.aap_sfizz
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.startup.Initializer
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.host.FolderRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class SfzSetupInitializer : Initializer<Any?> {

    fun xcopyFromAssetsToLocalStorage(context: Context, dst: File, src: String) {
        val list = context.assets.list(src) ?: return
        if (list.any()) {
            if (!dst.exists())
                dst.mkdirs()
            // src is directory
            for (sub in list)
                xcopyFromAssetsToLocalStorage(context, File(dst, sub), "$src/$sub")
        } else {
            // src is file
            FileOutputStream(dst).use { w ->
                context.assets.open(src).use { r ->
                    r.copyTo(w)
                }
            }
        }
    }

    companion object {
        internal const val LEGACY_FOLDERS_PREFS = "sfz-folders"
        internal const val LEGACY_FOLDERS_KEY = "trees"

        /** Once: the SFZ folders linked in AAP Sfizz 0.6 become this app's soundpack folders.
         * The grants are reused, so nobody has to pick them again. It scans each folder, so
         * call it off the main thread. */
        internal fun migrateSfzFolders(context: Context) {
            val old = context.getSharedPreferences(LEGACY_FOLDERS_PREFS, Context.MODE_PRIVATE)
            val trees = old.getStringSet(LEGACY_FOLDERS_KEY, null) ?: return
            val registry = FolderRegistry.get(context)
            for (tree in trees)
                runCatching { registry.add(Uri.parse(tree), setOf(SoundpackKind.SFZ)) }
                    .onFailure { Log.w("AAP.SFZ", "SFZ folder $tree was not carried over: ${it.message}") }
            old.edit().remove(LEGACY_FOLDERS_KEY).commit()
        }
    }

    override fun create(context: Context): Any {
        SoundpackResources.initialize(context)
        if (context.getSharedPreferences(LEGACY_FOLDERS_PREFS, Context.MODE_PRIVATE).contains(LEGACY_FOLDERS_KEY))
            thread(name = "sfz-folder-migration") { migrateSfzFolders(context) }
        if (File(context.filesDir, "lv2").exists())
            return ""

        xcopyFromAssetsToLocalStorage(context, File(context.filesDir, "lv2"), "lv2")
        return ""
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> {
        return mutableListOf()
    }
}
