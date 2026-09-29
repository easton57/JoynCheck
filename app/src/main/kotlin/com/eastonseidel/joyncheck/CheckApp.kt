package com.eastonseidel.joyncheck

import android.app.Application
import com.eastonseidel.joyncheck.checks.JoyConPresence
import com.eastonseidel.joyncheck.shizuku.ShizukuHelper

class CheckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.init(this)
        JoyConPresence.init(this)
    }
}
