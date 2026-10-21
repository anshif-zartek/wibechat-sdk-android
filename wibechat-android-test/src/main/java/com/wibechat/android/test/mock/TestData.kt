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

package com.wibechat.android.test.mock

import wibechat.WibechatModels
import wibechat.WibechatModels.ParticipantInfo.Kind
import wibechat.WibechatRtc
import java.util.UUID

object TestData {

    const val EXAMPLE_URL = "ws://www.example.com"

    val LOCAL_AUDIO_TRACK = with(WibechatModels.TrackInfo.newBuilder()) {
        sid = "TR_local_audio_track_sid"
        type = WibechatModels.TrackType.AUDIO
        source = WibechatModels.TrackSource.MICROPHONE
        build()
    }
    val LOCAL_VIDEO_TRACK = with(WibechatModels.TrackInfo.newBuilder()) {
        sid = "TR_local_video_track_sid"
        type = WibechatModels.TrackType.VIDEO
        source = WibechatModels.TrackSource.CAMERA
        build()
    }

    val REMOTE_AUDIO_TRACK = with(WibechatModels.TrackInfo.newBuilder()) {
        sid = "TR_remote_audio_track_sid"
        type = WibechatModels.TrackType.AUDIO
        source = WibechatModels.TrackSource.MICROPHONE
        build()
    }

    val REMOTE_VIDEO_TRACK = with(WibechatModels.TrackInfo.newBuilder()) {
        sid = "TR_remote_video_track_sid"
        type = WibechatModels.TrackType.VIDEO
        source = WibechatModels.TrackSource.CAMERA
        build()
    }

    val LOCAL_PARTICIPANT = with(WibechatModels.ParticipantInfo.newBuilder()) {
        sid = "local_participant_sid"
        identity = "local_participant_identity"
        state = WibechatModels.ParticipantInfo.State.ACTIVE
        metadata = "local_metadata"
        permission = with(WibechatModels.ParticipantPermission.newBuilder()) {
            canPublish = true
            canSubscribe = true
            canPublishData = true

            hidden = false
            recorder = false
            build()
        }
        joinedAt = 0
        joinedAtMs = 0
        putAttributes("attribute", "value")
        build()
    }

    val REMOTE_PARTICIPANT = with(WibechatModels.ParticipantInfo.newBuilder()) {
        sid = "remote_participant_sid"
        identity = "remote_participant_identity"
        state = WibechatModels.ParticipantInfo.State.ACTIVE
        metadata = "remote_metadata"
        isPublisher = true
        permission = with(WibechatModels.ParticipantPermission.newBuilder()) {
            canPublish = true
            canSubscribe = true
            canPublishData
            build()
        }
        addTracks(REMOTE_AUDIO_TRACK)
        addTracks(REMOTE_VIDEO_TRACK)
        putAttributes("attribute", "value")
        build()
    }

    val REMOTE_SPEAKER_INFO = with(WibechatModels.SpeakerInfo.newBuilder()) {
        sid = REMOTE_PARTICIPANT.sid
        level = 1.0f
        active = true
        build()
    }

    val ENABLED_CODECS = listOf(
        codecForMime("video/VP8"),
        codecForMime("video/VP9"),
        codecForMime("video/H264"),
        codecForMime("video/AV1"),
        codecForMime("video/H265"),
        codecForMime("audio/red"),
        codecForMime("audio/opus"),
        codecForMime("audio/PCMU"),
        codecForMime("audio/PCMA"),
    )
    // Signal Responses
    // /////////////////////////////////

    val JOIN = with(WibechatRtc.SignalResponse.newBuilder()) {
        join = with(WibechatRtc.JoinResponse.newBuilder()) {

            addAllEnabledPublishCodecs(ENABLED_CODECS)
            // fastPublish = true

            room = with(WibechatModels.Room.newBuilder()) {
                name = "roomname"
                creationTime = 0
                creationTimeMs = 0
                departureTimeout = 20
                emptyTimeout = 300
                addAllEnabledCodecs(ENABLED_CODECS)
                build()
            }
            participant = LOCAL_PARTICIPANT
            subscriberPrimary = true
            addIceServers(
                with(WibechatRtc.ICEServer.newBuilder()) {
                    addUrls("stun:stun.example.com:19302")
                    username = "username"
                    credential = "credential"
                    build()
                },
            )
            serverInfo = with(WibechatModels.ServerInfo.newBuilder()) {
                edition = WibechatModels.ServerInfo.Edition.Cloud
                protocol = 16
                region = "Earth"
                version = "1.9.3"
                build()
            }
            serverVersion = "1.9.3"
            build()
        }
        build()
    }

    val RECONNECT = with(WibechatRtc.SignalResponse.newBuilder()) {
        reconnect = with(WibechatRtc.ReconnectResponse.newBuilder()) {
            addIceServers(
                with(WibechatRtc.ICEServer.newBuilder()) {
                    addUrls("stun:stun.reconnect.com:19302")
                    username = "username"
                    credential = "credential"
                    build()
                },
            )
            clientConfiguration = with(WibechatModels.ClientConfiguration.newBuilder()) {
                forceRelay = WibechatModels.ClientConfigSetting.ENABLED
                build()
            }
            lastMessageSeq = 1
            build()
        }
        build()
    }

    val OFFER = with(WibechatRtc.SignalResponse.newBuilder()) {
        offer = with(WibechatRtc.SessionDescription.newBuilder()) {
            sdp = "remote_offer"
            type = "offer"
            id = 99
            build()
        }
        build()
    }

    val ROOM_UPDATE = with(WibechatRtc.SignalResponse.newBuilder()) {
        roomUpdate = with(WibechatRtc.RoomUpdate.newBuilder()) {
            room = with(WibechatModels.Room.newBuilder()) {
                sid = "room_sid"
                metadata = "metadata"
                activeRecording = true
                build()
            }
            build()
        }
        build()
    }

    val LOCAL_TRACK_PUBLISHED = with(WibechatRtc.SignalResponse.newBuilder()) {
        trackPublished = with(WibechatRtc.TrackPublishedResponse.newBuilder()) {
            cid = "local_cid"
            track = LOCAL_AUDIO_TRACK
            build()
        }
        build()
    }

    val LOCAL_TRACK_UNPUBLISHED = with(WibechatRtc.SignalResponse.newBuilder()) {
        trackUnpublished = with(WibechatRtc.TrackUnpublishedResponse.newBuilder()) {
            trackSid = LOCAL_AUDIO_TRACK.sid
            build()
        }
        build()
    }

    val PERMISSION_CHANGE = with(WibechatRtc.SignalResponse.newBuilder()) {
        update = with(WibechatRtc.ParticipantUpdate.newBuilder()) {
            addParticipants(
                with(LOCAL_PARTICIPANT.toBuilder()) {
                    permission = with(WibechatModels.ParticipantPermission.newBuilder()) {
                        canPublish = true
                        canSubscribe = false
                        canPublishData = false
                        addCanPublishSources(WibechatModels.TrackSource.CAMERA)
                        canUpdateMetadata = false
                        canSubscribeMetrics = false
                        hidden = false
                        recorder = false
                        build()
                    }
                    build()
                },
            )
            build()
        }
        build()
    }

    val PARTICIPANT_JOIN = with(WibechatRtc.SignalResponse.newBuilder()) {
        update = with(WibechatRtc.ParticipantUpdate.newBuilder()) {
            addParticipants(REMOTE_PARTICIPANT)
            build()
        }
        build()
    }

    val PARTICIPANT_DISCONNECT = with(WibechatRtc.SignalResponse.newBuilder()) {
        update = with(WibechatRtc.ParticipantUpdate.newBuilder()) {
            val disconnectedParticipant = REMOTE_PARTICIPANT.toBuilder()
                .setState(WibechatModels.ParticipantInfo.State.DISCONNECTED)
                .build()

            addParticipants(disconnectedParticipant)
            build()
        }
        build()
    }

    val AGENT_JOIN = with(TestData.PARTICIPANT_JOIN.toBuilder()) {
        update = with(update.toBuilder()) {
            clearParticipants()
            val agent = with(TestData.REMOTE_PARTICIPANT.toBuilder()) {
                kind = Kind.AGENT
                clearAttributes()
                putAttributes("lk.agent.state", "listening")
                build()
            }
            addParticipants(agent)
            build()
        }
        build()
    }

    val ACTIVE_SPEAKER_UPDATE = with(WibechatRtc.SignalResponse.newBuilder()) {
        speakersChanged = with(WibechatRtc.SpeakersChanged.newBuilder()) {
            addSpeakers(REMOTE_SPEAKER_INFO)
            build()
        }
        build()
    }

    val LOCAL_PARTICIPANT_METADATA_CHANGED = with(WibechatRtc.SignalResponse.newBuilder()) {
        update = with(WibechatRtc.ParticipantUpdate.newBuilder()) {
            val participantMetadataChanged = LOCAL_PARTICIPANT.toBuilder()
                .setMetadata("changed_metadata")
                .setName("changed_name")
                .putAttributes("attribute", "changed_value")
                .build()

            addParticipants(participantMetadataChanged)
            build()
        }
        build()
    }

    val REMOTE_PARTICIPANT_METADATA_CHANGED = with(WibechatRtc.SignalResponse.newBuilder()) {
        update = with(WibechatRtc.ParticipantUpdate.newBuilder()) {
            val participantMetadataChanged = REMOTE_PARTICIPANT.toBuilder()
                .setMetadata("changed_metadata")
                .setName("changed_name")
                .build()

            addParticipants(participantMetadataChanged)
            build()
        }
        build()
    }

    val CONNECTION_QUALITY = with(WibechatRtc.SignalResponse.newBuilder()) {
        connectionQuality = with(WibechatRtc.ConnectionQualityUpdate.newBuilder()) {
            addUpdates(
                with(WibechatRtc.ConnectionQualityInfo.newBuilder()) {
                    participantSid = JOIN.join.participant.sid
                    quality = WibechatModels.ConnectionQuality.EXCELLENT
                    build()
                },
            )
            build()
        }
        build()
    }

    val STREAM_STATE_UPDATE = with(WibechatRtc.SignalResponse.newBuilder()) {
        streamStateUpdate = with(WibechatRtc.StreamStateUpdate.newBuilder()) {
            addStreamStates(
                with(WibechatRtc.StreamStateInfo.newBuilder()) {
                    participantSid = REMOTE_PARTICIPANT.sid
                    trackSid = REMOTE_AUDIO_TRACK.sid
                    state = WibechatRtc.StreamState.ACTIVE
                    build()
                },
            )
            build()
        }
        build()
    }

    val SUBSCRIPTION_PERMISSION_UPDATE = with(WibechatRtc.SignalResponse.newBuilder()) {
        subscriptionPermissionUpdate = with(WibechatRtc.SubscriptionPermissionUpdate.newBuilder()) {
            participantSid = REMOTE_PARTICIPANT.sid
            trackSid = REMOTE_AUDIO_TRACK.sid
            allowed = false
            build()
        }
        build()
    }

    val REFRESH_TOKEN = with(WibechatRtc.SignalResponse.newBuilder()) {
        refreshToken = "refresh_token"
        build()
    }

    val PONG = with(WibechatRtc.SignalResponse.newBuilder()) {
        pong = 1L
        build()
    }

    val LEAVE = with(WibechatRtc.SignalResponse.newBuilder()) {
        leave = with(WibechatRtc.LeaveRequest.newBuilder()) {
            reason = WibechatModels.DisconnectReason.SERVER_SHUTDOWN
            build()
        }
        build()
    }

    // Data packets

    val DATA_PACKET_TRANSCRIPTION = with(WibechatModels.DataPacket.newBuilder()) {
        transcription = with(WibechatModels.Transcription.newBuilder()) {
            transcribedParticipantIdentity = JOIN.join.participant.identity // Local participant's identity
            addSegments(
                with(WibechatModels.TranscriptionSegment.newBuilder()) {
                    id = "id"
                    language = "enUS"
                    text = "This is a transcription."
                    startTime = 1
                    endTime = 10
                    final = true
                    trackId = LOCAL_AUDIO_TRACK.sid
                    build()
                },
            )
            build()
        }
        build()
    }
    val DATA_PACKET_RPC_REQUEST = with(WibechatModels.DataPacket.newBuilder()) {
        participantIdentity = REMOTE_PARTICIPANT.identity
        rpcRequest = with(WibechatModels.RpcRequest.newBuilder()) {
            id = UUID.randomUUID().toString()
            method = "hello"
            payload = "hello world"
            responseTimeoutMs = 10000
            version = 1
            build()
        }
        build()
    }
}

private fun codecForMime(mime: String, fmtpLine: String? = null) = with(WibechatModels.Codec.newBuilder()) {
    setMime(mime)
    build()
}
