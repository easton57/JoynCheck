package com.eastonseidel.joyncheck.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eastonseidel.joyncheck.checks.CheckResult
import com.eastonseidel.joyncheck.checks.CheckStatus
import com.eastonseidel.joyncheck.checks.Checker
import com.eastonseidel.joyncheck.checks.JoyConPresence
import com.eastonseidel.joyncheck.checks.Verdict
import com.eastonseidel.joyncheck.shizuku.ShizukuHelper
import com.eastonseidel.joyncheck.shizuku.ShizukuState

private val COLOR_OK = Color(0xFF00C98C)
private val COLOR_WARN = Color(0xFFFFB300)
private val COLOR_FAIL = Color(0xFFE5484D)
private val COLOR_OFF = Color(0xFF888888)

@Composable
fun CheckScreen() {
    val context = LocalContext.current
    val shizuku by ShizukuHelper.state.collectAsState()
    val presence by JoyConPresence.state.collectAsState()
    val state by Checker.state.collectAsState()
    val verdict = Checker.verdict(state, shizuku, presence)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("JoynCheck", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Free compatibility check for JoynCon. It runs the same steps JoynCon uses to merge two " +
                    "Joy-Cons, so you know whether JoynCon will work on this phone before you buy it. " +
                    "Nothing leaves your device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            VerdictCard(verdict)

            Section("1. Shizuku") {
                val running = shizuku != ShizukuState.NOT_INSTALLED && shizuku != ShizukuState.NOT_RUNNING
                StatusRow(if (shizuku != ShizukuState.NOT_INSTALLED) COLOR_OK else COLOR_OFF, "Installed", if (shizuku == ShizukuState.NOT_INSTALLED) "No" else "Yes")
                StatusRow(if (running) COLOR_OK else COLOR_OFF, "Running", if (running) "Yes" else "No")
                StatusRow(
                    when (shizuku) {
                        ShizukuState.READY -> COLOR_OK
                        ShizukuState.RUNNING_DENIED -> COLOR_FAIL
                        else -> COLOR_OFF
                    },
                    "Permission for JoynCheck",
                    when (shizuku) {
                        ShizukuState.READY -> "Granted"
                        ShizukuState.RUNNING_DENIED -> "Denied — allow it in the Shizuku app"
                        else -> "Not granted"
                    },
                )
                when (shizuku) {
                    ShizukuState.NOT_INSTALLED -> Button(onClick = { ShizukuHelper.openPlayStore(context) }) { Text("Get Shizuku on Google Play") }
                    ShizukuState.NOT_RUNNING -> {
                        Text(
                            "Open Shizuku and start it with Wireless debugging (Android 11+). It has to be started again after every reboot.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { ShizukuHelper.openShizukuApp(context) }) { Text("Open Shizuku") }
                    }
                    ShizukuState.RUNNING_NO_PERMISSION, ShizukuState.RUNNING_DENIED ->
                        Button(onClick = { ShizukuHelper.requestPermission() }) { Text("Grant permission") }
                    ShizukuState.READY -> {}
                }
            }

            Section("2. Joy-Cons") {
                StatusRow(if (presence.left) COLOR_OK else COLOR_OFF, "Joy-Con (L)", if (presence.left) "Connected" else "Not connected")
                StatusRow(if (presence.right) COLOR_OK else COLOR_OFF, "Joy-Con (R)", if (presence.right) "Connected" else "Not connected")
                if (!presence.left || !presence.right) {
                    Text(
                        "Pair each Joy-Con: hold the small sync button on its rail until the lights run, then pick it in Bluetooth settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }) { Text("Open Bluetooth settings") }
                }
            }

            Section("3. System checks") {
                for ((check, result) in state.system) ResultRow(check.title, result)
                Button(
                    onClick = { Checker.runSystemChecks(context) },
                    enabled = shizuku == ShizukuState.READY && !state.systemRunning && !state.liveRunning,
                ) { Text(if (state.systemDone) "Run again" else "Run checks") }
                if (shizuku != ShizukuState.READY) {
                    Text("Finish the Shizuku step first.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("4. Live test") {
                Text(
                    "Briefly takes over both Joy-Cons, merges them into a test gamepad, and confirms that input comes " +
                        "back through Android the way it would reach a game. Keep this screen open while testing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for ((check, result) in state.live) ResultRow(check.title, result)
                if (state.liveRunning) {
                    Text("${state.liveSecondsLeft}s left", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { Checker.stopLiveTest() }) { Text("Stop") }
                } else {
                    Button(onClick = { Checker.startLiveTest(context) }, enabled = state.canRunLive) {
                        Text(if (state.liveFinished) "Test again" else "Start live test")
                    }
                    if (!state.canRunLive && !state.systemRunning) {
                        Text("Available once the system checks pass with both Joy-Cons connected.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(verdict: Verdict) {
    val (color, title, body) = when (verdict) {
        Verdict.SETUP_NEEDED -> Triple(COLOR_OFF, "Setup needed", "Set up Shizuku and connect both Joy-Cons, then run the checks below.")
        Verdict.NOT_TESTED -> Triple(COLOR_OFF, "Not tested yet", "Run the system checks, then the live test.")
        Verdict.IN_PROGRESS -> Triple(COLOR_WARN, "Testing…", "Follow the prompts below.")
        Verdict.COMPATIBLE -> Triple(COLOR_OK, "Compatible", "JoynCon can fully merge your Joy-Cons on this phone: buttons and both sticks.")
        Verdict.BUTTONS_ONLY -> Triple(
            COLOR_WARN, "Buttons only",
            "This phone blocks the direct input access JoynCon needs to merge the analog sticks. JoynCon could only merge buttons here (through its Accessibility fallback).",
        )
        Verdict.INCOMPLETE -> Triple(
            COLOR_WARN, "Live test incomplete",
            "Some inputs weren't detected. Run the live test again, pressing a button and moving the stick on each Joy-Con.",
        )
        Verdict.INCOMPATIBLE -> Triple(COLOR_FAIL, "Not compatible", "Shizuku couldn't start JoynCheck's helper, which JoynCon needs too.")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(color)
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Text(body)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ResultRow(title: String, result: CheckResult) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 4.dp).size(12.dp), contentAlignment = Alignment.Center) {
            if (result.status == CheckStatus.RUNNING) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            } else {
                Dot(
                    when (result.status) {
                        CheckStatus.PASS -> COLOR_OK
                        CheckStatus.WARN -> COLOR_WARN
                        CheckStatus.FAIL -> COLOR_FAIL
                        else -> COLOR_OFF
                    }
                )
            }
        }
        Column {
            Text(title)
            if (result.detail.isNotEmpty()) {
                Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusRow(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(color)
        Text("$label: $value")
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}
