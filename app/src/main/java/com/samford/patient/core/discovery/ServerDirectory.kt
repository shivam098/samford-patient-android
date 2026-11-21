package com.samford.patient.core.discovery

import androidx.core.net.toUri
import com.samford.patient.core.network.ApiRoot
import com.samford.patient.core.network.ApiRootProvider
import com.samford.patient.core.network.DiscoveryDto
import com.samford.patient.core.network.MetaDto
import com.samford.patient.core.network.SamfordJson
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.core.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Minimum time between two failure-driven re-reads of the discovery document. */
private const val REDISCOVERY_INTERVAL_MS = 5 * 60_000L

enum class ServerStatus { UNKNOWN, CHECKING, ONLINE, OFFLINE, UNREACHABLE, UPDATE_REQUIRED }

enum class ServerSource { NONE, MANUAL, LAST_GOOD, DISCOVERY }

data class ServerState(
    val status: ServerStatus = ServerStatus.UNKNOWN,
    val root: String? = null,
    val source: ServerSource = ServerSource.NONE,
    val checkedAt: Long? = null,
    /** Operator message from the discovery document (shown verbatim; English only). */
    val message: String? = null,
    val discoveryUrl: String = "",
    val serverVersion: String? = null,
)

sealed interface ManualServerResult {
    data class Saved(val root: String, val reachable: Boolean) : ManualServerResult
    data object InvalidAddress : ManualServerResult
    data object HttpsRequired : ManualServerResult
    data class Unreachable(val root: String) : ManualServerResult
}

data class QrTarget(val apiRoot: HttpUrl?, val discoveryUrl: String?)

private sealed interface Probe {
    data class Ok(val meta: MetaDto) : Probe
    data object TooOld : Probe
    data object Failed : Probe
}

/**
 * Finds the hospital's mobile API. Order: a manual override (never silently replaced), then the last
 * server that answered, then the public discovery document. Every candidate is validated with
 * GET {apiBaseUrl}/meta before use (raw gist content is cached for minutes, so a stale address is
 * possible). Normal launches therefore cost one small request.
 */
class ServerDirectory(
    private val settings: SettingsStore,
    private val http: OkHttpClient,
    private val defaultDiscoveryUrl: String,
    private val appVersionCode: Int,
    private val allowCleartext: Boolean,
    private val clock: Clock = Clock.System,
    private val json: Json = SamfordJson,
) : ApiRootProvider {

    private val _state = MutableStateFlow(ServerState(discoveryUrl = defaultDiscoveryUrl))
    val state: StateFlow<ServerState> = _state.asStateFlow()

    @Volatile
    private var cachedRoot: HttpUrl? = null
    private val refreshLock = Mutex()

    override suspend fun root(): HttpUrl? {
        cachedRoot?.let { return it }
        val s = settings.current()
        return (s.serverOverride ?: s.lastGoodRoot)?.toHttpUrlOrNull()?.also { cachedRoot = it }
    }

    suspend fun refresh(): ServerState = refreshLock.withLock {
        val s = settings.current()
        val discoveryUrl = s.discoveryUrlOverride ?: defaultDiscoveryUrl
        _state.update { it.copy(status = ServerStatus.CHECKING, discoveryUrl = discoveryUrl) }

        s.serverOverride?.toHttpUrlOrNull()?.let { manual ->
            return@withLock when (val p = probe(manual)) {
                is Probe.Ok -> publish(ServerStatus.ONLINE, manual, ServerSource.MANUAL, discoveryUrl, version = p.meta.serverVersion)
                Probe.TooOld -> publish(ServerStatus.UPDATE_REQUIRED, manual, ServerSource.MANUAL, discoveryUrl)
                Probe.Failed -> publish(ServerStatus.UNREACHABLE, manual, ServerSource.MANUAL, discoveryUrl)
            }
        }

        s.lastGoodRoot?.toHttpUrlOrNull()?.let { last ->
            when (val p = probe(last)) {
                is Probe.Ok -> return@withLock publish(ServerStatus.ONLINE, last, ServerSource.LAST_GOOD, discoveryUrl, version = p.meta.serverVersion)
                Probe.TooOld -> return@withLock publish(ServerStatus.UPDATE_REQUIRED, last, ServerSource.LAST_GOOD, discoveryUrl)
                Probe.Failed -> Unit
            }
        }

        val discovery = fetchDiscovery(discoveryUrl)
        if (discovery != null) {
            val minimum = (discovery.minPatientAppVersion as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
            val root = discovery.apiBaseUrl?.let { ApiRoot.normalize(it) }
            if (minimum != null && minimum > appVersionCode) {
                return@withLock publish(ServerStatus.UPDATE_REQUIRED, root, ServerSource.DISCOVERY, discoveryUrl, discovery.message)
            }
            if (discovery.status != null && discovery.status != "online") {
                return@withLock publish(ServerStatus.OFFLINE, s.lastGoodRoot?.toHttpUrlOrNull(), ServerSource.DISCOVERY, discoveryUrl, discovery.message)
            }
            if (root != null) {
                when (val p = probe(root)) {
                    is Probe.Ok -> {
                        settings.setLastGoodRoot(root.toString())
                        return@withLock publish(ServerStatus.ONLINE, root, ServerSource.DISCOVERY, discoveryUrl, discovery.message, p.meta.serverVersion)
                    }
                    Probe.TooOld -> return@withLock publish(ServerStatus.UPDATE_REQUIRED, root, ServerSource.DISCOVERY, discoveryUrl, discovery.message)
                    Probe.Failed -> Unit
                }
            }
        }
        publish(ServerStatus.UNREACHABLE, s.lastGoodRoot?.toHttpUrlOrNull(), ServerSource.NONE, discoveryUrl, discovery?.message)
    }

    @Volatile
    private var lastRediscoveryAt: Long? = null

    /**
     * Re-reads the server address after repeated network or 5xx failures. The demo tunnel's address
     * changes whenever it restarts and the discovery document follows within minutes, so a running
     * app must not keep calling a dead address until its next launch. Runs at most once every five
     * minutes; a manual address is re-checked but never replaced. Returns true when a different
     * server is now in use and answered GET /meta.
     */
    suspend fun rediscoverAfterFailure(): Boolean {
        val now = clock.now().toEpochMilli()
        lastRediscoveryAt?.let { if (now - it < REDISCOVERY_INTERVAL_MS) return false }
        lastRediscoveryAt = now
        val before = root()?.toString()
        val state = refresh()
        return state.status == ServerStatus.ONLINE && state.source != ServerSource.MANUAL &&
            state.root != null && state.root != before
    }

    /** Marks the app as too old after any 426 APP_UPDATE_REQUIRED response. */
    fun reportUpdateRequired() {
        _state.update { it.copy(status = ServerStatus.UPDATE_REQUIRED) }
    }

    suspend fun setManual(input: String, saveEvenIfUnreachable: Boolean = false): ManualServerResult {
        val root = ApiRoot.normalize(input) ?: return ManualServerResult.InvalidAddress
        if (!isTransportAllowed(root)) return ManualServerResult.HttpsRequired
        val probe = probe(root)
        val ok = probe is Probe.Ok
        if (!ok && !saveEvenIfUnreachable) return ManualServerResult.Unreachable(root.toString())
        settings.setServerOverride(root.toString())
        if (ok) settings.setLastGoodRoot(root.toString())
        cachedRoot = root
        val status = when (probe) {
            is Probe.Ok -> ServerStatus.ONLINE
            Probe.TooOld -> ServerStatus.UPDATE_REQUIRED
            Probe.Failed -> ServerStatus.UNREACHABLE
        }
        publish(status, root, ServerSource.MANUAL, _state.value.discoveryUrl, version = (probe as? Probe.Ok)?.meta?.serverVersion)
        return ManualServerResult.Saved(root.toString(), ok)
    }

    suspend fun setDiscoveryUrl(url: String?) {
        settings.setDiscoveryOverride(url?.takeIf { it.isNotBlank() })
        refresh()
    }

    suspend fun clearManual(): ServerState {
        settings.setServerOverride(null)
        cachedRoot = null
        return refresh()
    }

    /** Applies a scanned QR code or confirmed deep link. Returns null when it is not a Samford server code. */
    suspend fun applyQr(payload: String): ManualServerResult? {
        val target = parseQr(payload) ?: return null
        return when {
            target.apiRoot != null -> setManual(target.apiRoot.toString())
            target.discoveryUrl != null -> {
                settings.setDiscoveryOverride(target.discoveryUrl)
                settings.setServerOverride(null)
                cachedRoot = null
                val state = refresh()
                if (state.status == ServerStatus.ONLINE && state.root != null) {
                    ManualServerResult.Saved(state.root, true)
                } else {
                    ManualServerResult.Unreachable(target.discoveryUrl)
                }
            }
            else -> null
        }
    }

    private fun publish(
        status: ServerStatus,
        root: HttpUrl?,
        source: ServerSource,
        discoveryUrl: String,
        message: String? = null,
        version: String? = null,
    ): ServerState {
        if (status == ServerStatus.ONLINE && root != null) cachedRoot = root
        val next = ServerState(status, root?.toString(), source, clock.now().toEpochMilli(), message, discoveryUrl, version)
        _state.value = next
        return next
    }

    private fun isTransportAllowed(root: HttpUrl): Boolean =
        root.isHttps || allowCleartext || root.host in LOOPBACK_HOSTS

    private suspend fun probe(root: HttpUrl): Probe {
        if (!isTransportAllowed(root)) return Probe.Failed
        val url = root.resolve("meta") ?: return Probe.Failed
        val meta = getJson(url, MetaDto.serializer()) ?: return Probe.Failed
        val minimum = meta.minPatientAppVersion
        return if (minimum != null && minimum > appVersionCode) Probe.TooOld else Probe.Ok(meta)
    }

    private suspend fun fetchDiscovery(url: String): DiscoveryDto? =
        url.toHttpUrlOrNull()?.let { getJson(it, DiscoveryDto.serializer()) }

    private suspend fun <T> getJson(url: HttpUrl, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
        runInterruptible(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Samford-App", "patient-android")
                .header("X-Samford-App-Version", appVersionCode.toString())
                .build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) null else json.decodeFromString(serializer, r.body.string())
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /**
         * Accepted QR/deep-link payloads: `samfordpatient://connect?api=<url>` or `?discovery=<url>`,
         * a discovery-style JSON object with `apiBaseUrl`, a discovery URL ending in `.json`, or a
         * plain server address.
         */
        fun parseQr(payload: String): QrTarget? {
            val text = payload.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("samfordpatient://", ignoreCase = true)) {
                val uri = text.toUri()
                val api = uri.getQueryParameter("api")?.let { ApiRoot.normalize(it) }
                val discovery = uri.getQueryParameter("discovery")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                return if (api == null && discovery == null) null else QrTarget(api, discovery)
            }
            if (text.startsWith("{")) {
                val dto = runCatching { SamfordJson.decodeFromString(DiscoveryDto.serializer(), text) }.getOrNull()
                val api = dto?.apiBaseUrl?.let { ApiRoot.normalize(it) } ?: return null
                return QrTarget(api, null)
            }
            if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
                val url = text.toHttpUrlOrNull() ?: return null
                return if (url.encodedPath.endsWith(".json")) QrTarget(null, url.toString()) else QrTarget(ApiRoot.normalize(text), null)
            }
            return null
        }
    }
}
