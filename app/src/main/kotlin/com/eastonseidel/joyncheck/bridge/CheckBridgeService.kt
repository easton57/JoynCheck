package com.eastonseidel.joyncheck.bridge

import android.os.Process
import android.util.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku user-service process as UID 2000 (shell) — the same identity JoynCon's
 * own bridge runs as, which is the whole point: every probe gets exactly the access JoynCon would.
 *
 * Instantiated by Shizuku via reflection, so it must keep this public no-arg constructor.
 */
class CheckBridgeService : ICheckBridge.Stub() {

    init {
        killStaleSiblings()
        System.loadLibrary("joyncheck_native")
    }

    override fun probeEvdev(): String = guarded("{}") { nativeProbeEvdev() }

    override fun createTestDevice(): String = guarded("createTestDevice threw") { nativeCreateTestDevice() }

    override fun destroyTestDevice() {
        guarded(Unit) { nativeDestroyTestDevice() }
    }

    override fun probeJoyCon(role: String): String = guarded("{}") { nativeProbeJoyCon(role) }

    override fun startLiveTest(): String = guarded("{\"uinputError\":\"startLiveTest threw\"}") { nativeStartLiveTest() }

    override fun pollLiveTest(): String = guarded("{}") { nativePollLiveTest() }

    override fun stopLiveTest() {
        guarded(Unit) { nativeStopLiveTest() }
    }

    override fun isJoynConBridgeRunning(): Boolean = findProcesses(JOYNCON_BRIDGE_PROCESS).isNotEmpty()

    override fun destroy() {
        guarded(Unit) { nativeStopLiveTest() }
        exitProcess(0)
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = runCatching(block).getOrElse {
        Log.e(TAG, "bridge call failed", it)
        fallback
    }

    /** A leftover checker bridge from a killed app process could still hold Joy-Con grabs. */
    private fun killStaleSiblings() {
        for (pid in findProcesses(OWN_BRIDGE_PROCESS)) {
            if (pid == Process.myPid()) continue
            Log.w(TAG, "Killing stale bridge sibling process $pid")
            runCatching { Process.killProcess(pid) }
        }
    }

    private fun findProcesses(cmdline: String): List<Int> {
        val pidDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null } ?: return emptyList()
        return pidDirs.mapNotNull { dir ->
            val cmd = runCatching {
                File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).trim('\u0000')
            }.getOrNull()
            if (cmd == cmdline) dir.name.toInt() else null
        }
    }

    private external fun nativeProbeEvdev(): String
    private external fun nativeCreateTestDevice(): String
    private external fun nativeDestroyTestDevice()
    private external fun nativeProbeJoyCon(role: String): String
    private external fun nativeStartLiveTest(): String
    private external fun nativePollLiveTest(): String
    private external fun nativeStopLiveTest()

    companion object {
        private const val TAG = "CheckBridgeService"
        private const val OWN_BRIDGE_PROCESS = "com.eastonseidel.joyncheck:bridge"
        private const val JOYNCON_BRIDGE_PROCESS = "com.eastonseidel.joyncon:bridge"
    }
}
