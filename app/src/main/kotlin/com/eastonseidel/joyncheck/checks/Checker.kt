package com.eastonseidel.joyncheck.checks

import android.content.Context
import com.eastonseidel.joyncheck.bridge.BridgeClient
import com.eastonseidel.joyncheck.shizuku.ShizukuHelper
import com.eastonseidel.joyncheck.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class CheckStatus { PENDING, RUNNING, PASS, WARN, FAIL, SKIPPED }

data class CheckResult(val status: CheckStatus = CheckStatus.PENDING, val detail: String = "")

/** One-shot probes, in the order they run. */
enum class SystemCheck(val title: String) {
    BRIDGE("Shizuku helper starts"),
    EVDEV("Can read input devices"),
    UINPUT("Can create a virtual gamepad"),
    UINPUT_SEEN("Android accepts the virtual gamepad"),
    JOYCON_L("Joy-Con (L) can be taken over"),
    JOYCON_R("Joy-Con (R) can be taken over"),
}

/** Live test: the user presses/moves things and each is confirmed end to end. */
enum class LiveCheck(val title: String) {
    L_BUTTON("Joy-Con (L): press any button"),
    L_STICK("Joy-Con (L): move the stick"),
    R_BUTTON("Joy-Con (R): press any button"),
    R_STICK("Joy-Con (R): move the stick"),
    DELIVERED_BUTTON("Merged buttons reach apps"),
    DELIVERED_STICK("Merged sticks reach apps"),
}

enum class Verdict { SETUP_NEEDED, NOT_TESTED, IN_PROGRESS, COMPATIBLE, BUTTONS_ONLY, INCOMPLETE, INCOMPATIBLE }

data class CheckState(
    val systemRunning: Boolean = false,
    val system: Map<SystemCheck, CheckResult> = SystemCheck.entries.associateWith { CheckResult() },
    val liveRunning: Boolean = false,
    val liveFinished: Boolean = false,
    val live: Map<LiveCheck, CheckResult> = LiveCheck.entries.associateWith { CheckResult() },
    val liveSecondsLeft: Int = 0,
) {
    val systemDone get() = system.values.none { it.status == CheckStatus.PENDING || it.status == CheckStatus.RUNNING }
    val canRunLive get() = !systemRunning && !liveRunning &&
        listOf(SystemCheck.UINPUT, SystemCheck.JOYCON_L, SystemCheck.JOYCON_R).all { system[it]?.status == CheckStatus.PASS }
}

/**
 * Runs JoynCheck's probes. Each one exercises the exact operation JoynCon's merge depends on, so
 * the verdict maps directly onto what JoynCon can do on this phone:
 *  - full merge (buttons + sticks) needs every system check and the live test to pass;
 *  - if raw input or uinput access is blocked, JoynCon falls back to its buttons-only
 *    Accessibility path, which only needs Shizuku.
 */
object Checker {

    private const val BRIDGE_TIMEOUT_MS = 15_000L
    private const val LIVE_TEST_SECONDS = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CheckState())
    val state: StateFlow<CheckState> = _state

    private var systemJob: Job? = null
    private var liveJob: Job? = null

    fun runSystemChecks(context: Context) {
        if (systemJob?.isActive == true) return
        stopLiveTest()
        _state.value = CheckState(systemRunning = true)
        systemJob = scope.launch {
            try {
                runSystemChecksInner(context.applicationContext)
            } finally {
                _state.update { s ->
                    // Anything still unresolved (e.g. the job was cancelled) is marked skipped.
                    s.copy(
                        systemRunning = false,
                        system = s.system.mapValues { (_, r) ->
                            if (r.status == CheckStatus.PENDING || r.status == CheckStatus.RUNNING) {
                                CheckResult(CheckStatus.SKIPPED, "Not run")
                            } else r
                        },
                    )
                }
            }
        }
    }

    private suspend fun runSystemChecksInner(context: Context) {
        set(SystemCheck.BRIDGE, CheckStatus.RUNNING)
        val bridge = BridgeClient.connect(context, BRIDGE_TIMEOUT_MS)
        if (bridge == null) {
            set(
                SystemCheck.BRIDGE, CheckStatus.FAIL,
                "Shizuku didn't start the helper within ${BRIDGE_TIMEOUT_MS / 1000}s. Restart Shizuku and try again.",
            )
            skipRest(SystemCheck.BRIDGE, "Needs the Shizuku helper")
            return
        }
        val joynConRunning = runCatching { bridge.isJoynConBridgeRunning() }.getOrDefault(false)
        set(
            SystemCheck.BRIDGE, CheckStatus.PASS,
            if (joynConRunning) "Running. Note: JoynCon's own merge is also running and may hold the Joy-Cons — stop it for an accurate result."
            else "Running as the shell user",
        )

        set(SystemCheck.EVDEV, CheckStatus.RUNNING)
        val evdev = JSONObject(bridge.probeEvdev())
        val opened = evdev.optInt("opened")
        val total = evdev.optInt("total")
        when {
            evdev.optString("error").isNotEmpty() -> set(SystemCheck.EVDEV, CheckStatus.FAIL, evdev.optString("error"))
            opened > 0 -> set(SystemCheck.EVDEV, CheckStatus.PASS, "Opened $opened of $total input devices")
            else -> set(
                SystemCheck.EVDEV, CheckStatus.FAIL,
                "This phone's security policy blocks raw input access (${evdev.optInt("denied")} of $total denied)",
            )
        }

        set(SystemCheck.UINPUT, CheckStatus.RUNNING)
        val uinputError = bridge.createTestDevice()
        if (uinputError.isEmpty()) {
            set(SystemCheck.UINPUT, CheckStatus.PASS, "Created a test gamepad")
            set(SystemCheck.UINPUT_SEEN, CheckStatus.RUNNING)
            var seen = false
            repeat(30) {
                if (!seen) {
                    seen = JoyConPresence.testDeviceVisible()
                    if (!seen) delay(100)
                }
            }
            bridge.destroyTestDevice()
            if (seen) set(SystemCheck.UINPUT_SEEN, CheckStatus.PASS, "Registered as an input device")
            else set(SystemCheck.UINPUT_SEEN, CheckStatus.FAIL, "Android never registered the test gamepad")
        } else {
            set(SystemCheck.UINPUT, CheckStatus.FAIL, uinputError)
            set(SystemCheck.UINPUT_SEEN, CheckStatus.SKIPPED, "Needs a virtual gamepad")
        }

        JoyConPresence.rescan()
        probeJoyCon(bridge, SystemCheck.JOYCON_L, "left", JoyConPresence.state.value.left, joynConRunning)
        probeJoyCon(bridge, SystemCheck.JOYCON_R, "right", JoyConPresence.state.value.right, joynConRunning)
    }

    private fun probeJoyCon(
        bridge: com.eastonseidel.joyncheck.bridge.ICheckBridge,
        check: SystemCheck,
        role: String,
        androidSeesIt: Boolean,
        joynConRunning: Boolean,
    ) {
        set(check, CheckStatus.RUNNING)
        val r = JSONObject(bridge.probeJoyCon(role))
        val name = r.optString("name")
        when {
            r.optBoolean("found") && r.optBoolean("grabOk") -> set(
                check, CheckStatus.PASS,
                "$name — ${r.optInt("keyCount")} buttons" + if (r.optBoolean("hasStick")) ", analog stick" else ", no analog stick reported",
            )
            r.optBoolean("found") -> set(
                check, CheckStatus.FAIL,
                r.optString("grabError") + if (joynConRunning) " — stop JoynCon's merge service and run again" else "",
            )
            !androidSeesIt -> set(check, CheckStatus.SKIPPED, "Not connected — pair it in Bluetooth settings and run again")
            r.optInt("denied") > 0 || r.optInt("ioctlDenied") > 0 || r.optString("dirError").isNotEmpty() -> set(
                check, CheckStatus.FAIL, "Connected, but this phone's security policy blocks direct access to it",
            )
            else -> set(check, CheckStatus.FAIL, "Connected in Android, but not found as a raw input device")
        }
    }

    fun startLiveTest(context: Context) {
        if (!_state.value.canRunLive) return
        liveJob = scope.launch {
            val bridge = BridgeClient.connect(context.applicationContext, BRIDGE_TIMEOUT_MS) ?: run {
                _state.update { s ->
                    s.copy(live = s.live.mapValues { CheckResult(CheckStatus.FAIL, "Shizuku helper not running") }, liveFinished = true)
                }
                return@launch
            }
            _state.update { s ->
                s.copy(
                    liveRunning = true,
                    liveFinished = false,
                    liveSecondsLeft = LIVE_TEST_SECONDS,
                    live = LiveCheck.entries.associateWith { CheckResult(CheckStatus.RUNNING, "Waiting…") },
                )
            }
            try {
                val start = JSONObject(bridge.startLiveTest())
                val uinputError = start.optString("uinputError")
                val leftError = start.optString("leftError")
                val rightError = start.optString("rightError")
                if (uinputError.isNotEmpty()) {
                    _state.update { s -> s.copy(live = s.live.mapValues { CheckResult(CheckStatus.FAIL, uinputError) }) }
                    return@launch
                }
                if (leftError.isNotEmpty()) {
                    setLive(LiveCheck.L_BUTTON, CheckStatus.FAIL, leftError)
                    setLive(LiveCheck.L_STICK, CheckStatus.FAIL, leftError)
                }
                if (rightError.isNotEmpty()) {
                    setLive(LiveCheck.R_BUTTON, CheckStatus.FAIL, rightError)
                    setLive(LiveCheck.R_STICK, CheckStatus.FAIL, rightError)
                }

                val deadline = System.currentTimeMillis() + LIVE_TEST_SECONDS * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val poll = JSONObject(bridge.pollLiveTest())
                    if (poll.optBoolean("leftButton")) setLive(LiveCheck.L_BUTTON, CheckStatus.PASS, "Detected")
                    if (poll.optBoolean("leftStick")) setLive(LiveCheck.L_STICK, CheckStatus.PASS, "Detected")
                    if (poll.optBoolean("rightButton")) setLive(LiveCheck.R_BUTTON, CheckStatus.PASS, "Detected")
                    if (poll.optBoolean("rightStick")) setLive(LiveCheck.R_STICK, CheckStatus.PASS, "Detected")
                    val live = _state.value.live
                    if (live.values.none { it.status == CheckStatus.RUNNING }) break
                    val left = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                    _state.update { it.copy(liveSecondsLeft = left) }
                    delay(150)
                }
            } finally {
                runCatching { bridge.stopLiveTest() }
                _state.update { s ->
                    s.copy(
                        liveRunning = false,
                        liveFinished = true,
                        liveSecondsLeft = 0,
                        live = s.live.mapValues { (check, r) ->
                            if (r.status == CheckStatus.RUNNING) CheckResult(CheckStatus.FAIL, notDetectedDetail(check)) else r
                        },
                    )
                }
            }
        }
    }

    private fun notDetectedDetail(check: LiveCheck) = when (check) {
        LiveCheck.DELIVERED_BUTTON, LiveCheck.DELIVERED_STICK ->
            "Input was read but never arrived back through Android — try again, and keep this app open during the test"
        else -> "Not detected before the test ended"
    }

    fun stopLiveTest() {
        liveJob?.cancel()
        liveJob = null
        // The finally block above stops the bridge side; also cover a job that never got that far.
        BridgeClient.current()?.let { b -> scope.launch { runCatching { b.stopLiveTest() } } }
    }

    /** Called by the activity for key events arriving from the test gamepad. */
    fun onVirtualButton() {
        if (_state.value.liveRunning) setLive(LiveCheck.DELIVERED_BUTTON, CheckStatus.PASS, "Received by this app")
    }

    /** Called by the activity for joystick motion arriving from the test gamepad. */
    fun onVirtualStick() {
        if (_state.value.liveRunning) setLive(LiveCheck.DELIVERED_STICK, CheckStatus.PASS, "Received by this app")
    }

    fun verdict(state: CheckState, shizuku: ShizukuState, presence: Presence): Verdict {
        if (state.systemRunning || state.liveRunning) return Verdict.IN_PROGRESS
        if (shizuku != ShizukuState.READY) return Verdict.SETUP_NEEDED
        if (!state.systemDone) return Verdict.NOT_TESTED

        val sys = state.system.mapValues { it.value.status }
        if (sys[SystemCheck.BRIDGE] == CheckStatus.FAIL) return Verdict.INCOMPATIBLE
        val rawPathBlocked = listOf(SystemCheck.EVDEV, SystemCheck.UINPUT, SystemCheck.UINPUT_SEEN, SystemCheck.JOYCON_L, SystemCheck.JOYCON_R)
            .any { sys[it] == CheckStatus.FAIL }
        if (rawPathBlocked) return Verdict.BUTTONS_ONLY
        if (!presence.left || !presence.right ||
            sys[SystemCheck.JOYCON_L] == CheckStatus.SKIPPED || sys[SystemCheck.JOYCON_R] == CheckStatus.SKIPPED
        ) return Verdict.SETUP_NEEDED
        if (!state.liveFinished) return Verdict.NOT_TESTED
        if (state.live.values.all { it.status == CheckStatus.PASS }) return Verdict.COMPATIBLE
        return Verdict.INCOMPLETE
    }

    private fun set(check: SystemCheck, status: CheckStatus, detail: String = "") {
        _state.update { s -> s.copy(system = s.system + (check to CheckResult(status, detail))) }
    }

    private fun setLive(check: LiveCheck, status: CheckStatus, detail: String) {
        _state.update { s ->
            if (s.live[check]?.status == status) s else s.copy(live = s.live + (check to CheckResult(status, detail)))
        }
    }

    private fun skipRest(after: SystemCheck, reason: String) {
        for (check in SystemCheck.entries.filter { it.ordinal > after.ordinal }) set(check, CheckStatus.SKIPPED, reason)
    }
}
