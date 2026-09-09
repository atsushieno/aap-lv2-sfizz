package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.samples.aap_sfizz.AudioPluginLV2ResourceBridge
import org.androidaudioplugin.samples.aap_sfizz.SfzResourceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SFZ selection must survive getState()/setState(), samples included. */
class StatePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pluginId = "lv2:http://sfztools.github.io/sfizz"

    private fun NativeRemotePluginInstance.param(needle: String): Double {
        val idx = (0 until getParameterCount())
            .single { getParameter(it).name.contains(needle, ignoreCase = true) }
        return getParameterValue(idx)
    }

    @Test
    fun salamanderSelectionRoundTripsThroughState() {
        SfzResourceClient.initialize(context)
        val salamander = SfzResourceClient.discover()
            .first { it.group.contains("Salamander", true) || it.label.contains("Salamander", true) }

        val pluginInfo = AudioPluginServiceHelper.getLocalAudioPluginService(context)
            .plugins.single { it.pluginId == pluginId }
        val host = AudioPluginClientBase(context)
        runBlocking { host.connectToPluginService(pluginInfo.packageName) }
        val nativeService = AudioPluginServiceHelper.getServiceInstance(pluginId)

        try {
            val source = host.instantiateNativePlugin(pluginInfo)
            source.prepare(1024, 48000, 0x10000)
            source.activate()
            assertTrue(AudioPluginLV2ResourceBridge.load(nativeService, source.instanceId, salamander.identity))
            repeat(4) { source.process(1024, 0) }
            val regions = source.param("regions")
            val samples = source.param("samples")
            assertNotEquals("must differ from the default piano", 360.0, regions, 5.0)
            assertTrue("source must load samples", samples > 0)

            val saved = ByteArray(source.getStateSize().also { assertTrue("empty state", it > 0) })
            source.getState(saved)
            val ttl = String(saved, Charsets.UTF_8)
            assertTrue("identity missing from state: $ttl", ttl.contains("sfz-selection:1#identity"))
            assertFalse("stale legacy sfzfile in state: $ttl",
                ttl.contains("Splendid%20Grand%20Piano.sfz") || ttl.contains("Splendid Grand Piano.sfz"))

            source.deactivate()
            source.destroy()

            // Fresh instance, no load()/attach(). Restore from the main thread, as a
            // host does from an ActivityResult callback, and before prepare() too.
            for (onMain in booleanArrayOf(false, true)) {
                val restored = host.instantiateNativePlugin(pluginInfo)
                restored.prepare(1024, 48000, 0x10000)
                restored.activate()
                if (onMain)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { restored.setState(saved) }
                else
                    restored.setState(saved)
                repeat(4) { restored.process(1024, 0) }
                assertEquals("regions (onMain=$onMain)", regions, restored.param("regions"), 5.0)
                assertEquals("samples (onMain=$onMain)", samples, restored.param("samples"), 0.0)
                restored.deactivate()
                restored.destroy()
            }
        } finally {
            host.disconnectPluginService(pluginInfo.packageName)
            host.dispose()
        }
    }
}
