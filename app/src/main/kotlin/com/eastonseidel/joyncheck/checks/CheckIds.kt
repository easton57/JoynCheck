package com.eastonseidel.joyncheck.checks

import android.view.InputDevice

object CheckIds {
    const val VENDOR_NINTENDO = 0x057e
    const val PRODUCT_JOYCON_L = 0x2006
    const val PRODUCT_JOYCON_R = 0x2007

    // Must match TEST_VENDOR/TEST_PRODUCT in joyncheck_native.cpp.
    const val TEST_VENDOR = 0x0ee2
    const val TEST_PRODUCT = 0x00c7

    fun isTestDevice(device: InputDevice?): Boolean =
        device != null && device.vendorId == TEST_VENDOR && device.productId == TEST_PRODUCT
}
