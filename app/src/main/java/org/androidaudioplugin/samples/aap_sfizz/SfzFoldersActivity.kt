package org.androidaudioplugin.samples.aap_sfizz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Own activity result owner, also usable when the plugin UI is hosted by a service. */
class SfzFoldersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val revision by SfzFolders.revision.collectAsState()
            val roots = remember(revision) { SfzFolders.roots(this) }
            var error by remember { mutableStateOf<String?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) runCatching { SfzFolders.add(this, uri) }
                    .onFailure { error = it.message }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("SFZ folders", style = MaterialTheme.typography.headlineSmall)
                        Text("Choose a folder containing your SFZ files and samples. Subfolders are searched too.")
                        Button(onClick = { picker.launch(null) }) { Text("Add folder") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        LazyColumn(Modifier.weight(1f)) {
                            items(roots, key = { it.toString() }) { uri ->
                                Column {
                                    Text(android.provider.DocumentsContract.getTreeDocumentId(uri))
                                    TextButton(onClick = { SfzFolders.remove(this@SfzFoldersActivity, uri) }) { Text("Remove folder") }
                                }
                            }
                        }
                        Button(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}
