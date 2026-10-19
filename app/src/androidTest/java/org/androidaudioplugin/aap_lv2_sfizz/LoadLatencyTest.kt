package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.PortInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.samples.aap_sfizz.AudioPluginLV2ResourceBridge
import org.androidaudioplugin.samples.aap_sfizz.SoundpackResources
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.ui.compose.app.PluginManagerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

/** First-load latency by source (soundpack-provider-for-android, docs/PLAN.md Phase 7). It
 * reports rather than asserts: each case logs one line under the tag `LoadLatency`.
 *
 * A load ends when sfizz has parsed the instrument and preloaded its samples, which is when it
 * can play; each load is followed by a note, which must sound within the first blocks. Android
 * keeps the files in its page cache between runs, and dropping it needs root, so the numbers
 * are with a warm page cache. Loads are a second apart, as when a user picks instruments.
 *
 * Run each case in its own `am instrument` run, so that its first load is the first in the
 * process: sfizz's soundpack client is not initialised yet, and the pack app's or the Soundpack
 * Manager's process is killed before it. The identities are looked up beforehand with another
 * client, untimed, as a restored state brings them. Needs Salamander (aap-sfz-salamander-drumkit)
 * installed. The folder case needs Salamander's `ALL.sfz` and its samples in a linked or shared
 * folder, and is skipped otherwise. The linked-folder case links the kit's folder in this app
 * through the system picker (in English), in place or with `-e linkMode imported`, from
 * `-e kitPath <path under /sdcard>`, by default
 * `Music/SoundpackLatencyKit`: `adb push <aap-sfz-salamander-drumkit>/app/src/main/assets/pack
 * /sdcard/Music/SoundpackLatencyKit`. */
class LoadLatencyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pluginId = "lv2:http://sfztools.github.io/sfizz"
    private val frames = 1024
    private val runs = 6

    /** For looking up identities only: the loads go through SoundpackResources' own client. */
    private val lookup = SoundpackClient(context, SoundpackKind.SFZ)

    private fun nativeServiceFor(instanceId: Int): Long =
        AudioPluginServiceHelper::class.java
            .getDeclaredMethod("getServiceInstanceForInstance", String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(null, pluginId, instanceId) as Long

    private fun NativeRemotePluginInstance.param(needle: String): Double {
        val idx = (0 until getParameterCount()).single { getParameter(it).name.contains(needle, ignoreCase = true) }
        return getParameterValue(idx)
    }

    /** MIDI 2.0 note on, as aap-core's on-screen keyboard sends it. */
    private fun NativeRemotePluginInstance.noteOn(note: Int) {
        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        buffer.asIntBuffer().put((0x40 shl 24) + (0x90 shl 16) + (note shl 8)).put(0xf8 shl 24)
        addEventUmpInput(buffer, 8)
    }

    /** The first block after a note on whose audio outputs are not silent, from 1; 0 if none. */
    private fun NativeRemotePluginInstance.blocksToSound(note: Int, blocks: Int = 8): Int {
        val outputs = (0 until getPortCount()).filter {
            val port = getPort(it)
            port.direction == PortInformation.PORT_DIRECTION_OUTPUT && port.content == PortInformation.PORT_CONTENT_TYPE_AUDIO
        }
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        noteOn(note)
        for (block in 1..blocks) {
            process(frames, 0)
            for (port in outputs) {
                buffer.clear()
                getPortBuffer(port, buffer, frames * 4)
                val samples = buffer.asFloatBuffer()
                if ((0 until frames).any { abs(samples.get(it)) > 1e-4f })
                    return block
            }
        }
        return 0
    }

    private fun withInstance(block: (NativeRemotePluginInstance) -> Unit) {
        val pluginInfo: PluginInformation = AudioPluginServiceHelper.getLocalAudioPluginService(context)
            .plugins.single { it.pluginId == pluginId }
        val host = AudioPluginClientBase(context)
        runBlocking { host.connectToPluginService(pluginInfo.packageName) }
        try {
            val instance = host.instantiateNativePlugin(pluginInfo)
            instance.prepare(frames, 48000, 0x10000)
            instance.activate()
            block(instance)
            instance.deactivate()
            instance.destroy()
        } finally {
            host.disconnectPluginService(pluginInfo.packageName)
            host.dispose()
        }
    }

    /** UiAutomation runs commands with the shell's rights, which `am force-stop` needs. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    /** The system folder picker, answered as a user would, from this app's activity. */
    private fun pickFolder(path: String): Uri {
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
        val result = CompletableFuture<Uri?>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(PluginManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activityResultRegistry.register("import-folder", ActivityResultContracts.OpenDocumentTree()) { result.complete(it) }
                    .launch(initial)
            }
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)!!.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)!!.click()
            return requireNotNull(result.get(20, TimeUnit.SECONDS)) { "no folder picked" }
        }
    }

    private fun load(instance: NativeRemotePluginInstance, identity: String) =
        AudioPluginLV2ResourceBridge.load(nativeServiceFor(instance.instanceId), instance.instanceId, identity)

    private fun identityOf(packId: String, entryPath: String): String {
        val pack = lookup.catalog().packs.single { it.id == packId }
        return lookup.identityOf(lookup.entries(pack).single { it.path == entryPath }).format()
    }

    /** Opens and closes every file of [identity]'s pack through sfizz's client, one at a time
     * and 8 at a time as the prefetch does: the soundpack side of a load, without sfizz. */
    private fun logOpenCost(label: String, identity: String) {
        SoundpackResources.client.open(requireNotNull(SoundpackIdentity.parse(identity))).use { session ->
            val paths = session.files.map { it.path }
            val start = SystemClock.elapsedRealtimeNanos()
            paths.forEach { session.openFile(it).close() }
            val serial = (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / paths.size
            val pool = Executors.newFixedThreadPool(8)
            val parallelStart = SystemClock.elapsedRealtimeNanos()
            paths.map { path -> pool.submit { session.openFile(path).close() } }.forEach { it.get() }
            val parallel = (SystemClock.elapsedRealtimeNanos() - parallelStart) / 1e6 / paths.size
            pool.shutdown()
            Log.i("LoadLatency", "%s: %d files opened one at a time, %.2f ms each; 8 at a time, %.2f ms each".format(
                label, paths.size, serial, parallel))
        }
    }

    /** Times [runs] loads of an instrument. [before] runs ahead of each load, untimed. */
    private fun measure(
        label: String,
        instance: NativeRemotePluginInstance,
        note: Int,
        before: () -> Unit = {},
        load: () -> Boolean,
    ) {
        val times = mutableListOf<Double>()
        var regions = 0.0
        var samples = 0.0
        repeat(runs) { run ->
            if (run > 0)
                Thread.sleep(1000)
            before()
            val start = SystemClock.elapsedRealtimeNanos()
            assertTrue("$label: load $run failed", load())
            times += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            instance.process(frames, 0)
            regions = instance.param("regions")
            samples = instance.param("samples")
            val block = instance.blocksToSound(note)
            assertTrue("$label: no sound after load $run", block > 0)
        }
        val warm = times.drop(1).sorted()
        Log.i("LoadLatency", "%s: first %.0f ms; then median %.0f ms (%.0f to %.0f); %.0f regions, %.0f samples; all: %s".format(
            label, times[0], warm[warm.size / 2], warm.first(), warm.last(), regions, samples,
            times.joinToString { "%.0f".format(it) }))
    }

    /** The baseline, as before the soundpack libraries: the bundled piano from the copy in app
     * storage, by file path. A fresh instance has loaded it already and saves that path. sfizz
     * keeps the samples of a file it loads again, so Salamander's kick is loaded in between. */
    @Test
    fun pianoFromFile() = withInstance { instance ->
        val fileState = ByteArray(instance.getStateSize()).also { instance.getState(it) }
        assertTrue("the default state must name a file", String(fileState, Charsets.UTF_8).contains("sfzfile"))
        val kick = identityOf("org.androidaudioplugin.sfz.salamander-drumkit", "kick.sfz")
        measure("piano, file in app storage", instance, 60, before = { assertTrue(load(instance, kick)) }) {
            instance.setState(fileState)
            true
        }
    }

    /** The same piano, published by this app as a soundpack (its own APK assets, in process). */
    @Test
    fun pianoThroughSoundpack() = withInstance { instance ->
        val piano = identityOf("io.github.sfzinstruments.splendid-grand-piano", "Splendid Grand Piano.sfz")
        measure("piano, soundpack from this APK", instance, 60) { load(instance, piano) }
    }

    /** Salamander's kit (`ALL.sfz`) from its pack app: another app's provider, started by the
     * first load. */
    @Test
    fun salamanderFromPackApp() = withInstance { instance ->
        val kit = identityOf("org.androidaudioplugin.sfz.salamander-drumkit", "ALL.sfz")
        shell("am force-stop org.androidaudioplugin.sfz.salamanderdrumkit")
        measure("Salamander kit, soundpack from its pack app", instance, 36) { load(instance, kit) }
        logOpenCost("Salamander kit, soundpack from its pack app", kit)
    }

    /** The same kit from a folder (SAF), linked in this app or shared in the Soundpack Manager. */
    @Test
    fun salamanderFromFolder() = withInstance { instance ->
        val entry = lookup.catalog().packs.filter { it.source == "folder" }.firstNotNullOfOrNull { pack ->
            lookup.entries(pack).firstOrNull { it.path.endsWith("ALL.sfz") && it.path.contains("salamander", ignoreCase = true) }
        }
        assumeTrue("no folder holds Salamander's ALL.sfz", entry != null)
        val kit = lookup.identityOf(entry!!).format()
        Log.i("LoadLatency", "folder kit: $kit")
        shell("am force-stop org.androidaudioplugin.soundpack.manager")
        measure("Salamander kit, soundpack from a folder", instance, 36) { load(instance, kit) }
        logOpenCost("Salamander kit, soundpack from a folder", kit)
    }

    /** The kit's folder imported in the Soundpack Manager and read from there, from the
     * Manager's copy: linked here, shared with the Manager through its share screen with
     * "Import a copy", and unlinked here, so that loads go through the Manager. Removed from
     * the Manager afterwards, in its Folders tab, which deletes the copy. */
    @Test
    fun salamanderFromManagerImported() {
        val path = InstrumentationRegistry.getArguments().getString("kitPath") ?: "Music/SoundpackLatencyKit"
        assumeTrue("no Salamander kit at /sdcard/$path", shell("ls /sdcard/$path/ALL.sfz").contains("ALL.sfz"))
        val manager = SoundpackContract.Hub.PACKAGE
        val registry = FolderRegistry.get(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val folder = registry.add(pickFolder(path), setOf(SoundpackKind.SFZ))
        var shared = false
        try {
            context.startActivity(registry.shareWithHubIntent(folder.id)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.wait(Until.findObject(By.textStartsWith("Import a copy")), 20_000)!!.click()
            device.waitForIdle()
            // The row is one selectable node with the label, or the label's parent.
            val choice = device.findObject(By.textStartsWith("Import a copy"))
            val chosen = listOfNotNull(choice, choice?.parent).any { it.isSelected || it.isChecked }
            assertTrue("Import a copy is chosen", chosen)
            val started = SystemClock.elapsedRealtime()
            device.wait(Until.findObject(By.text("Share").enabled(true)), 10_000)!!.click()
            shared = true
            // The share screen closes when the Manager has linked the folder and copied it.
            assertTrue("the Manager is still importing", device.wait(Until.gone(By.textStartsWith("Import a copy")), 300_000))
            Log.i("LoadLatency", "the Manager imported the kit in %.1f s".format((SystemClock.elapsedRealtime() - started) / 1e3))
            registry.remove(folder.id)
            val pack = lookup.catalog().packs.single { it.id == folder.id }
            assertEquals(manager, pack.endpoint.packageName)
            val kit = lookup.identityOf(lookup.entries(pack).single { it.path == "ALL.sfz" }).format()
            shell("am force-stop $manager")
            withInstance { instance ->
                measure("Salamander kit, soundpack from a folder imported in the Manager", instance, 36) { load(instance, kit) }
                logOpenCost("Salamander kit, soundpack from a folder imported in the Manager", kit)
            }
        } finally {
            registry.remove(folder.id)
            if (shared)
                removeFromManager(folder.displayName)
        }
    }

    /** As a user would, in the Manager's Folders tab (SFZ, the first type). */
    private fun removeFromManager(name: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(context.packageManager.getLaunchIntentForPackage(SoundpackContract.Hub.PACKAGE)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("Folders")), 15_000)!!.click()
        device.wait(Until.findObject(By.scrollable(true)), 10_000)?.scrollUntil(Direction.DOWN, Until.findObject(By.text(name)))
        device.wait(Until.findObject(By.hasChild(By.text(name))), 10_000)!!.findObject(By.text("Remove")).click()
        assertTrue("removed from the Manager", device.wait(Until.gone(By.text(name)), 30_000))
        device.pressHome()
    }

    /** The kit's folder linked in this app (no Soundpack Manager in between), in place or, with
     * `-e linkMode imported`, imported (D6); removed again afterwards, with its copy. An import
     * writes the whole kit, so the loads wait until that is flushed. */
    @Test
    fun salamanderFromLinkedFolder() {
        val path = InstrumentationRegistry.getArguments().getString("kitPath") ?: "Music/SoundpackLatencyKit"
        val imported = InstrumentationRegistry.getArguments().getString("linkMode") == "imported"
        assumeTrue("no Salamander kit at /sdcard/$path", shell("ls /sdcard/$path/ALL.sfz").contains("ALL.sfz"))
        val registry = FolderRegistry.get(context)
        val tree = pickFolder(path)
        val started = SystemClock.elapsedRealtime()
        val folder = registry.add(tree, setOf(SoundpackKind.SFZ), imported)
        try {
            if (imported) {
                Log.i("LoadLatency", "imported %d files (%d MB) in %.1f s%s".format(folder.fileCount, folder.totalBytes / (1024 * 1024),
                    (SystemClock.elapsedRealtime() - started) / 1e3, folder.importError?.let { "; $it" } ?: ""))
                shell("sync")
                Thread.sleep(10_000)
            }
            val kit = identityOf(folder.id, "ALL.sfz")
            withInstance { instance ->
                val label = "Salamander kit, soundpack from this app's folder, " + if (imported) "imported" else "in place"
                measure(label, instance, 36) { load(instance, kit) }
                logOpenCost(label, kit)
            }
        } finally {
            registry.remove(folder.id)
        }
    }
}
