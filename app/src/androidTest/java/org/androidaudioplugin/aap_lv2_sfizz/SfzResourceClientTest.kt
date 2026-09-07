package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.androidaudioplugin.samples.aap_sfizz.SfzResourceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SfzResourceClientTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun bundledPianoIsDiscoverableAndReadable() {
        SfzResourceClient.initialize(context)
        val piano = SfzResourceClient.discover().single {
            it.label == "Splendid Grand Piano (bundled)"
        }

        SfzResourceClient.open(piano.identity).use { snapshot ->
            assertEquals("Splendid Grand Piano.sfz", snapshot.entry)
            assertTrue(snapshot.entry in snapshot.names)
            assertTrue(snapshot.names.any { it.startsWith("Samples/") && it.endsWith(".flac") })
            assertTrue(snapshot.descriptors.all { it.declaredLength > 0 })
        }
    }
}
