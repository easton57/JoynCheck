package com.eastonseidel.joyncheck.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eastonseidel.joyncheck.bridge.BridgeClient
import com.eastonseidel.joyncheck.checks.CheckIds
import com.eastonseidel.joyncheck.checks.Checker
import com.eastonseidel.joyncheck.checks.JoyConPresence
import com.eastonseidel.joyncheck.shizuku.ShizukuHelper
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JoynCheckTheme {
                CheckScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up Shizuku being installed/started, or Joy-Cons paired, while the user was away.
        ShizukuHelper.refreshState()
        JoyConPresence.rescan()
    }

    override fun onStop() {
        super.onStop()
        // Never leave the Joy-Cons grabbed while the user is off in another app.
        Checker.stopLiveTest()
    }

    override fun onDestroy() {
        if (isFinishing) BridgeClient.unbind()
        super.onDestroy()
    }

    // The live test's final leg: input forwarded through the test gamepad has to come back to us
    // through Android's normal dispatch, exactly as JoynCon's merged gamepad reaches a game.
    // Consumed so the synthetic presses can't also click buttons on screen.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CheckIds.isTestDevice(event.device)) {
            if (event.action == KeyEvent.ACTION_DOWN) Checker.onVirtualButton()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (CheckIds.isTestDevice(event.device)) {
            val axes = listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
            if (axes.any { abs(event.getAxisValue(it)) > 0.3f }) Checker.onVirtualStick()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
