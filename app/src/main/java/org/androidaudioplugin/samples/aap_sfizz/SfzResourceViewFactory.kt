package org.androidaudioplugin.samples.aap_sfizz

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Size
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.AudioPluginViewFactory
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.host.ui.SoundpackFoldersActivity
import org.androidaudioplugin.soundpack.ui.SoundpackPicker
import org.androidaudioplugin.ui.compose.ComposeAudioPluginViewFactory
import kotlin.math.roundToInt

/** Hosted in the plugin process by AudioPluginViewService, with an explicit instance ID.
 * SFZ content comes from installed packs, the folders linked here ("SFZ folders"), and the
 * folders shared in the Soundpack Manager. */
class SfzResourceViewFactory : AudioPluginViewFactory() {
    private companion object {
        const val PREFERRED_WIDTH_DP = 420f
        const val PREFERRED_HEIGHT_DP = 640f
    }

    override fun getPreferredSize(context: Context, pluginId: String, instanceId: Int): Size {
        val density = context.resources.displayMetrics.density
        return Size(
            (PREFERRED_WIDTH_DP * density).roundToInt(),
            (PREFERRED_HEIGHT_DP * density).roundToInt(),
        )
    }

    override fun createView(context: Context, pluginId: String, instanceId: Int): View {
        // Resolve inside AAP's instance scope, before callbacks leave createNativeView().
        val service = AudioPluginServiceHelper.getServiceInstance(pluginId)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                MaterialTheme {
                    val client = SoundpackResources.client
                    var status by remember { mutableStateOf("Select an instrument to load it.") }
                    var loadingResource by remember { mutableStateOf(false) }
                    var selected by remember { mutableStateOf<SoundpackClient.Entry?>(null) }
                    var selectorExpanded by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    fun load(entry: SoundpackClient.Entry) {
                        loadingResource = true
                        status = "Loading ${entry.label}…"
                        val identity = client.identityOf(entry).format()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    check(AudioPluginLV2ResourceBridge.load(service, instanceId, identity)) {
                                        "Unable to load instrument; check the pack and its sample format."
                                    }
                                }
                            }
                            result.onSuccess {
                                selected = entry
                                status = "Loaded: ${entry.label}"
                                selectorExpanded = false
                            }.onFailure {
                                status = it.message ?: "SFZ loading failed"
                            }
                            loadingResource = false
                        }
                    }

                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { selectorExpanded = !selectorExpanded },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (selectorExpanded) "▼ SFZ instruments"
                                    else "▶ SFZ: ${selected?.label ?: "Select instrument"}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selectorExpanded) {
                                Text(status, style = MaterialTheme.typography.titleMedium)
                                SoundpackPicker(
                                    client = client,
                                    selected = selected?.let { client.identityOf(it) },
                                    onSelect = { if (!loadingResource) load(it) },
                                    modifier = Modifier.fillMaxWidth().height(240.dp),
                                )
                                Button(onClick = {
                                    context.startActivity(Intent(context, SoundpackFoldersActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }, enabled = !loadingResource, modifier = Modifier.fillMaxWidth()) {
                                    Text("SFZ folders")
                                }
                            }
                            HorizontalDivider()
                            if (!loadingResource) {
                                AndroidView(
                                    factory = { createParameterViewCompat(context, pluginId, instanceId) },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                )
                            } else {
                                Spacer(Modifier.fillMaxWidth().weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createParameterView(context: Context, pluginId: String, instanceId: Int): View =
        ComposeAudioPluginViewFactory().createView(context, pluginId, instanceId)

    private fun createParameterViewCompat(context: Context, pluginId: String, instanceId: Int): View =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            createParameterView(context, pluginId, instanceId)
        } else {
            ComposeView(context).apply {
                setContent {
                    MaterialTheme { Text("Plugin parameters require Android 11 or later.") }
                }
            }
        }
}
