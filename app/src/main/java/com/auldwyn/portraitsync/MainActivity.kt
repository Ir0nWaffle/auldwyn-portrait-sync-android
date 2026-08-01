package com.auldwyn.portraitsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import java.io.File

private const val PREFS_NAME = "portrait_sync"
private const val PREF_DEST_URI = "dest_uri"
private const val PREF_DEST_PATH = "dest_path"
private const val NWN_PORTRAITS_RELATIVE_PATH =
    "Android/data/com.beamdog.nwnandroid/files/user/portraits"

private fun nwnPortraitsDir(): File =
    File(Environment.getExternalStorageDirectory(), NWN_PORTRAITS_RELATIVE_PATH)

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
    var destPath by remember {
        mutableStateOf(prefs.getString(PREF_DEST_PATH, null)?.let { File(it) })
    }
    var log by remember { mutableStateOf(listOf<String>()) }
    var syncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun useNwnFolder() {
        val dir = nwnPortraitsDir()
        destPath = dir
        destUri = null
        prefs.edit()
            .putString(PREF_DEST_PATH, dir.absolutePath)
            .remove(PREF_DEST_URI)
            .apply()
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destUri = uri
            destPath = null
            prefs.edit()
                .putString(PREF_DEST_URI, uri.toString())
                .remove(PREF_DEST_PATH)
                .apply()
        }
    }

    val destLabel = when {
        destPath != null -> destPath!!.absolutePath
        destUri != null -> DocumentFile.fromTreeUri(context, destUri!!)?.name ?: destUri.toString()
        else -> "Not set"
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Destination folder:")
        Text(destLabel, modifier = Modifier.padding(vertical = 4.dp))
        Text(
            "The system folder picker can't navigate into NWN:EE's own Android/data " +
                "folder on Android 11+, and neither can All Files Access - that's an OS-level " +
                "block on every app but NWN:EE itself. \"NWN:EE Folder\" below uses Shizuku " +
                "instead: install the Shizuku app, start it once (wireless debugging pairing, " +
                "no root), then tap this button.",
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row {
            Button(onClick = { pickFolder.launch(null) }) {
                Text("Choose Folder...")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (!ShizukuFileAccess.isRunning) {
                    log = log + "Shizuku isn't running. Install and start the Shizuku app " +
                        "(pair it via wireless debugging in Developer options), then try again."
                    return@Button
                }
                ShizukuFileAccess.requestPermission { granted ->
                    if (granted) {
                        useNwnFolder()
                        log = log + "Shizuku access granted - destination set to the NWN:EE portraits folder."
                    } else {
                        log = log + "Shizuku permission was not granted."
                    }
                }
            }) {
                Text("NWN:EE Folder")
            }
        }

        Row {
            Button(
                enabled = !syncing,
                onClick = {
                    if (destPath == null && destUri == null) {
                        log = log + "Please choose a destination folder first."
                        return@Button
                    }
                    syncing = true
                    scope.launch {
                        try {
                            val destination = if (destPath != null) {
                                SyncDestination.ShizukuDirect(destPath!!, ShizukuFileAccess.bind())
                            } else {
                                SyncDestination.Tree(destUri!!)
                            }
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
