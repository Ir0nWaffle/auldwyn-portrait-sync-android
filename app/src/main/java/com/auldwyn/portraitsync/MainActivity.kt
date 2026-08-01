package com.auldwyn.portraitsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "portrait_sync"
private const val PREF_DEST_URI = "dest_uri"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var destUri by remember {
        mutableStateOf(prefs.getString(PREF_DEST_URI, null)?.let { Uri.parse(it) })
    }
    var log by remember { mutableStateOf(listOf<String>()) }
    var syncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destUri = uri
            prefs.edit().putString(PREF_DEST_URI, uri.toString()).apply()
        }
    }

    val destLabel = destUri?.let { uri ->
        DocumentFile.fromTreeUri(context, uri)?.name ?: uri.toString()
    } ?: "Download/AuldwynPortraits (default, no setup needed)"

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Destination folder:")
        Text(destLabel, modifier = Modifier.padding(vertical = 4.dp))
        Text(
            "Tap Sync Now - no setup required. Portraits land in a plain " +
                "Download/AuldwynPortraits folder on your phone. Android won't let any app " +
                "(this one included) write straight into NWN:EE's own folder, so the last " +
                "step - moving those files into Android/data/com.beamdog.nwnandroid/files/" +
                "user/portraits - is a one-time manual copy via a computer (see the README) " +
                "or a file manager that can reach Android/data.",
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row {
            Button(onClick = { pickFolder.launch(null) }) {
                Text("Choose Folder Instead...")
            }
        }

        Row {
            Button(
                enabled = !syncing,
                onClick = {
                    syncing = true
                    scope.launch {
                        try {
                            val destination = destUri?.let { SyncDestination.Tree(it) }
                                ?: SyncDestination.PublicDownloads
                            withContext(Dispatchers.IO) {
                                PortraitSync.sync(context, destination) { msg ->
                                    log = log + msg
                                }
                            }
                        } catch (e: Exception) {
                            log = log + "Error: ${e.message}"
                        } finally {
                            syncing = false
                        }
                    }
                }
            ) {
                Text(if (syncing) "Syncing..." else "Sync Now")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Log:")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            log.forEach { line -> Text(line) }
        }
    }
}
