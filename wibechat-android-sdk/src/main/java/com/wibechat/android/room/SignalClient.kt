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

package com.wibechat.android.room

import androidx.annotation.VisibleForTesting
import com.google.protobuf.InvalidProtocolBufferException
import com.vdurmont.semver4j.Semver
import com.wibechat.android.ConnectOptions
import com.wibechat.android.RoomOptions
import com.wibechat.android.dagger.InjectionNames
import com.wibechat.android.room.datatrack.DataTrackSchemaException
import com.wibechat.android.room.participant.ParticipantTrackPermission
import com.wibechat.android.room.track.Track
import com.wibechat.android.stats.NetworkInfo
import com.wibechat.android.stats.getClientInfo
import com.wibechat.android.util.CloseableCoroutineScope
import com.wibechat.android.util.Either
import com.wibechat.android.util.WCLog
import com.wibechat.android.util.TimeoutException
import com.wibechat.android.util.rethrowIfCancellationSignal
import com.wibechat.android.util.toHttpUrl
import com.wibechat.android.util.toWebsocketUrl
import com.wibechat.android.util.withDeadline
import com.wibechat.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import wibechat.WibechatModels
import wibechat.WibechatModels.AudioTrackFeature
import wibechat.WibechatRtc
import wibechat.WibechatRtc.JoinResponse
import wibechat.WibechatRtc.ReconnectResponse
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SessionDescription
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * SignalClient to WibeChat WS servers
 * @suppress
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SignalClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    private val networkInfo: NetworkInfo,
) : WebSocketListener() {
    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var currentWs: WebSocket? = null

    @Volatile
    private var isReconnecting: Boolean = false
    internal var listener: Listener? = null
    internal var serverVersion: Semver? = null
    internal var serverInfo: ServerInfo? = null
    private var lastUrl: String? = null
    internal var lastOptions: ConnectOptions? = null
    private var lastRoomOptions: RoomOptions? = null

    @Volatile
    private var joinContinuation: CancellableContinuation<ConnectResult>? = null
    private lateinit var coroutineScope: CloseableCoroutineScope

    /**
     * @see [startRequestQueue]
     */
    private val requestFlow = MutableSharedFlow<WibechatRtc.SignalRequest>(Int.MAX_VALUE)
    private val requestFlowJobLock = Object()
    private var requestFlowJob: Job? = null

    /**
     * @see [onReadyForResponses]
     */
    private val responseFlow = MutableSharedFlow<IncomingSignal>(Int.MAX_VALUE)

    /**
     * Wire bytes of the Join [WibechatRtc.SignalResponse] from the last successful [join].
     * UniFFI parses these itself so re-encoding the decoded join cannot drop newer fields.
     */
    @Volatile
    internal var lastJoinEncoded: ByteArray? = null
        private set
    private val responseFlowJobLock = Object()
    private var responseFlowJob: Job? = null

    private var pingJob: Job? = null
    private var pongJob: Job? = null
    private var pingTimeoutDurationMillis: Long = 0
    private var pingIntervalDurationMillis: Long = 0
    private var rtt: Long = 0

    private val nextDataBlobRequestId = AtomicInteger(0)
    private val dataBlobCompleters = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED

    /**
     * @throws Exception if fails to connect.
     */
    @Throws(Exception::class)
    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions = ConnectOptions(),
        roomOptions: RoomOptions = RoomOptions(),
    ): JoinResponse {
        return when (val result = connect(url, token, options, roomOptions)) {
            is ConnectResult.Join -> result.response
            else -> throw IllegalStateException("Unexpected response during join: $result")
        }
    }

    /**
     * @throws Exception if fails to connect.
     */
    @Throws(Exception::class)
    @VisibleForTesting
    suspend fun reconnect(url: String, token: String, participantSid: String?): Either<ReconnectResponse, Unit> {
        return when (
            val result = connect(
                url,
                token,
                (lastOptions ?: ConnectOptions()).copy()
                    .apply {
                        reconnect = true
                        this.participantSid = participantSid
                    },
                lastRoomOptions ?: RoomOptions(),
            )
        ) {
            is ConnectResult.Reconnect -> Either.Left(result.response)
            is ConnectResult.OtherResponse -> Either.Right(Unit)
            is ConnectResult.Join -> throw IllegalStateException("Unexpected join response during reconnect")
        }
    }

    private suspend fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): ConnectResult {
        // Clean up any pre-existing connection.
        close(reason = "Starting new connection", shouldClearQueuedRequests = false)

        val wsUrlString = "${url.toWebsocketUrl()}/rtc${createConnectionParams(getClientInfo(options.clientProtocol), options, roomOptions)}"
        isReconnecting = options.reconnect

        WCLog.i { "connecting to $wsUrlString" }

        coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
        lastUrl = wsUrlString
        lastOptions = options
        lastRoomOptions = roomOptions

        val request = Request.Builder()
            .url(wsUrlString)
            .addHeader("Authorization", "Bearer $token")
            .build()

        return withDeadline(SIGNAL_CONNECT_TIMEOUT.milliseconds) {
            suspendCancellableCoroutine { cont ->
                // Wait for join response through WebSocketListener
                joinContinuation = cont
                cont.invokeOnCancellation {
                    // If the coroutine is cancelled, websocket needs to be cancelled.
                    // onFailure will handle cleanup.
                    WCLog.v { "connect cancelled, abort websocket" }
                    joinContinuation = null
                    currentWs?.cancel()
                }
                currentWs = websocketFactory.newWebSocket(request, this@SignalClient)
            }
        }
    }

    private fun createConnectionParams(
        clientInfo: WibechatModels.ClientInfo,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): String {
        val queryBuilder = StringBuilder()
        var first = true

        fun addParam(key: String, value: String) {
            queryBuilder.append(if (first) "?" else "&")
                .append(key).append("=").append(value)
            first = false
        }

        addParam(CONNECT_QUERY_PROTOCOL, options.protocolVersion.value.toString())

        if (options.reconnect) {
            addParam(CONNECT_QUERY_RECONNECT, "1")
            options.participantSid?.let { sid ->
                addParam(CONNECT_QUERY_PARTICIPANT_SID, sid)
            }
        }

        val autoSubscribe = if (options.autoSubscribe) 1 else 0
        addParam(CONNECT_QUERY_AUTOSUBSCRIBE, autoSubscribe.toString())

        val adaptiveStream = if (roomOptions.adaptiveStream) 1 else 0
        addParam(CONNECT_QUERY_ADAPTIVE_STREAM, adaptiveStream.toString())

        // Client info
        addParam(CONNECT_QUERY_SDK, "android")
        addParam(CONNECT_QUERY_VERSION, clientInfo.version)
        addParam(CONNECT_QUERY_DEVICE_MODEL, clientInfo.deviceModel)
        addParam(CONNECT_QUERY_OS, clientInfo.os)
        addParam(CONNECT_QUERY_OS_VERSION, clientInfo.osVersion)
        addParam(CONNECT_QUERY_NETWORK_TYPE, networkInfo.getNetworkType().protoName)
        addParam(CONNECT_QUERY_CLIENT_PROTOCOL, options.clientProtocol.value.toString())

        return queryBuilder.toString()
    }

    /**
     * Notifies that the downstream consumers of SignalClient are ready to consume messages.
     * Until this method is called, any messages received through the websocket are buffered.
     *
     * Should be called after resolving the join message.
     */
    fun onReadyForResponses() {
        if (responseFlowJob != null) {
            return
        }
        synchronized(responseFlowJobLock) {
            if (responseFlowJob == null) {
                responseFlowJob = coroutineScope.launch {
                    responseFlow.collect { incoming ->
                        responseFlow.resetReplayCache()
                        handleSignalResponseImpl(incoming.ws, incoming.response, incoming.encoded)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startRequestQueue() {
        if (requestFlowJob != null) {
            return
        }
        synchronized(requestFlowJobLock) {
            if (requestFlowJob == null) {
                requestFlowJob = coroutineScope.launch {
                    requestFlow.collect {
                        requestFlow.resetReplayCache()
                        sendRequestImpl(it)
                    }
                }
            }
        }
    }

    /**
     * On reconnection, SignalClient waits until the peer connection is established to send messages.
     * Call this method when it is connected.
     */
    fun onPCConnected() {
        startRequestQueue()
    }

    // --------------------------------- WebSocket Listener --------------------------------------//
    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket != currentWs) {
            // Possibly message from old websocket, discard.
            return
        }

        WCLog.w { "received JSON message, unsupported in this version." }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (webSocket != currentWs) {
            // Possibly message from old websocket, discard.
            return
        }
        val byteArray = bytes.toByteArray()
        val signalResponseBuilder = WibechatRtc.SignalResponse.newBuilder()
            .mergeFrom(byteArray)
        val response = signalResponseBuilder.build()

        handleSignalResponse(webSocket, response, byteArray)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket != currentWs) {
            return
        }
        handleWebSocketClose(reason, code)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        WCLog.v { "websocket closing" }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (webSocket != currentWs) {
            return
        }
        var reason: String? = null
        try {
            val lastToken = webSocket.request().header("Authorization")
            lastUrl?.let {
                val validationUrl = it.toHttpUrl().replaceFirst("/rtc?", "/rtc/validate?")
                val request = Request.Builder()
                    .url(validationUrl)
                    .apply {
                        if (lastToken != null) {
                            addHeader("Authorization", lastToken)
                        }
                    }
                    .build()

                val resp = okHttpClient.newCall(request).execute()
                val body = resp.body
                if (!resp.isSuccessful) {
                    reason = body?.string()
                }
                body?.close()
            }
        } catch (e: Throwable) {
            WCLog.e(e) { "failed to validate connection" }
        }

        val wasConnected = isConnected
        val wasReconnectHandshake = joinContinuation != null && isReconnecting

        // Clear joinContinuation before cont.cancel() so a synchronous nested onFailure (e.g. from
        // WebSocket.cancel) does not treat this as an in-flight join/reconnect handshake.
        val joinCont = joinContinuation
        joinContinuation = null

        if (reason != null) {
            WCLog.e(t) { "websocket failure: $reason" }
            val error = if (joinCont != null) {
                RoomException.ConnectException(reason, t)
            } else {
                Exception(reason)
            }
            listener?.onError(error)
            joinCont?.resumeWithException(error)
        } else {
            WCLog.e(t) { "websocket failure: $response" }
            val error = if (joinCont != null) {
                RoomException.ConnectException(t.localizedMessage, t)
            } else {
                t
            }
            listener?.onError(error)
            joinCont?.resumeWithException(error)
        }

        if (wasConnected || wasReconnectHandshake) {
            // onClosing/onClosed will not be called after onFailure.
            // Handle websocket closure here.
            // Also handle failure during a soft reconnect handshake (reconnect query): isConnected is
            // still false but the upper layer should be notified like a close. Initial join handshake
            // failures do not call onClose so RTCEngine can surface them via onError / onFailToConnect.
            handleWebSocketClose(
                reason = reason ?: response?.toString() ?: t.localizedMessage ?: "websocket failure",
                code = response?.code ?: CLOSE_REASON_WEBSOCKET_FAILURE,
            )
        }
    }

    private fun handleWebSocketClose(reason: String, code: Int) {
        WCLog.v { "websocket closed" }
        isConnected = false
        listener?.onClose(reason, code)
        requestFlow.resetReplayCache()
        responseFlow.resetReplayCache()
        pingJob?.cancel()
        pongJob?.cancel()
    }

    // ------------------------------- End WebSocket Listener ------------------------------------//

    private fun fromProtoSessionDescription(sd: WibechatRtc.SessionDescription): SessionDescription {
        val rtcSdpType = when (sd.type) {
            SD_TYPE_ANSWER -> SessionDescription.Type.ANSWER
            SD_TYPE_OFFER -> SessionDescription.Type.OFFER
            SD_TYPE_PRANSWER -> SessionDescription.Type.PRANSWER
            SD_TYPE_ROLLBACK -> SessionDescription.Type.ROLLBACK
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sd.type}")
        }
        return SessionDescription(rtcSdpType, sd.sdp)
    }

    fun sendOffer(offer: SessionDescription, offerId: Int) {
        val sd = offer.toProtoSessionDescription(offerId)
        val request = WibechatRtc.SignalRequest.newBuilder()
            .setOffer(sd)
            .build()

        sendRequest(request)
    }

    fun sendAnswer(answer: SessionDescription, offerId: Int) {
        val sd = answer.toProtoSessionDescription(offerId)
        val request = WibechatRtc.SignalRequest.newBuilder()
            .setAnswer(sd)
            .build()

        sendRequest(request)
    }

    fun sendCandidate(candidate: IceCandidate, target: WibechatRtc.SignalTarget) {
        val iceCandidateJSON = IceCandidateJSON(
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
        )

        val trickleRequest = WibechatRtc.TrickleRequest.newBuilder()
            .setCandidateInit(json.encodeToString(iceCandidateJSON))
            .setTarget(target)
            .build()

        val request = WibechatRtc.SignalRequest.newBuilder()
            .setTrickle(trickleRequest)
            .build()

        sendRequest(request)
    }

    fun sendMuteTrack(trackSid: String, muted: Boolean) {
        val muteRequest = WibechatRtc.MuteTrackRequest.newBuilder()
            .setSid(trackSid)
            .setMuted(muted)
            .build()

        val request = WibechatRtc.SignalRequest.newBuilder()
            .setMute(muteRequest)
            .build()

        sendRequest(request)
    }

    /**
     * @param builder an optional builder to include other parameters related to the track
     */
    fun sendAddTrack(
        cid: String,
        name: String,
        type: WibechatModels.TrackType,
        stream: String?,
        builder: WibechatRtc.AddTrackRequest.Builder = WibechatRtc.AddTrackRequest.newBuilder(),
    ) {
        val encryptionType = lastRoomOptions?.e2eeOptions?.encryptionType ?: WibechatModels.Encryption.Type.NONE
        val addTrackRequest = builder.apply {
            setCid(cid)
            setName(name)
            setType(type)
            if (stream != null) {
                setStream(stream)
            } else {
                clearStream()
            }
            encryption = encryptionType
        }
        val request = WibechatRtc.SignalRequest.newBuilder()
            .setAddTrack(addTrackRequest)
            .build()

        sendRequest(request)
    }

    fun sendUpdateTrackSettings(
        sid: String,
        disabled: Boolean,
        videoDimensions: Track.Dimensions?,
        videoQuality: WibechatModels.VideoQuality?,
        fps: Int?,
    ) {
        val trackSettings = WibechatRtc.UpdateTrackSettings.newBuilder()
            .addTrackSids(sid)
            .setDisabled(disabled)
            .apply {
                if (videoDimensions != null) {
                    width = videoDimensions.width
                    height = videoDimensions.height
                } else if (videoQuality != null) {
                    quality = videoQuality
                } else {
                    // default to HIGH
                    quality = WibechatModels.VideoQuality.HIGH
                }

                if (fps != null) {
                    setFps(fps)
                }
            }

        val request = WibechatRtc.SignalRequest.newBuilder()
            .setTrackSetting(trackSettings)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscription(subscribe: Boolean, vararg participantTracks: WibechatModels.ParticipantTracks) {
        val participantTracksList = participantTracks.toList()

        // backwards compatibility for protocol version < 6
        val trackSids = participantTracksList.map { it.trackSidsList }.flatten()
        val subscription = WibechatRtc.UpdateSubscription.newBuilder()
            .addAllParticipantTracks(participantTracks.toList())
            .addAllTrackSids(trackSids)
            .setSubscribe(subscribe)

        val request = WibechatRtc.SignalRequest.newBuilder()
            .setSubscription(subscription)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscriptionPermissions(
        allParticipants: Boolean,
        participantTrackPermissions: List<ParticipantTrackPermission>,
    ) {
        val update = WibechatRtc.SubscriptionPermission.newBuilder()
            .setAllParticipants(allParticipants)
            .addAllTrackPermissions(participantTrackPermissions.map { it.toProto() })

        val request = WibechatRtc.SignalRequest.newBuilder()
            .setSubscriptionPermission(update)
            .build()

        sendRequest(request)
    }

    fun sendUpdateLocalMetadata(metadata: String?, name: String?, attributes: Map<String, String>? = emptyMap()) {
        val update = WibechatRtc.UpdateParticipantMetadata.newBuilder()
            .setMetadata(metadata ?: "")
            .setName(name ?: "")
            .putAllAttributes(attributes)

        val request = WibechatRtc.SignalRequest.newBuilder()
            .setUpdateMetadata(update)
            .build()

        sendRequest(request)
    }

    fun sendSyncState(syncState: WibechatRtc.SyncState) {
        val request = WibechatRtc.SignalRequest.newBuilder()
            .setSyncState(syncState)
            .build()

        sendRequest(request)
    }

    internal fun sendSimulateScenario(scenario: WibechatRtc.SimulateScenario) {
        val request = WibechatRtc.SignalRequest.newBuilder()
            .setSimulate(scenario)
            .build()

        sendRequest(request)
    }

    fun sendLeave() {
        val request = with(WibechatRtc.SignalRequest.newBuilder()) {
            leave = with(WibechatRtc.LeaveRequest.newBuilder()) {
                reason = WibechatModels.DisconnectReason.CLIENT_INITIATED
                // server doesn't process this field, keeping it here to indicate the intent of a full disconnect
                action = WibechatRtc.LeaveRequest.Action.DISCONNECT
                build()
            }
            build()
        }

        sendRequest(request)
    }

    fun sendPing(): Long {
        val time = Date().time
        sendRequest(
            with(WibechatRtc.SignalRequest.newBuilder()) {
                ping = time
                build()
            },
        )
        sendRequest(
            with(WibechatRtc.SignalRequest.newBuilder()) {
                pingReq = with(WibechatRtc.Ping.newBuilder()) {
                    rtt = this@SignalClient.rtt
                    timestamp = time
                    build()
                }
                build()
            },
        )

        return time
    }

    fun sendUpdateLocalAudioTrack(trackSid: String, features: Collection<AudioTrackFeature>) {
        val request = with(WibechatRtc.SignalRequest.newBuilder()) {
            updateAudioTrack = with(WibechatRtc.UpdateLocalAudioTrack.newBuilder()) {
                setTrackSid(trackSid)
                addAllFeatures(features)
                build()
            }
            build()
        }

        sendRequest(request)
    }

    /**
     * Stores a blob on the server under [key], replacing nothing — a key can only be written once.
     */
    internal suspend fun sendStoreDataBlob(key: WibechatModels.DataBlobKey, contents: ByteArray): Result<Unit> {
        return sendIdCorrelatedRequest { requestId ->
            WibechatRtc.SignalRequest.newBuilder()
                .setStoreDataBlobRequest(
                    WibechatRtc.StoreDataBlobRequest.newBuilder()
                        .setRequestId(requestId)
                        .setBlob(
                            WibechatModels.DataBlob.newBuilder()
                                .setKey(key)
                                .setContents(com.google.protobuf.ByteString.copyFrom(contents)),
                        ),
                )
                .build()
        }.map { }
    }

    /**
     * Reads back a blob [participantIdentity] stored under [key].
     */
    internal suspend fun sendGetDataBlob(
        key: WibechatModels.DataBlobKey,
        participantIdentity: String,
    ): Result<ByteArray> {
        return sendIdCorrelatedRequest { requestId ->
            WibechatRtc.SignalRequest.newBuilder()
                .setGetDataBlobRequest(
                    WibechatRtc.GetDataBlobRequest.newBuilder()
                        .setRequestId(requestId)
                        .setParticipantIdentity(participantIdentity)
                        .setKey(key),
                )
                .build()
        }
    }

    /**
     * Sends a request the SFU answers by echoing its id, and waits for that answer.
     */
    private suspend fun sendIdCorrelatedRequest(
        build: (Int) -> WibechatRtc.SignalRequest,
    ): Result<ByteArray> {
        if (!isConnected) {
            return Result.failure(DataTrackSchemaException.Disconnected("Not connected to a room"))
        }
        val requestId = nextDataBlobRequestId.incrementAndGet()
        val deferred = CompletableDeferred<ByteArray>()
        dataBlobCompleters[requestId] = deferred
        try {
            sendRequest(build(requestId))
            return withDeadline(DATA_BLOB_REQUEST_TIMEOUT) {
                Result.success(deferred.await())
            }
        } catch (e: TimeoutException) {
            return Result.failure(
                DataTrackSchemaException.Timeout("Timed out waiting for data blob response", e),
            )
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            return Result.failure(
                e as? DataTrackSchemaException
                    ?: DataTrackSchemaException.Internal(e.message ?: "", e),
            )
        } finally {
            dataBlobCompleters.remove(requestId)
        }
    }

    private fun sendRequest(request: WibechatRtc.SignalRequest) {
        val skipQueue = skipQueueTypes.contains(request.messageCase)

        if (skipQueue) {
            sendRequestImpl(request)
        } else {
            requestFlow.tryEmit(request)
        }
    }

    /**
     * Sends a previously encoded [WibechatRtc.SignalRequest] (e.g. from UniFFI data track manager).
     *
     * Undecodable bytes are dropped rather than thrown: this runs on a callback from the native
     * data track managers, so an exception here would unwind through the FFI boundary.
     */
    internal fun sendEncodedRequest(requestBytes: ByteArray) {
        val request = try {
            WibechatRtc.SignalRequest.parseFrom(requestBytes)
        } catch (e: InvalidProtocolBufferException) {
            WCLog.e(e) { "Discarding an encoded signal request that could not be parsed." }
            return
        }
        sendRequest(request)
    }

    private fun sendRequestImpl(request: WibechatRtc.SignalRequest) {
        WCLog.v { "sending request: $request" }
        if (!isConnected || currentWs == null) {
            WCLog.w { "not connected, could not send request $request" }
            return
        }
        val message = request.toByteArray().toByteString()
        val sent = currentWs?.send(message) ?: false

        if (!sent) {
            WCLog.e { "error sending request: $request" }
        }
    }

    private fun handleSignalResponse(ws: WebSocket, response: WibechatRtc.SignalResponse, encoded: ByteArray) {
        if (ws != currentWs) {
            return
        }

        WCLog.v { "response: $response" }

        if (!isConnected) {
            var shouldProcessMessage = false

            // Only handle certain messages if not connected.
            if (response.hasJoin()) {
                isConnected = true
                startRequestQueue()
                pingTimeoutDurationMillis = response.join.pingTimeout.toLong() * 1000
                pingIntervalDurationMillis = response.join.pingInterval.toLong() * 1000
                startPingJob()
                try {
                    serverVersion = Semver(response.join.serverVersion)
                } catch (t: Throwable) {
                    WCLog.w(t) { "Thrown while trying to parse server version." }
                }
                serverInfo = ServerInfo(
                    edition = ServerInfo.Edition.fromProto(response.join.serverInfo.edition),
                    version = serverVersion
                )
                lastJoinEncoded = encoded
                joinContinuation?.resumeWith(Result.success(ConnectResult.Join(response.join)))
                joinContinuation = null
            } else if (response.hasLeave()) {
                // Some reconnects may immediately send leave back without a join response first.
                handleSignalResponseImpl(ws, response, encoded)
                val cont = joinContinuation
                joinContinuation = null
                cont?.resumeWithException(
                    RoomException.ConnectException("Received leave during reconnect: ${response.leave.reason}"),
                )
            } else if (isReconnecting) {
                // When reconnecting, any message received means signal reconnected.
                // Newer servers will send a reconnect response first
                isReconnecting = false
                isConnected = true

                // Restart ping job with old settings.
                startPingJob()

                if (response.hasReconnect()) {
                    if (response.reconnect.hasServerInfo()) {
                        try {
                            serverVersion = Semver(response.reconnect.serverInfo.version)
                        } catch (t: Throwable) {
                            WCLog.w(t) { "Thrown while trying to parse server version from reconnect." }
                        }
                        serverInfo = ServerInfo(
                            edition = ServerInfo.Edition.fromProto(response.reconnect.serverInfo.edition),
                            version = serverVersion,
                        )
                    }
                    joinContinuation?.resumeWith(Result.success(ConnectResult.Reconnect(response.reconnect)))
                    joinContinuation = null
                } else {
                    joinContinuation?.resumeWith(Result.success(ConnectResult.OtherResponse))
                    joinContinuation = null
                    // Non-reconnect response, handle normally
                    shouldProcessMessage = true
                }
            } else {
                WCLog.e { "Received response while not connected. $response" }
            }
            if (!shouldProcessMessage) {
                return
            }
        }
        responseFlow.tryEmit(IncomingSignal(ws, response, encoded))
    }

    private fun handleSignalResponseImpl(ws: WebSocket, response: WibechatRtc.SignalResponse, encoded: ByteArray) {
        if (ws != currentWs) {
            WCLog.v { "received message from old websocket, discarding." }
            return
        }

        when (response.messageCase) {
            WibechatRtc.SignalResponse.MessageCase.ANSWER -> {
                val sd = fromProtoSessionDescription(response.answer)
                val offerId = response.answer.id
                listener?.onServerAnswer(sd, offerId)
            }

            WibechatRtc.SignalResponse.MessageCase.OFFER -> {
                val sd = fromProtoSessionDescription(response.offer)
                val offerId = response.offer.id
                listener?.onServerOffer(sd, offerId)
            }

            WibechatRtc.SignalResponse.MessageCase.TRICKLE -> {
                val iceCandidateJson =
                    json.decodeFromString<IceCandidateJSON>(response.trickle.candidateInit)
                val iceCandidate = IceCandidate(
                    iceCandidateJson.sdpMid,
                    iceCandidateJson.sdpMLineIndex,
                    iceCandidateJson.candidate,
                )
                listener?.onTrickle(iceCandidate, response.trickle.target)
            }

            WibechatRtc.SignalResponse.MessageCase.UPDATE -> {
                listener?.onParticipantUpdate(response.update.participantsList, encoded)
            }

            WibechatRtc.SignalResponse.MessageCase.TRACK_SUBSCRIBED -> {
                listener?.onLocalTrackSubscribed(response.trackSubscribed)
            }

            WibechatRtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> {
                listener?.onLocalTrackPublished(response.trackPublished)
            }

            WibechatRtc.SignalResponse.MessageCase.SPEAKERS_CHANGED -> {
                listener?.onSpeakersChanged(response.speakersChanged.speakersList)
            }

            WibechatRtc.SignalResponse.MessageCase.JOIN -> {
                WCLog.d { "received unexpected extra join message?" }
            }

            WibechatRtc.SignalResponse.MessageCase.LEAVE -> {
                listener?.onLeave(response.leave)
            }

            WibechatRtc.SignalResponse.MessageCase.MUTE -> {
                listener?.onRemoteMuteChanged(response.mute.sid, response.mute.muted)
            }

            WibechatRtc.SignalResponse.MessageCase.ROOM_UPDATE -> {
                listener?.onRoomUpdate(response.roomUpdate.room)
            }

            WibechatRtc.SignalResponse.MessageCase.CONNECTION_QUALITY -> {
                listener?.onConnectionQuality(response.connectionQuality.updatesList)
            }

            WibechatRtc.SignalResponse.MessageCase.STREAM_STATE_UPDATE -> {
                listener?.onStreamStateUpdate(response.streamStateUpdate.streamStatesList)
            }

            WibechatRtc.SignalResponse.MessageCase.SUBSCRIBED_QUALITY_UPDATE -> {
                val versionToIgnoreUpTo = Semver("0.15.1")
                if ((serverVersion?.compareTo(versionToIgnoreUpTo) ?: 1) <= 0) {
                    return
                }
                listener?.onSubscribedQualityUpdate(response.subscribedQualityUpdate)
            }

            WibechatRtc.SignalResponse.MessageCase.SUBSCRIPTION_PERMISSION_UPDATE -> {
                listener?.onSubscriptionPermissionUpdate(response.subscriptionPermissionUpdate)
            }

            WibechatRtc.SignalResponse.MessageCase.REFRESH_TOKEN -> {
                listener?.onRefreshToken(response.refreshToken)
            }

            WibechatRtc.SignalResponse.MessageCase.TRACK_UNPUBLISHED -> {
                listener?.onLocalTrackUnpublished(response.trackUnpublished)
            }

            WibechatRtc.SignalResponse.MessageCase.PONG -> {
                resetPingTimeout()
            }

            WibechatRtc.SignalResponse.MessageCase.PONG_RESP -> {
                rtt = Date().time - response.pongResp.lastPingTimestamp
                resetPingTimeout()
            }

            WibechatRtc.SignalResponse.MessageCase.RECONNECT -> {
                // Handshake-only message; handled in handleSignalResponse() before connection.
                WCLog.d { "ignoring reconnect response received after connected" }
            }

            WibechatRtc.SignalResponse.MessageCase.SUBSCRIPTION_RESPONSE -> {
                listener?.onSubscriptionError(response.subscriptionResponse)
            }

            WibechatRtc.SignalResponse.MessageCase.REQUEST_RESPONSE -> {
                val requestResponse = response.requestResponse
                val reason = requestResponse.reason
                val isFailure = reason != WibechatRtc.RequestResponse.Reason.OK &&
                    reason != WibechatRtc.RequestResponse.Reason.QUEUED
                if (isFailure) {
                    val completer = dataBlobCompleters.remove(requestResponse.requestId)
                    if (completer != null) {
                        val message = requestResponse.message.ifEmpty {
                            "Request rejected (reason ${reason.number})"
                        }
                        completer.completeExceptionally(DataTrackSchemaException.Rejected(message))
                    }
                }
                // Pass the full SignalResponse — UniFFI deserializes and filters data-track related ones.
                listener?.onRequestResponse(encoded)
            }

            WibechatRtc.SignalResponse.MessageCase.ROOM_MOVED -> {
                // TODO
            }

            WibechatRtc.SignalResponse.MessageCase.MEDIA_SECTIONS_REQUIREMENT -> {
                // TODO
            }

            WibechatRtc.SignalResponse.MessageCase.SUBSCRIBED_AUDIO_CODEC_UPDATE -> {
                // TODO
            }

            WibechatRtc.SignalResponse.MessageCase.PUBLISH_DATA_TRACK_RESPONSE -> {
                listener?.onPublishDataTrackResponse(encoded)
            }

            WibechatRtc.SignalResponse.MessageCase.UNPUBLISH_DATA_TRACK_RESPONSE -> {
                listener?.onUnpublishDataTrackResponse(encoded)
            }

            WibechatRtc.SignalResponse.MessageCase.DATA_TRACK_SUBSCRIBER_HANDLES -> {
                listener?.onDataTrackSubscriberHandles(encoded)
            }

            WibechatRtc.SignalResponse.MessageCase.STORE_DATA_BLOB_RESPONSE -> {
                dataBlobCompleters.remove(response.storeDataBlobResponse.requestId)
                    ?.complete(ByteArray(0))
            }

            WibechatRtc.SignalResponse.MessageCase.GET_DATA_BLOB_RESPONSE -> {
                dataBlobCompleters.remove(response.getDataBlobResponse.requestId)
                    ?.complete(response.getDataBlobResponse.blob.contents.toByteArray())
            }
            WibechatRtc.SignalResponse.MessageCase.MESSAGE_NOT_SET,
            null,
            -> {
                WCLog.v { "empty messageCase!" }
            }
        }
    }

    private fun startPingJob() {
        if (pingJob == null && pingIntervalDurationMillis != 0L) {
            pingJob = coroutineScope.launch {
                while (true) {
                    delay(pingIntervalDurationMillis)
                    val pingTimestamp = sendPing()
                    startPingTimeout(pingTimestamp)
                }
            }
        }
    }

    private fun startPingTimeout(timestamp: Long) {
        if (pongJob != null) {
            return
        }
        pongJob = coroutineScope.launch {
            delay(pingTimeoutDurationMillis)
            WCLog.d { "Ping timeout reached for ping sent at $timestamp." }
            currentWs?.close(CLOSE_REASON_PING_TIMEOUT, "Ping timeout")
        }
    }

    private fun resetPingTimeout() {
        pongJob?.cancel()
        pongJob = null
    }

    private fun failPendingDataBlobRequests() {
        val pending = dataBlobCompleters.values.toList()
        dataBlobCompleters.clear()
        pending.forEach { completer ->
            completer.completeExceptionally(
                DataTrackSchemaException.Disconnected("Not connected to a room"),
            )
        }
    }

    /**
     * Closes out any existing websocket connection, and cleans up used resources.
     *
     * Can be reused afterwards.
     */
    fun close(code: Int = CLOSE_REASON_NORMAL_CLOSURE, reason: String = "Normal Closure", shouldClearQueuedRequests: Boolean = true) {
        @Suppress("ThrowingExceptionsWithoutMessageOrCause")
        WCLog.v(Exception()) { "Closing SignalClient: code = $code, reason = $reason" }
        isConnected = false
        isReconnecting = false
        failPendingDataBlobRequests()
        if (::coroutineScope.isInitialized) {
            coroutineScope.close()
        }
        requestFlowJob?.cancel()
        requestFlowJob = null
        responseFlowJob?.cancel()
        responseFlowJob = null
        pingJob?.cancel()
        pingJob = null
        pongJob?.cancel()
        pongJob = null
        currentWs?.close(code, reason)
        currentWs = null
        // Same ordering as [onFailure]: clear the field before cancel() for synchronous listener callbacks.
        val joinCont = joinContinuation
        joinContinuation = null
        joinCont?.cancel()
        if (shouldClearQueuedRequests) {
            requestFlow.resetReplayCache()
        }
        responseFlow.resetReplayCache()
        lastUrl = null
        lastOptions = null
        lastRoomOptions = null
        serverVersion = null
        serverInfo = null
    }

    internal interface Listener {
        fun onServerAnswer(sessionDescription: SessionDescription, offerId: Int)
        fun onServerOffer(sessionDescription: SessionDescription, offerId: Int)
        fun onTrickle(candidate: IceCandidate, target: WibechatRtc.SignalTarget)
        fun onLocalTrackPublished(response: WibechatRtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<WibechatModels.ParticipantInfo>, encoded: ByteArray)
        fun onSpeakersChanged(speakers: List<WibechatModels.SpeakerInfo>)
        fun onClose(reason: String, code: Int)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onRoomUpdate(update: WibechatModels.Room)
        fun onConnectionQuality(updates: List<WibechatRtc.ConnectionQualityInfo>)
        fun onLeave(leave: WibechatRtc.LeaveRequest)
        fun onError(error: Throwable)
        fun onStreamStateUpdate(streamStates: List<WibechatRtc.StreamStateInfo>)
        fun onSubscribedQualityUpdate(subscribedQualityUpdate: WibechatRtc.SubscribedQualityUpdate)
        fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: WibechatRtc.SubscriptionPermissionUpdate)
        fun onSubscriptionError(subscriptionResponse: WibechatRtc.SubscriptionResponse)
        fun onRefreshToken(token: String)
        fun onLocalTrackUnpublished(trackUnpublished: WibechatRtc.TrackUnpublishedResponse)
        fun onLocalTrackSubscribed(trackSubscribed: WibechatRtc.TrackSubscribed)
        fun onPublishDataTrackResponse(encoded: ByteArray) {}
        fun onUnpublishDataTrackResponse(encoded: ByteArray) {}
        fun onRequestResponse(encoded: ByteArray) {}
        fun onDataTrackSubscriberHandles(encoded: ByteArray) {}
    }

    /**
     * A signal message together with the websocket bytes it arrived as.
     * Data-track managers parse the encoded form themselves.
     */
    private class IncomingSignal(
        val ws: WebSocket,
        val response: WibechatRtc.SignalResponse,
        val encoded: ByteArray,
    )

    /**
     * Result of waiting for the initial signal response after opening the WebSocket.
     * Join always yields [Join]; reconnect yields [Reconnect] or [OtherResponse].
     */
    private sealed class ConnectResult {
        data class Join(val response: JoinResponse) : ConnectResult()
        data class Reconnect(val response: ReconnectResponse) : ConnectResult()
        data object OtherResponse : ConnectResult()
    }

    companion object {
        const val CONNECT_QUERY_TOKEN = "access_token"
        const val CONNECT_QUERY_RECONNECT = "reconnect"
        const val CONNECT_QUERY_AUTOSUBSCRIBE = "auto_subscribe"
        const val CONNECT_QUERY_ADAPTIVE_STREAM = "adaptive_stream"
        const val CONNECT_QUERY_SDK = "sdk"
        const val CONNECT_QUERY_VERSION = "version"
        const val CONNECT_QUERY_PROTOCOL = "protocol"
        const val CONNECT_QUERY_DEVICE_MODEL = "device_model"
        const val CONNECT_QUERY_OS = "os"
        const val CONNECT_QUERY_OS_VERSION = "os_version"
        const val CONNECT_QUERY_NETWORK_TYPE = "network"
        const val CONNECT_QUERY_PARTICIPANT_SID = "sid"
        const val CONNECT_QUERY_CLIENT_PROTOCOL = "client_protocol"

        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"
        const val SD_TYPE_ROLLBACK = "rollback"
        const val SDK_TYPE = "android"

        private val skipQueueTypes = listOf(
            WibechatRtc.SignalRequest.MessageCase.SYNC_STATE,
            WibechatRtc.SignalRequest.MessageCase.TRICKLE,
            WibechatRtc.SignalRequest.MessageCase.OFFER,
            WibechatRtc.SignalRequest.MessageCase.ANSWER,
            WibechatRtc.SignalRequest.MessageCase.SIMULATE,
            WibechatRtc.SignalRequest.MessageCase.LEAVE,
        )

        private fun iceServer(url: String) =
            PeerConnection.IceServer.builder(url).createIceServer()

        // more stun servers might slow it down, WebRTC recommends 3 max
        val DEFAULT_ICE_SERVERS = listOf(
            iceServer("stun:stun.l.google.com:19302"),
            iceServer("stun:stun1.l.google.com:19302"),
//            iceServer("stun:stun2.l.google.com:19302"),
//            iceServer("stun:stun3.l.google.com:19302"),
//            iceServer("stun:stun4.l.google.com:19302"),
        )
        private const val SIGNAL_CONNECT_TIMEOUT = 10000
        private val DATA_BLOB_REQUEST_TIMEOUT = 5.seconds
        const val CLOSE_REASON_NORMAL_CLOSURE = 1000
        const val CLOSE_REASON_PING_TIMEOUT = 3000
        const val CLOSE_REASON_WEBSOCKET_FAILURE = 3500
    }
}

@Suppress("EnumEntryName", "unused")
enum class ProtocolVersion(val value: Int) {
    v1(1),
    v2(2),
    v3(3),
    v4(4),
    v5(5),
    v6(6),
    v7(7),
    v8(8),
    v9(9),
    v10(10),
    v11(11),
    v12(12),

    // new leave request handling
    v13(13),
}

/**
 * The protocol version this SDK advertises to **peers** (other participants) for
 * client-to-client feature negotiation (RPC v2, etc.). Distinct from [ProtocolVersion],
 * which tracks the signaling protocol between client and server.
 *
 * Sent to the server during the join handshake via the `client_protocol` connection
 * query parameter and `ClientInfo.client_protocol`; the server then populates
 * `ParticipantInfo.client_protocol` for other peers in the room to read.
 */
@Suppress("unused")
enum class ClientProtocolVersion(val value: Int) {
    /** Initial client protocol. RPC v1 only (15 KB packet payload limit). */
    DEFAULT(0),

    /**
     * RPC v2: request and success-response payloads are carried over text data streams
     * instead of inline packets, lifting the 15 KB payload limit.
     */
    DATA_STREAM_RPC(1),
}

class ServerInfo(
    val edition: Edition,
    val version: Semver?,
) {
    enum class Edition {
        STANDARD,
        CLOUD,
        UNKNOWN
        ;

        companion object {
            fun fromProto(proto: WibechatModels.ServerInfo.Edition): Edition {
                return when (proto) {
                    WibechatModels.ServerInfo.Edition.Standard -> STANDARD
                    WibechatModels.ServerInfo.Edition.Cloud -> CLOUD
                    WibechatModels.ServerInfo.Edition.UNRECOGNIZED -> UNKNOWN
                }
            }
        }
    }
}
