package org.androidaudioplugin.samples.aap_sfizz

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import org.androidaudioplugin.ui.compose.ComposeAudioPluginViewFactory
import kotlin.math.roundToInt

/** Hosted in the plugin process by AudioPluginViewService, with an explicit instance ID. */
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
                    var choices by remember { mutableStateOf(emptyList<SfzResourceClient.Choice>()) }
                    var status by remember { mutableStateOf("Finding SFZ packs…") }
                    var loading by remember { mutableStateOf(true) }
                    var loadingResource by remember { mutableStateOf(false) }
                    var selectedIdentity by remember { mutableStateOf<String?>(null) }
                    var selectorExpanded by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    fun discover() {
                        loading = true
                        status = "Finding SFZ packs…"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { SfzResourceClient.discover() } }
                                .onSuccess {
                                    choices = it
                                    status = if (it.isEmpty())
                                        "No SFZ packs installed. Install an SFZ provider APK."
                                    else
                                        "Select an instrument to load it."
                                }
                                .onFailure { status = it.message ?: "SFZ discovery failed" }
                            loading = false
                        }
                    }

                    val folderRevision by SfzFolders.revision.collectAsState()
                    LaunchedEffect(folderRevision) { discover() }
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val selectedLabel = choices.firstOrNull {
                                it.identity == selectedIdentity
                            }?.label
                            OutlinedButton(
                                onClick = { selectorExpanded = !selectorExpanded },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (selectorExpanded)
                                        "▼ SFZ instruments"
                                    else
                                        "▶ SFZ: ${selectedLabel ?: if (loading) "Finding…" else "Select instrument"}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selectorExpanded) {
                                Text(status, style = MaterialTheme.typography.titleMedium)
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().height(112.dp),
                                ) {
                                    itemsIndexed(choices, key = { _, choice -> choice.identity }) { position, choice ->
                                        val selected = choice.identity == selectedIdentity
                                        Text(
                                            text = choice.label,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                                    else MaterialTheme.colorScheme.surface
                                                )
                                                .clickable(enabled = !loading) {
                                                    // The visible row and resource use the same zero-based index.
                                                    val clicked = choices[position]
                                                    loading = true
                                                    loadingResource = true
                                                    status = "Loading ${clicked.label}…"
                                                    scope.launch {
                                                        val result = withContext(Dispatchers.IO) {
                                                            runCatching {
                                                                check(AudioPluginLV2ResourceBridge.load(service, instanceId, clicked.identity)) {
                                                                    "Unable to load instrument; check the provider and sample format."
                                                                }
                                                            }
                                                        }
                                                        result.onSuccess {
                                                            selectedIdentity = clicked.identity
                                                            status = "Loaded: ${clicked.label}"
                                                            selectorExpanded = false
                                                        }.onFailure {
                                                            status = it.message ?: "SFZ loading failed"
                                                        }
                                                        loadingResource = false
                                                        loading = false
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 14.dp),
                                        )
                                        HorizontalDivider()
                                    }
                                }
                                Button(onClick = { discover() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                                    Text("Find SFZ packs")
                                }
                                Button(onClick = {
                                    context.startActivity(Intent(context, SfzFoldersActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
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
