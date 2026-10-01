package dev.usix.companion.adapters.transport

import dev.usix.companion.application.RemoteConnectionControl
import dev.usix.companion.application.RemoteEndpointStore
import dev.usix.companion.domain.RemoteEndpoint
import dev.usix.companion.protocol.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*

/** Phone-initiated WSS. PKIX and default hostname checks are never disabled. */
class OutboundDeviceConnection(
    private val settings: RemoteEndpointStore,
    private val router: DeviceV2Router,
    private val deviceId: () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RemoteConnectionControl, AutoCloseable {
    @Volatile private var state = "disabled"
    @Volatile private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var client: OkHttpClient? = null
    private var generation = 0L
    private val slots = Semaphore(4)
    private val pending = AtomicInteger()
    init { router.remote = this }
    override fun status() = state

    override suspend fun configure(endpoint: RemoteEndpoint?) {
        endpoint?.let { validatedClient(it) }
        settings.save(endpoint)
        connect(endpoint)
    }
    suspend fun restore() {
        try { connect(settings.load()) } catch (_: Exception) { state = "configuration_required" }
    }
    @Synchronized private fun connect(endpoint: RemoteEndpoint?) {
        generation++
        reconnect?.cancel(); reconnect = null
        socket?.cancel(); socket = null
        client?.dispatcher?.executorService?.shutdown(); client?.connectionPool?.evictAll()
        if (endpoint == null) { state = "disabled"; return }
        val ownerGeneration = generation
        client = validatedClient(endpoint)
        state = "connecting"
        var lastSequence = 0L
        val seen = linkedSetOf<String>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ownerGeneration != generation) { webSocket.cancel(); return }
                state = "connected"
                webSocket.send(StrictJson.encode(mapOf("contractVersion" to DEVICE_CONTRACT, "kind" to "hello",
                    "deviceId" to deviceId(), "connectionId" to UUID.randomUUID().toString())))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try {
                    val data = StrictJson.objectValue(text, 131072)
                    DeviceV2Codec.fields(data, "contractVersion", "kind", "channelRequestId", "sequence", "path", "bearer", "body")
                    require(data["contractVersion"] == DEVICE_CONTRACT && data["kind"] == "request")
                    val requestId = DeviceV2Codec.id(data["channelRequestId"])
                    val sequence = DeviceV2Codec.integer(data["sequence"])
                    synchronized(seen) {
                        require(sequence > lastSequence && requestId !in seen)
                        lastSequence = sequence; seen.add(requestId)
                        if (seen.size > 128) seen.remove(seen.first())
                    }
                    require(ownerGeneration == generation)
                    data
                } catch (_: Exception) { webSocket.close(1008, "Invalid or replayed device request"); return }
                if (pending.incrementAndGet() > 16) {
                    pending.decrementAndGet(); webSocket.close(1008, "Device request queue exceeded"); return
                }
                scope.launch {
                    slots.withPermit {
                        val requestId = DeviceV2Codec.id(message["channelRequestId"])
                        val path = DeviceV2Codec.text(message["path"], 128)
                        val body = StrictJson.encode(DeviceV2Codec.obj(message["body"]))
                        val authorization = router.authorize(path, message["bearer"] as? String)
                        val response = when {
                            !path.startsWith("/v2/") || path == "/v2/admin/remote" -> DeviceV2Codec.failure("PermissionRequired", "Remote endpoint settings require local setup")
                            authorization == null -> DeviceV2Codec.failure("IdentityMismatch", "Paired session required", "401 Unauthorized")
                            else -> try { withTimeout(30_000) { router.route("POST", path, body, authorization) } }
                                catch (_: Exception) { DeviceV2Codec.failure("UnknownEffect", "Request interrupted; inspect its action receipt", "503 Service Unavailable") }
                        }
                        if (ownerGeneration == generation) webSocket.send(StrictJson.encode(mapOf("contractVersion" to DEVICE_CONTRACT,
                            "kind" to "response", "channelRequestId" to requestId, "status" to response.status.substringBefore(' ').toInt(),
                            "body" to StrictJson.objectValue(response.json, 1048576))))
                    }
                }.invokeOnCompletion { pending.decrementAndGet() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry() }
            private fun retry() {
                if (ownerGeneration != generation) return
                state = "offline"
                reconnect?.cancel()
                reconnect = scope.launch { delay(5_000); if (ownerGeneration == generation) connect(endpoint) }
            }
        }
        socket = client!!.newWebSocket(Request.Builder().url(endpoint.url)
            .header("Authorization", "Bearer " + endpoint.deviceBearer).header("X-Companion-Device-Id", deviceId()).build(), listener)
    }
    @Synchronized override fun close() {
        generation++; reconnect?.cancel(); socket?.cancel(); socket = null
        client?.dispatcher?.executorService?.shutdown(); client?.connectionPool?.evictAll(); state = "disabled"
        scope.coroutineContext.cancelChildren()
    }
    companion object {
        fun validatedClient(endpoint: RemoteEndpoint): OkHttpClient {
            val uri = URI(endpoint.url)
            require(uri.scheme == "wss" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path == "/v2/device")
            require(endpoint.deviceBearer.matches(Regex("[A-Za-z0-9_-]{32,128}")))
            val builder = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS)
            endpoint.trustedCertificatePem?.let { pem ->
                val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)))
                require(certificates.size == 1)
                val certificate = certificates.single() as X509Certificate
                certificate.checkValidity()
                val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("paired-endpoint", certificate) }
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
                val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                builder.sslSocketFactory(ssl.socketFactory, trust)
                builder.certificatePinner(CertificatePinner.Builder().add(uri.host, CertificatePinner.pin(certificate)).build())
            }
            return builder.build()
        }
    }
}
