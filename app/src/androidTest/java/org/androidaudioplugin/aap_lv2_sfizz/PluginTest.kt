package org.androidaudioplugin.aap_lv2_sfizz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.ServiceTestRule
import org.androidaudioplugin.androidaudioplugin.testing.AudioPluginServiceTesting
import org.androidaudioplugin.hosting.AudioPluginHostHelper
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

class PluginTest {
    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    private val testing = AudioPluginServiceTesting(applicationContext)
    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun testPluginInfo() {
        /* not suitable for multi-plugins service
        testing.testSinglePluginInformation {
            Assert.assertEquals("lv2:http://sfztools.github.io/sfizz", it.pluginId)
            Assert.assertEquals(21, it.parameters.size) // maybe it should be 23
            Assert.assertEquals(4, it.ports.size) // maybe it could be automatically populated...
        }*/
    }

    @Test
    fun basicServiceOperationsForAllPlugins() {
        testing.basicServiceOperationsForAllPlugins()
    }

    @Test
    fun repeatDirectServiceOperations() {
        val pluginInfo = AudioPluginHostHelper.getLocalAudioPluginService(applicationContext).plugins.first()

        for (i in 0 until 5)
            testing.testInstancingAndProcessing(pluginInfo)
    }
}
