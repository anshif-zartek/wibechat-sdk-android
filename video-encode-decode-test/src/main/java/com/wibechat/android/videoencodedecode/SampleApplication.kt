package com.wibechat.android.videoencodedecode

import android.app.Application
import com.wibechat.android.WibeChat
import com.wibechat.android.util.LoggingLevel

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        WibeChat.loggingLevel = LoggingLevel.VERBOSE
    }
}
