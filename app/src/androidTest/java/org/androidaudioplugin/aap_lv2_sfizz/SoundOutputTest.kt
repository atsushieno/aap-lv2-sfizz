package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.PortInformation
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.androidaudioplugin.samples.aap_sfizz.AudioPluginLV2ResourceBridge
import org.androidaudioplugin.samples.aap_sfizz.SoundpackResources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** sfizz must make sound: the default piano, and instruments selected through soundpack
 * identities. Plays one note and measures the peak on the audio outputs. */
class SoundOutputTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pluginId = "lv2:http://sfztools.github.io/sfizz"
    private val frames = 1024

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

    /** Peak of every audio output over [blocks] blocks after a note on. */
    private fun NativeRemotePluginInstance.peakAfterNote(note: Int, blocks: Int = 24): Float {
        val outputs = (0 until getPortCount()).filter {
            val port = getPort(it)
            port.direction == PortInformation.PORT_DIRECTION_OUTPUT && port.content == PortInformation.PORT_CONTENT_TYPE_AUDIO
        }
        assertTrue("no audio outputs", outputs.isNotEmpty())
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        var peak = 0f
        noteOn(note)
        repeat(blocks) {
            process(frames, 0)
            for (port in outputs) {
                buffer.clear()
                getPortBuffer(port, buffer, frames * 4)
                val samples = buffer.asFloatBuffer()
                for (i in 0 until frames)
                    peak = maxOf(peak, abs(samples.get(i)))
            }
        }
        return peak
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

    private fun identityOf(packId: String, entryPath: String? = null): String {
        val client = SoundpackResources.client
        val pack = client.catalog().packs.single { it.id == packId }
        val entries = client.entries(pack)
        return client.identityOf(entryPath?.let { path -> entries.single { it.path == path } } ?: entries.first()).format()
    }

    private fun assertSound(label: String, peak: Float) {
        Log.i("SoundOutputTest", "$label: peak $peak")
        assertTrue("$label is silent (peak $peak)", peak > 1e-4f)
    }

    @Test
    fun defaultPianoMakesSound() = withInstance { instance ->
        assertSound("default piano", instance.peakAfterNote(60))
    }

    @Test
    fun bundledPianoThroughSoundpackMakesSound() = withInstance { instance ->
        val identity = identityOf("io.github.sfzinstruments.splendid-grand-piano")
        assertTrue(AudioPluginLV2ResourceBridge.load(nativeServiceFor(instance.instanceId), instance.instanceId, identity))
        repeat(4) { instance.process(frames, 0) }
        Log.i("SoundOutputTest", "bundled piano: ${instance.param("regions")} regions, ${instance.param("samples")} samples")
        assertSound("bundled piano", instance.peakAfterNote(60))
    }

    @Test
    fun salamanderThroughSoundpackMakesSound() = withInstance { instance ->
        val identity = identityOf("org.androidaudioplugin.sfz.salamander-drumkit", "kick.sfz")
        assertTrue(AudioPluginLV2ResourceBridge.load(nativeServiceFor(instance.instanceId), instance.instanceId, identity))
        repeat(4) { instance.process(frames, 0) }
        Log.i("SoundOutputTest", "salamander kick: ${instance.param("regions")} regions, ${instance.param("samples")} samples")
        assertSound("salamander kick", instance.peakAfterNote(36))
    }
}
