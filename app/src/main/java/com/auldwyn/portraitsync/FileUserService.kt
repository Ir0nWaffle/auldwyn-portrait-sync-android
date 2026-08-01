package com.auldwyn.portraitsync

import java.io.File

/** Runs in a separate process spawned by Shizuku with shell-level privileges,
 * which (unlike a normal app process) can read/write other apps' Android/data
 * folders directly - the FUSE-based scoped storage restriction that blocks
 * this from a regular app process doesn't apply to the shell UID. */
class FileUserService : IFileService.Stub() {
    override fun mkdirs(path: String): Boolean {
        val file = File(path)
        return file.exists() || file.mkdirs()
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun readFile(path: String): ByteArray = File(path).readBytes()

    override fun writeFile(path: String, data: ByteArray): Boolean = try {
        File(path).writeBytes(data)
        true
    } catch (e: Exception) {
        false
    }

    override fun destroy() {
        System.exit(0)
    }
}
