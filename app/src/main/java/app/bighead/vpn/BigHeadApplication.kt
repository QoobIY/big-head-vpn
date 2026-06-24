package app.bighead.vpn

import android.app.Application
import app.bighead.vpn.vpn.DebugLog

class BigHeadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLog.append(this, "crash on ${thread.name}", error)
            previousHandler?.uncaughtException(thread, error)
        }
    }
}
