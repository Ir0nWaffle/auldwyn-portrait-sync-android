package com.auldwyn.portraitsync

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Thin wrapper around the Shizuku API: checks whether Shizuku is running,
 * requests its permission, and binds [FileUserService] so privileged file
 * writes can go through it. */
object ShizukuFileAccess {
    private const val PERMISSION_REQUEST_CODE = 5219

    private var service: IFileService? = null
    private var connection: ServiceConnection? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.auldwyn.portraitsync", FileUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("fileservice")
        .debuggable(false)
        .version(1)

    val isRunning: Boolean
        get() = Shizuku.pingBinder()

    val isGranted: Boolean
        get() = isRunning && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (!isRunning) {
            onResult(false)
            return
        }
        if (isGranted) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != PERMISSION_REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    suspend fun bind(): IFileService {
        service?.let { return it }
        return suspendCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val bound = IFileService.Stub.asInterface(binder)
                    service = bound
                    cont.resume(bound)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            connection = conn
            Shizuku.bindUserService(userServiceArgs, conn)
        }
    }
}
