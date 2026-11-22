package com.relay.agents

import android.app.Application
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import android.os.Build
import de.blinkt.openvpn.core.VpnStatus
import java.io.File

class RelayApp : ICSOpenVPNApplication() {
    override fun onCreate() {
        super.onCreate()
        // في عملية :openvpn نكتب سجل النواة إلى ملف ليظهر في شاشة VPN (يفيد في تشخيص الأعطال).
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":openvpn")) {
            val lf = File(filesDir, "ovpn.log")
            runCatching { if (lf.length() > 200_000) lf.writeText("") else lf.appendText("---- process start ----\n") }
            VpnStatus.addLogListener { item -> runCatching { lf.appendText(item.getString(this) + "\n") } }
        }
        val f = File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { f.writeText(e.stackTraceToString()) }
            prev?.uncaughtException(t, e)
        }
    }
}
