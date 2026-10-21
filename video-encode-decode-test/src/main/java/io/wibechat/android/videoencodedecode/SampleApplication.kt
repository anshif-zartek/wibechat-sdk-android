package io.wibechat.android.videoencodedecode

import android.app.Application
import io.wibechat.android.WibeChat
import io.wibechat.android.util.LoggingLevel

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        WibeChat.loggingLevel = LoggingLevel.VERBOSE
    }
}
