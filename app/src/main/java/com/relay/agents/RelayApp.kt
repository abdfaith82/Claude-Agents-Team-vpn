package com.relay.agents

import de.blinkt.openvpn.core.ICSOpenVPNApplication
import java.io.File

class RelayApp : ICSOpenVPNApplication() {
    override fun onCreate() {
        super.onCreate()
        val f = File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { f.writeText(e.stackTraceToString()) }
            prev?.uncaughtException(t, e)
        }
    }
}
