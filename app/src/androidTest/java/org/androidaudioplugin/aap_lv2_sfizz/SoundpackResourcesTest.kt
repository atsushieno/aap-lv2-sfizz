package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.androidaudioplugin.samples.aap_sfizz.SoundpackResources
import org.androidaudioplugin.soundpack.testkit.ProviderConformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled piano is a soundpack like any pack app, and the native bridge reads it through
 * [SoundpackResources]. */
class SoundpackResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bundledAuthority = "${context.packageName}.soundpack.sfz"

    @Test
    fun bundledPianoProviderConforms() {
        ProviderConformance(context, bundledAuthority).assertConforms()
    }

    @Test
    fun bundledPianoOpensForTheBridge() {
        val client = SoundpackResources.client
        val piano = client.catalog().packs.single { it.id == "io.github.sfzinstruments.splendid-grand-piano" }
        assertEquals(bundledAuthority, piano.endpoint.authority)
        val entry = client.entries(piano).single()

        SoundpackResources.open(client.identityOf(entry).format()).use { snapshot ->
            assertEquals("Splendid Grand Piano.sfz", snapshot.entry)
            assertTrue("files are opened when sfizz asks for them", snapshot.lazy)
            assertTrue(snapshot.entry in snapshot.names)
            assertTrue(snapshot.names.any { it.startsWith("Samples/") && it.endsWith(".flac") })
            snapshot.openResource(snapshot.names.first { it.endsWith(".flac") }).use {
                assertTrue(it.declaredLength > 0)
            }
        }
    }

    @Test
    fun selectionsFromBeforeTheSoundpackContractAreNotResolved() {
        assertThrows(IllegalArgumentException::class.java) {
            SoundpackResources.open("aap-sfz://org.androidaudioplugin.ports.lv2.sfizz/bundled/Splendid%20Grand%20Piano.sfz")
        }
    }
}
