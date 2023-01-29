package org.androidaudioplugin.samples.aap_sfizz
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import java.io.File
import java.io.FileOutputStream

class SfzSetupInitializer : Initializer<Any?> {

    fun xcopyFromAssetsToLocalStorage(context: Context, dst: File, src: String) {
        var list = context.assets.list(src)
        if (list == null)
            return
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

    override fun create(context: Context): Any {
        if (File(context.filesDir, "lv2").exists())
            return ""

        xcopyFromAssetsToLocalStorage(context, File(context.filesDir, "lv2"), "lv2")
        return ""
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> {
        return mutableListOf()
    }
}
