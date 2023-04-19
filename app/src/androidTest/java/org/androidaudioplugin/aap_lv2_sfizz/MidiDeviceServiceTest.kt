package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.ServiceTestRule
import org.androidaudioplugin.androidaudioplugin.testing.AudioPluginMidiDeviceServiceTesting
import org.junit.Rule
import org.junit.Test

class MidiDeviceServiceTest {

    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    private val testing = AudioPluginMidiDeviceServiceTesting(applicationContext)
    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun basicServiceOperations() {
        // Replace plugin name after copy-pasting this code.
        testing.basicServiceOperations("sfizz")
    }

    @Test
    fun repeatDirectServieOperations() {
        for (i in 0 until 5)
            basicServiceOperations()
    }

}
