/*
 * Copyright 2023-2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wibechat.android

import android.app.Application
import android.content.Context
import com.wibechat.android.WibeChat.loggingLevel
import com.wibechat.android.dagger.DaggerWibeChatComponent
import com.wibechat.android.dagger.RTCModule
import com.wibechat.android.dagger.create
import com.wibechat.android.room.Room
import com.wibechat.android.util.WCLog
import com.wibechat.android.util.LoggingLevel

/**
 * The main entry point into using WibeChat.
 *
 * @see [WibeChat.create]
 */
object WibeChat {
    /**
     * [LoggingLevel] to use for Wibechat logs. Set to [LoggingLevel.OFF] to turn off logs.
     *
     * Defaults to [LoggingLevel.OFF]
     */
    @JvmStatic
    var loggingLevel: LoggingLevel
        get() = WCLog.loggingLevel
        set(value) {
            WCLog.loggingLevel = value
        }

    /**
     * The [WCLog.Logger] to use for Wibechat logs.
     *
     * Default implementation prints to logcat.
     */
    @JvmStatic
    var logger: WCLog.Logger?
        get() = WCLog.logger
        set(value) {
            WCLog.logger = value
        }

    /**
     * Enables logs for the underlying WebRTC sdk logging. Used in conjunction with [loggingLevel].
     *
     * Note: WebRTC logging is very noisy and should only be used to diagnose native WebRTC issues.
     */
    @JvmStatic
    var enableWebRTCLogging: Boolean = false

    /**
     * Certain WebRTC classes need to be initialized prior to use.
     *
     * This does not need to be called under normal circumstances, as [WibeChat.create]
     * will handle this for you.
     */
    fun init(appContext: Context) {
        RTCModule.libWebrtcInitialization(appContext)
    }

    /**
     * Create a Room object.
     */
    fun create(
        appContext: Context,
        options: RoomOptions = RoomOptions(),
        overrides: WibeChatOverrides = WibeChatOverrides(),
    ): Room {
        val ctx = appContext.applicationContext

        if (ctx !is Application) {
            WCLog.w { "Application context was not found, this may cause memory leaks." }
        }

        val component = DaggerWibeChatComponent
            .factory()
            .create(ctx, overrides)

        val room = component.roomFactory().create(ctx)
        room.setRoomOptions(options)

        return room
    }
}
