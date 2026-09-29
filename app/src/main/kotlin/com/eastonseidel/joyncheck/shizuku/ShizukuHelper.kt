package com.eastonseidel.joyncheck.shizuku

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    RUNNING_NO_PERMISSION,
    RUNNING_DENIED,
    READY,
}

/** Thin wrapper around Shizuku's static API; same states JoynCon itself reports. */
object ShizukuHelper {

    private const val REQUEST_CODE = 9001
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private lateinit var appContext: Context

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refreshState() }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE) refreshState()
    }

    private var listenersAdded = false

    fun init(context: Context) {
        appContext = context.applicationContext
        if (listenersAdded) return
        listenersAdded = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshState()
    }

    fun refreshState() {
        _state.value = when {
            !Shizuku.pingBinder() -> if (isInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
            Shizuku.isPreV11() -> ShizukuState.RUNNING_NO_PERMISSION // pre-v11 EventBus flow not supported
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.RUNNING_DENIED
            else -> ShizukuState.RUNNING_NO_PERMISSION
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Needs the `<queries>` entry in AndroidManifest.xml to see the package on Android 11+. */
    fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrElse { it !is PackageManager.NameNotFoundException }

    /** Opens Shizuku's Play Store listing, falling back to the web page if no store app is present. */
    fun openPlayStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openShizukuApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
