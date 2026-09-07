package org.androidaudioplugin.samples.aap_sfizz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.PortInformation
import org.androidaudioplugin.ui.compose.app.PluginDetails
import org.androidaudioplugin.ui.compose.app.PluginDetailsScope
import org.androidaudioplugin.ui.compose.app.PluginList
import org.androidaudioplugin.ui.compose.app.PluginManagerScope
import org.androidaudioplugin.ui.compose.app.PluginManagerTheme

/** Uses normal Activity back handling; never schedules a process-wide exit. */
class SfizzActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = remember {
                PluginManagerScope(this, listOf(
                    AudioPluginServiceHelper.getLocalAudioPluginService(this)
                ).toMutableStateList())
            }
            var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
            val selected = scope.pluginServices.flatMap { it.plugins }
                .firstOrNull { it.pluginId == selectedId }
            BackHandler(enabled = selected != null) { selectedId = null }
            PluginManagerTheme {
                Scaffold(topBar = {
                    TopAppBar(title = { Text(if (selected == null) "Plugins in this application" else "Plugins") })
                }) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (selected == null)
                            PluginList(scope, onSelectItem = { selectedId = it })
                        else if (selected.audioOutputCount() > 2)
                            MultiOutputPluginDetails(selected, scope)
                        else
                            PluginDetails(selected, scope)
                    }
                }
            }
        }
    }
}

private fun PluginInformation.audioOutputCount() = ports.count {
    it.direction == PortInformation.PORT_DIRECTION_OUTPUT &&
        it.content == PortInformation.PORT_CONTENT_TYPE_AUDIO
}

@Composable
private fun MultiOutputPluginDetails(
    pluginInfo: PluginInformation,
    manager: PluginManagerScope,
) {
    val scope = remember(pluginInfo) { PluginDetailsScope(pluginInfo, manager) }
    val instance by scope.instance
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scope) {
        runCatching { scope.instantiatePlugin() }
            .onFailure { error = it.message ?: "Unable to instantiate plugin" }
    }
    DisposableEffect(scope) {
        onDispose { scope.close() }
    }

    when {
        error != null -> Text(error!!, modifier = Modifier.padding(16.dp))
        instance == null -> Text(
            "Instantiating ${pluginInfo.displayName}…",
            modifier = Modifier.padding(16.dp),
        )
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                "${pluginInfo.audioOutputCount()}-output plugin: audio preview is disabled in this stereo activity.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AndroidView(
                factory = { context ->
                    SfzResourceViewFactory().createView(
                        context,
                        requireNotNull(pluginInfo.pluginId),
                        instance!!.instanceId,
                    )
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
