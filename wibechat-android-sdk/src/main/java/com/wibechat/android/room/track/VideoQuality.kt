/*
 * Copyright 2024 LiveKit, Inc.
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

package com.wibechat.android.room.track

import wibechat.WibechatModels

enum class VideoQuality {
    LOW,
    MEDIUM,
    HIGH,
    ;

    fun toProto(): WibechatModels.VideoQuality {
        return when (this) {
            LOW -> WibechatModels.VideoQuality.LOW
            MEDIUM -> WibechatModels.VideoQuality.MEDIUM
            HIGH -> WibechatModels.VideoQuality.HIGH
        }
    }

    companion object {
        fun fromProto(quality: WibechatModels.VideoQuality): VideoQuality? {
            return when (quality) {
                WibechatModels.VideoQuality.LOW -> LOW
                WibechatModels.VideoQuality.MEDIUM -> MEDIUM
                WibechatModels.VideoQuality.HIGH -> HIGH
                WibechatModels.VideoQuality.OFF -> null
                WibechatModels.VideoQuality.UNRECOGNIZED -> null
            }
        }
    }
}
