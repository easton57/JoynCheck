package com.eastonseidel.joyncheck.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.eastonseidel.joyncheck.shizuku.ShizukuHelper
import com.eastonseidel.joyncheck.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Owns the Shizuku user-service connection to [CheckBridgeService]. */
object BridgeClient {

    private val bridge = MutableStateFlow<ICheckBridge?>(null)
    private var bindRequested = false
    private var args: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bridge.value = ICheckBridge.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bridge.value = null
            bindRequested = false
        }
    }

    private fun argsFor(context: Context) = args ?: Shizuku.UserServiceArgs(
        ComponentName(context.packageName, CheckBridgeService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("bridge")
        .debuggable(false)
        .version(1)
        .also { args = it }

    /**
     * Starts (or reuses) the bridge and waits for it. Only ever issues one bind at a time — a
     * repeated bindUserService with the same connection can spawn a second bridge process.
     */
    suspend fun connect(context: Context, timeoutMs: Long): ICheckBridge? {
        bridge.value?.let { if (it.asBinder().pingBinder()) return it }
        if (ShizukuHelper.state.value != ShizukuState.READY) return null
        withContext(Dispatchers.Main) {
            if (!bindRequested) {
                bindRequested = true
                Shizuku.bindUserService(argsFor(context), connection)
            }
        }
        return withTimeoutOrNull(timeoutMs) { bridge.filterNotNull().first() }
    }

    fun current(): ICheckBridge? = bridge.value

    fun unbind() {
        val a = args ?: return
        runCatching { Shizuku.unbindUserService(a, connection, true) }
        bridge.value = null
        bindRequested = false
    }
}
