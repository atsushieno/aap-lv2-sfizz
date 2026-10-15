package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.samples.aap_sfizz.AudioPluginLV2ResourceBridge
import org.androidaudioplugin.samples.aap_sfizz.SoundpackResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** SFZ selection must survive getState()/setState(), samples included. Needs the Salamander
 * drumkit pack app (aap-sfz-salamander-drumkit) installed. */
class StatePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pluginId = "lv2:http://sfztools.github.io/sfizz"

    private class Saved(val state: ByteArray, val regions: Double, val samples: Double)

    private fun NativeRemotePluginInstance.param(needle: String): Double {
        val idx = (0 until getParameterCount())
            .single { getParameter(it).name.contains(needle, ignoreCase = true) }
        return getParameterValue(idx)
    }

    private fun <T> withHost(block: (AudioPluginClientBase, PluginInformation) -> T): T {
        val pluginInfo = AudioPluginServiceHelper.getLocalAudioPluginService(context)
            .plugins.single { it.pluginId == pluginId }
        val host = AudioPluginClientBase(context)
        runBlocking { host.connectToPluginService(pluginInfo.packageName) }
        try {
            return block(host, pluginInfo)
        } finally {
            host.disconnectPluginService(pluginInfo.packageName)
            host.dispose()
        }
    }

    /** The native service that hosts [instanceId]. `getServiceInstance(pluginId)` returns the
     * first service bound for the plugin in this process, which belongs to another test once
     * PluginTest has run. aap-core's per-instance lookup is private, so call it reflectively. */
    private fun nativeServiceFor(instanceId: Int): Long =
        AudioPluginServiceHelper::class.java
            .getDeclaredMethod("getServiceInstanceForInstance", String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(null, pluginId, instanceId) as Long

    private fun salamanderIdentity(): String {
        val client = SoundpackResources.client
        val pack = client.catalog().packs.single { it.id == "org.androidaudioplugin.sfz.salamander-drumkit" }
        return client.identityOf(client.entries(pack).first()).format()
    }

    private fun saveSalamander(host: AudioPluginClientBase, pluginInfo: PluginInformation, identity: String): Saved {
        val source = host.instantiateNativePlugin(pluginInfo)
        source.prepare(1024, 48000, 0x10000)
        source.activate()
        assertTrue(AudioPluginLV2ResourceBridge.load(nativeServiceFor(source.instanceId), source.instanceId, identity))
        repeat(4) { source.process(1024, 0) }
        val regions = source.param("regions")
        val samples = source.param("samples")
        assertNotEquals("must differ from the default piano", 360.0, regions, 5.0)
        assertTrue("source must load samples", samples > 0)

        val state = ByteArray(source.getStateSize().also { assertTrue("empty state", it > 0) })
        source.getState(state)
        source.deactivate()
        source.destroy()
        return Saved(state, regions, samples)
    }

    /** Restores [state] into a fresh instance, with no load(), and returns its counts and the
     * state it saves again. */
    private fun restore(host: AudioPluginClientBase, pluginInfo: PluginInformation, state: ByteArray, onMain: Boolean): Saved {
        val restored = host.instantiateNativePlugin(pluginInfo)
        restored.prepare(1024, 48000, 0x10000)
        restored.activate()
        if (onMain)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { restored.setState(state) }
        else
            restored.setState(state)
        repeat(4) { restored.process(1024, 0) }
        val again = ByteArray(restored.getStateSize())
        restored.getState(again)
        val result = Saved(again, restored.param("regions"), restored.param("samples"))
        restored.deactivate()
        restored.destroy()
        return result
    }

    @Test
    fun salamanderSelectionRoundTripsThroughState() = withHost { host, pluginInfo ->
        val identity = salamanderIdentity()
        val saved = saveSalamander(host, pluginInfo, identity)
        val ttl = String(saved.state, Charsets.UTF_8)
        assertTrue("identity missing from state: $ttl", ttl.contains("sfz-selection:1#identity"))
        assertTrue("soundpack identity missing from state: $ttl", ttl.contains(identity))
        assertFalse("stale legacy sfzfile in state: $ttl",
            ttl.contains("Splendid%20Grand%20Piano.sfz") || ttl.contains("Splendid Grand Piano.sfz"))

        // Restore from the main thread too, as a host does from an ActivityResult callback.
        for (onMain in booleanArrayOf(false, true)) {
            val restored = restore(host, pluginInfo, saved.state, onMain)
            assertEquals("regions (onMain=$onMain)", saved.regions, restored.regions, 5.0)
            assertEquals("samples (onMain=$onMain)", saved.samples, restored.samples, 0.0)
        }
    }

    /** D4: selections saved by AAP Sfizz 0.6 are not translated. They restore as an
     * unavailable instrument (nothing loaded, nothing substituted), and stay in the state. */
    @Test
    fun selectionsFromSfizz06RestoreAsUnavailable() = withHost { host, pluginInfo ->
        val identity = salamanderIdentity()
        val ttl = String(saveSalamander(host, pluginInfo, identity).state, Charsets.UTF_8)
        assertTrue(ttl.contains(identity))
        val legacy = "aap-sfz://org.androidaudioplugin.sfz.salamanderdrumkit/" +
            "org.androidaudioplugin.sfz.salamanderdrumkit.SalamanderDrumkitService/salamander-drumkit?revision=1"
        val legacyState = ttl.replace(identity, legacy).toByteArray(Charsets.UTF_8)

        val restored = restore(host, pluginInfo, legacyState, onMain = false)
        assertEquals("no instrument is substituted", 0.0, restored.regions, 0.0)
        assertEquals(0.0, restored.samples, 0.0)
        assertTrue("the selection is kept", String(restored.state, Charsets.UTF_8).contains(legacy))
    }

    /** Across a process restart, in two instrumentation runs; skipped otherwise:
     *
     * `adb shell am instrument -w -e stateRestart save -e class <this class>#stateRestoresInANewProcess <runner>`,
     * then the same with `-e stateRestart restore`. */
    @Test
    fun stateRestoresInANewProcess() {
        val phase = InstrumentationRegistry.getArguments().getString("stateRestart")
        assumeTrue("run with -e stateRestart save, then restore", phase == "save" || phase == "restore")
        val file = File(context.filesDir, "state-restart-test.properties")
        withHost { host, pluginInfo ->
            if (phase == "save") {
                val saved = saveSalamander(host, pluginInfo, salamanderIdentity())
                Properties().apply {
                    setProperty("pid", Process.myPid().toString())
                    setProperty("state", String(saved.state, Charsets.UTF_8))
                    setProperty("regions", saved.regions.toString())
                    setProperty("samples", saved.samples.toString())
                }.let { props -> file.outputStream().use { props.store(it, null) } }
            } else {
                val props = Properties().apply { file.inputStream().use { load(it) } }
                file.delete()
                assertNotEquals("must run in a new process", props.getProperty("pid"), Process.myPid().toString())
                val restored = restore(host, pluginInfo, props.getProperty("state").toByteArray(Charsets.UTF_8), onMain = true)
                assertEquals(props.getProperty("regions").toDouble(), restored.regions, 5.0)
                assertEquals(props.getProperty("samples").toDouble(), restored.samples, 0.0)
            }
        }
    }
}
