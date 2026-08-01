package com.auldwyn.portraitsync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive as RarArchive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Where extracted portraits get written. [Tree] goes through the SAF folder picker;
 * [Direct] writes straight to a filesystem path (used for the NWN:EE Android/data
 * folder, which the SAF picker refuses to navigate into on Android 11+). */
sealed class SyncDestination {
    data class Tree(val uri: Uri) : SyncDestination()
    data class Direct(val dir: File) : SyncDestination()
}

private interface DestinationWriter {
    fun readExisting(filename: String): ByteArray?
    fun write(filename: String, data: ByteArray)
}

private class TreeWriter(private val context: Context, uri: Uri) : DestinationWriter {
    private val dir = DocumentFile.fromTreeUri(context, uri)
        ?: throw IOException("Could not open destination folder")

    override fun readExisting(filename: String): ByteArray? {
        val file = dir.findFile(filename) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    override fun write(filename: String, data: ByteArray) {
        dir.findFile(filename)?.delete()
        val newFile = dir.createFile("application/octet-stream", filename)
            ?: throw IOException("Could not create file $filename")
        context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(data) }
    }
}

private class DirectWriter(private val dir: File) : DestinationWriter {
    init {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create destination folder: ${dir.absolutePath}")
        }
    }

    override fun readExisting(filename: String): ByteArray? {
        val file = File(dir, filename)
        return if (file.exists()) file.readBytes() else null
    }

    override fun write(filename: String, data: ByteArray) {
        File(dir, filename).writeBytes(data)
    }
}

object PortraitSync {

    private const val DROPBOX_SHARE_LINK =
        "https://www.dropbox.com/scl/fo/kdzc0x3xplbj3srgv1370/" +
            "ADjYBXy_RIGPf9VXvEsS9nY?rlkey=rg7vr8bngppst5klsz0qiinfo" +
            "&st=nco1gyvm&dl=0"

    private val client = OkHttpClient()

    private fun toZipDownloadUrl(link: String): String = when {
        link.contains("dl=1") -> link
        link.contains("dl=0") -> link.replace("dl=0", "dl=1")
        link.contains("?") -> "$link&dl=1"
        else -> "$link?dl=1"
    }

    /** Downloads the Dropbox folder and extracts every .tga file found anywhere
     * inside it (including nested .zip/.7z/.rar archives) into [destination]. */
    fun sync(context: Context, destination: SyncDestination, log: (String) -> Unit): Int {
        val destDir: DestinationWriter = when (destination) {
            is SyncDestination.Tree -> TreeWriter(context, destination.uri)
            is SyncDestination.Direct -> DirectWriter(destination.dir)
        }

        val url = toZipDownloadUrl(DROPBOX_SHARE_LINK)
        log("Downloading from Dropbox...")

        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("Empty response body")
            log("Download complete (${bytes.size / 1_048_576} MB). Extracting .tga files " +
                "(including nested .zip/.7z/.rar archives)...")

            var copied = 0
            var skipped = 0

            fun saveEntry(name: String, data: ByteArray) {
                val filename = name.substringAfterLast('/').substringAfterLast('\\')
                val existingBytes = destDir.readExisting(filename)
                if (existingBytes != null && existingBytes.contentEquals(data)) {
                    skipped++
                    return
                }
                destDir.write(filename, data)
                copied++
                log("Saved: $filename")
            }

            fun handleArchive(data: ByteArray, nameHint: String, depth: Int) {
                fun processEntry(name: String, entryData: ByteArray) {
                    val lower = name.lowercase()
                    when {
                        lower.endsWith(".tga") -> saveEntry(name, entryData)
                        depth < 5 && (lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")) -> {
                            log("Opening nested archive: ${name.substringAfterLast('/')}")
                            try {
                                handleArchive(entryData, name, depth + 1)
                            } catch (e: Exception) {
                                log("Warning: could not open nested archive $name (${e.message})")
                            }
                        }
                    }
                }

                val lower = nameHint.lowercase()
                when {
                    lower.endsWith(".zip") -> {
                        ZipInputStream(ByteArrayInputStream(data)).use { zis ->
                            while (true) {
                                val entry = zis.nextEntry ?: break
                                if (!entry.isDirectory) processEntry(entry.name, zis.readBytes())
                                zis.closeEntry()
                            }
                        }
                    }

                    lower.endsWith(".7z") -> {
                        val tmp = File.createTempFile("sevenzip", ".7z", context.cacheDir)
                        try {
                            tmp.writeBytes(data)
                            SevenZFile(tmp).use { sevenZ ->
                                var entry = sevenZ.nextEntry
                                while (entry != null) {
                                    if (!entry.isDirectory) {
                                        val out = ByteArrayOutputStream()
                                        val buf = ByteArray(8192)
                                        while (true) {
                                            val n = sevenZ.read(buf)
                                            if (n < 0) break
                                            out.write(buf, 0, n)
                                        }
                                        processEntry(entry.name, out.toByteArray())
                                    }
                                    entry = sevenZ.nextEntry
                                }
                            }
                        } finally {
                            tmp.delete()
                        }
                    }

                    lower.endsWith(".rar") -> {
                        val tmp = File.createTempFile("rarfile", ".rar", context.cacheDir)
                        try {
                            tmp.writeBytes(data)
                            RarArchive(tmp).use { archive ->
                                var header = archive.nextFileHeader()
                                while (header != null) {
                                    if (!header.isDirectory) {
                                        val out = ByteArrayOutputStream()
                                        archive.extractFile(header, out)
                                        processEntry(header.fileNameString.trim(), out.toByteArray())
                                    }
                                    header = archive.nextFileHeader()
                                }
                            }
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            }

            handleArchive(bytes, "dropbox_folder.zip", 0)

            log("Done. $copied file(s) copied/updated, $skipped already up to date.")
            return copied
        }
    }
}
