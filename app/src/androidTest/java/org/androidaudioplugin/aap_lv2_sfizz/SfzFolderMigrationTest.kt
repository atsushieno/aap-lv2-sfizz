package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.androidaudioplugin.samples.aap_sfizz.SfzSetupInitializer
import org.androidaudioplugin.samples.aap_sfizz.SoundpackResources
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.ui.compose.app.PluginManagerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Folders linked in AAP Sfizz 0.6 carry over without picking them again. The test links a
 * folder the way 0.6 did: the system picker (answered in English), the app's own persisted
 * grant, and the `sfz-folders` list. Its folder is `/sdcard/Music/SoundpackSfizzMigrationTest`. */
class SfzFolderMigrationTest {
    private companion object {
        const val NAME = "SoundpackSfizzMigrationTest"
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val registry = FolderRegistry.get(context)
    private val legacyPrefs = context.getSharedPreferences(SfzSetupInitializer.LEGACY_FOLDERS_PREFS, Context.MODE_PRIVATE)
    private var tree: Uri? = null

    @After
    fun cleanup() {
        legacyPrefs.edit().remove(SfzSetupInitializer.LEGACY_FOLDERS_KEY).commit()
        tree?.let { registry.remove(FolderRegistry.packIdFor(it)) }
        shell("rm -rf /sdcard/Music/$NAME")
    }

    @Test
    fun foldersFromSfizz06AppearWithoutPicking() {
        writeFolder()
        val picked = pickAsSfizz06()
        tree = picked
        val id = FolderRegistry.packIdFor(picked)
        assertNull("the folder must not be linked before the migration", registry.folder(id))
        val neverGranted = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music/SoundpackSfizzNeverGranted")
        legacyPrefs.edit().putStringSet(SfzSetupInitializer.LEGACY_FOLDERS_KEY, setOf(picked.toString(), neverGranted.toString())).commit()

        SfzSetupInitializer.migrateSfzFolders(context)

        assertFalse("the old list is removed", legacyPrefs.contains(SfzSetupInitializer.LEGACY_FOLDERS_KEY))
        assertEquals(setOf(SoundpackKind.SFZ), registry.folder(id)!!.kinds)
        assertNull("a folder without a grant is skipped", registry.folder(FolderRegistry.packIdFor(neverGranted)))

        val client = SoundpackResources.client
        val pack = client.catalog().packs.single { it.id == id }
        val entry = client.entries(pack).single()
        assertEquals("Piano/piano.sfz", entry.path)
        SoundpackResources.open(client.identityOf(entry).format()).use { snapshot ->
            assertTrue(snapshot.names.contains("Piano/samples/a.wav"))
        }
    }

    private fun writeFolder() {
        val stage = File(context.getExternalFilesDir(null), "stage").apply { deleteRecursively(); mkdirs() }
        File(stage, "Piano/samples").mkdirs()
        File(stage, "Piano/piano.sfz").writeText("<control> default_path=samples/\n<region> sample=a.wav\n")
        File(stage, "Piano/samples/a.wav").writeBytes(ByteArray(4096))
        shell("rm -rf /sdcard/Music/$NAME", "cp -r ${stage.absolutePath} /sdcard/Music/$NAME")
    }

    /** OPEN_DOCUMENT_TREE from the app's activity, then the persisted read grant, as 0.6 kept it. */
    private fun pickAsSfizz06(): Uri {
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music/$NAME")
        val result = CompletableFuture<Uri?>()
        ActivityScenario.launch(PluginManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activityResultRegistry.register("legacy-folder", ActivityResultContracts.OpenDocumentTree()) { result.complete(it) }
                    .launch(initial)
            }
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)!!.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)!!.click()
            val uri = requireNotNull(result.get(20, TimeUnit.SECONDS)) { "no folder picked" }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri
        }
    }

    /** UiAutomation runs commands without a shell, so they go through a script. */
    private fun shell(vararg commands: String): String {
        val script = File(context.getExternalFilesDir(null), "test.sh").apply { writeText(commands.joinToString("\n") + "\n") }
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"))
            .use { it.readBytes().decodeToString() }
    }
}
