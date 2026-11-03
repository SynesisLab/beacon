package dev.beacon

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BeaconApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            // Socket.IO client protocol trace → logcat (M4-d3 handshake probe):
            // the client logs via j.u.l, which has NO default handlers here —
            // bridge io.socket.* records to Timber.
            java.util.logging.Logger.getLogger("io.socket").apply {
                level = java.util.logging.Level.FINE
                addHandler(object : java.util.logging.Handler() {
                    override fun publish(record: java.util.logging.LogRecord) {
                        Timber.d("jul[%s] %s", record.loggerName?.substringAfterLast('.') ?: "?", record.message)
                    }
                    override fun flush() {}
                    override fun close() {}
                })
                useParentHandlers = false
            }
        }
    }
}