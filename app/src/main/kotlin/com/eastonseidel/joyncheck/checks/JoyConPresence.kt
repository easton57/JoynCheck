package com.eastonseidel.joyncheck.checks

import android.content.Context
import android.hardware.input.InputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Presence(val left: Boolean = false, val right: Boolean = false)

/** Which Joy-Cons Android itself currently sees, via the zero-permission InputManager listener. */
object JoyConPresence {
    private val _state = MutableStateFlow(Presence())
    val state: StateFlow<Presence> = _state

    private lateinit var inputManager: InputManager

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = rescan()
        override fun onInputDeviceRemoved(deviceId: Int) = rescan()
        override fun onInputDeviceChanged(deviceId: Int) = rescan()
    }

    fun init(context: Context) {
        if (::inputManager.isInitialized) return
        inputManager = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(deviceListener, null)
        rescan()
    }

    fun rescan() {
        if (!::inputManager.isInitialized) return
        var left = false
        var right = false
        for (id in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(id) ?: continue
            if (device.vendorId != CheckIds.VENDOR_NINTENDO) continue
            when (device.productId) {
                CheckIds.PRODUCT_JOYCON_L -> left = true
                CheckIds.PRODUCT_JOYCON_R -> right = true
            }
        }
        _state.value = Presence(left, right)
    }

    /** Whether Android has registered the bridge's test gamepad as an input device. */
    fun testDeviceVisible(): Boolean = inputManager.inputDeviceIds.any { id ->
        inputManager.getInputDevice(id)?.let(CheckIds::isTestDevice) == true
    }
}
