package com.auldwyn.portraitsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

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

    val requestAllFilesAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasAllFilesAccess()) {
            useNwnFolder()
        } else {
            log = log + "All files access was not granted, so the NWN:EE folder is still off-limits."
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
                "folder on Android 11+. Use \"NWN:EE Folder\" below instead - it writes " +
                "there directly, after a one-time \"Allow all files access\" grant.",
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row {
            Button(onClick = { pickFolder.launch(null) }) {
                Text("Choose Folder...")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (hasAllFilesAccess()) {
                    useNwnFolder()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    requestAllFilesAccess.launch(intent)
                }
            }) {
                Text("NWN:EE Folder")
            }
        }

        Row {
            Button(
                enabled = !syncing,
                onClick = {
                    val destination = when {
                        destPath != null -> SyncDestination.Direct(destPath!!)
                        destUri != null -> SyncDestination.Tree(destUri!!)
                        else -> null
                    }
                    if (destination == null) {
                        log = log + "Please choose a destination folder first."
                        return@Button
                    }
                    syncing = true
                    scope.launch {
                        try {
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
