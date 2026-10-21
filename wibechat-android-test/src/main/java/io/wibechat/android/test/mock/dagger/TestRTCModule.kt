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

package io.wibechat.android.test.mock.dagger

import android.content.Context
import android.javax.sdp.SdpFactory
import dagger.Module
import dagger.Provides
import dagger.Reusable
import io.wibechat.android.audio.AudioBufferCallbackDispatcher
import io.wibechat.android.audio.AudioProcessingController
import io.wibechat.android.audio.AudioRecordPrewarmer
import io.wibechat.android.audio.AudioRecordSamplesDispatcher
import io.wibechat.android.audio.NoAudioRecordPrewarmer
import io.wibechat.android.dagger.CapabilitiesGetter
import io.wibechat.android.dagger.InjectionNames
import io.wibechat.android.e2ee.DataPacketCryptorManager
import io.wibechat.android.e2ee.KeyProvider
import io.wibechat.android.room.datatrack.LocalDataTrackManagerFactory
import io.wibechat.android.room.datatrack.RemoteDataTrackManagerFactory
import io.wibechat.android.test.mock.MockAudioDeviceModule
import io.wibechat.android.test.mock.MockAudioProcessingController
import io.wibechat.android.test.mock.MockEglBase
import io.wibechat.android.test.mock.e2ee.ReversingDataPacketCryptorManager
import io.wibechat.android.test.mock.room.datatrack.MockLocalDataTrackManagerFactory
import io.wibechat.android.test.mock.room.datatrack.MockRemoteDataTrackManagerFactory
import io.wibechat.android.webrtc.PeerConnectionFactoryManager
import io.wibechat.android.webrtc.peerconnection.RTCThreadToken
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
