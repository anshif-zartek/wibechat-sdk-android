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

package com.wibechat.android.test.mock.dagger

import android.content.Context
import android.javax.sdp.SdpFactory
import dagger.Module
import dagger.Provides
import dagger.Reusable
import com.wibechat.android.audio.AudioBufferCallbackDispatcher
import com.wibechat.android.audio.AudioProcessingController
import com.wibechat.android.audio.AudioRecordPrewarmer
import com.wibechat.android.audio.AudioRecordSamplesDispatcher
import com.wibechat.android.audio.NoAudioRecordPrewarmer
import com.wibechat.android.dagger.CapabilitiesGetter
import com.wibechat.android.dagger.InjectionNames
import com.wibechat.android.e2ee.DataPacketCryptorManager
import com.wibechat.android.e2ee.KeyProvider
import com.wibechat.android.room.datatrack.LocalDataTrackManagerFactory
import com.wibechat.android.room.datatrack.RemoteDataTrackManagerFactory
import com.wibechat.android.test.mock.MockAudioDeviceModule
import com.wibechat.android.test.mock.MockAudioProcessingController
import com.wibechat.android.test.mock.MockEglBase
import com.wibechat.android.test.mock.e2ee.ReversingDataPacketCryptorManager
import com.wibechat.android.test.mock.room.datatrack.MockLocalDataTrackManagerFactory
import com.wibechat.android.test.mock.room.datatrack.MockRemoteDataTrackManagerFactory
import com.wibechat.android.webrtc.PeerConnectionFactoryManager
import com.wibechat.android.webrtc.peerconnection.RTCThreadToken
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.WebRTCInitializer
import livekit.org.webrtc.audio.AudioDeviceModule
import javax.inject.Named
import javax.inject.Singleton

@Module
object TestRTCModule {

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_BUFFER_CALLBACK_DISPATCHER)
    @Singleton
    fun localAudioBufferCallbackDispatcher(): AudioBufferCallbackDispatcher {
        return AudioBufferCallbackDispatcher()
    }

    @Provides
    @Singleton
    fun eglBase(): EglBase {
        return MockEglBase()
    }

    @Provides
    fun eglContext(eglBase: EglBase): EglBase.Context = eglBase.eglBaseContext

    @Provides
    fun audioProcessingController(): AudioProcessingController {
        return MockAudioProcessingController()
    }

    @Provides
    @Singleton
    fun audioDeviceModule(): AudioDeviceModule {
        return MockAudioDeviceModule()
    }

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_RECORD_SAMPLES_DISPATCHER)
    @Singleton
    fun localAudioSamplesDispatcher(): AudioRecordSamplesDispatcher {
        return AudioRecordSamplesDispatcher()
    }

    @Provides
    fun audioPrewarmer(): AudioRecordPrewarmer {
        return NoAudioRecordPrewarmer()
    }

    @Provides
    @Singleton
    fun peerConnectionFactory(
        appContext: Context,
    ): PeerConnectionFactory {
        WebRTCInitializer.initialize(appContext)
        return MockPeerConnectionFactory()
    }

    @Provides
    @Singleton
    fun peerConnectionFactoryManager(
        peerConnectionFactory: PeerConnectionFactory,
    ): PeerConnectionFactoryManager {
        return PeerConnectionFactoryManager(peerConnectionFactory)
    }

    @Provides
    @Reusable
    fun rtcThreadToken(
        manager: PeerConnectionFactoryManager,
    ): RTCThreadToken {
        return RTCThreadTokenImpl(manager)
    }

    private class RTCThreadTokenImpl(private val manager: PeerConnectionFactoryManager) : RTCThreadToken {
        override val isDisposed: Boolean
            get() = manager.isDisposed
    }

    @Provides
    @Named(InjectionNames.SENDER)
    fun senderCapabilitiesGetter(peerConnectionFactory: PeerConnectionFactory): CapabilitiesGetter {
        return { mediaType: MediaStreamTrack.MediaType ->
            peerConnectionFactory.getRtpSenderCapabilities(mediaType)
        }
    }

    @Provides
    @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
    fun videoHwAccel() = true

    @Provides
    fun sdpFactory() = SdpFactory.getInstance()

    @Provides
    fun dataPacketCryptorManagerFactory(): DataPacketCryptorManager.Factory = object : DataPacketCryptorManager.Factory {
        override fun create(keyProvider: KeyProvider): DataPacketCryptorManager {
            return ReversingDataPacketCryptorManager()
        }
    }

    @Provides
    @Singleton
    fun mockLocalDataTrackManagerFactory(): MockLocalDataTrackManagerFactory {
        return MockLocalDataTrackManagerFactory()
    }

    @Provides
    @Singleton
    internal fun localDataTrackManagerFactory(
        factory: MockLocalDataTrackManagerFactory,
    ): LocalDataTrackManagerFactory = factory

    @Provides
    @Singleton
    fun mockRemoteDataTrackManagerFactory(): MockRemoteDataTrackManagerFactory {
        return MockRemoteDataTrackManagerFactory()
    }

    @Provides
    @Singleton
    internal fun remoteDataTrackManagerFactory(
        factory: MockRemoteDataTrackManagerFactory,
    ): RemoteDataTrackManagerFactory = factory
}
