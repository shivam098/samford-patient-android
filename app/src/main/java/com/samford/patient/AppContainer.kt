package com.samford.patient

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.discovery.ServerDirectory
import com.samford.patient.core.network.DeviceIdProvider
import com.samford.patient.core.network.HttpMobileApi
import com.samford.patient.core.network.MobileApi
import com.samford.patient.core.notify.Notifier
import com.samford.patient.core.security.KeystoreTokenCipher
import com.samford.patient.core.security.TokenCipher
import com.samford.patient.core.security.TokenStore
import com.samford.patient.core.settings.LocaleStore
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.core.sync.SyncEngine
import com.samford.patient.core.sync.SyncScheduler
import com.samford.patient.core.time.Clock
import com.samford.patient.data.AppLockManager
import com.samford.patient.data.AuthRepository
import com.samford.patient.data.DocumentExporter
import com.samford.patient.data.PdfPageRasterizer
import com.samford.patient.data.Prefetcher
import com.samford.patient.data.RecordsRepository
import com.samford.patient.data.SessionManager
import com.samford.patient.data.SyncCoordinator
import com.samford.patient.data.SyncScheduling
import com.samford.patient.data.WorkCanceller
import com.samford.patient.data.sample.SampleDataInstaller
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(name = "session")
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manual dependency wiring. The app is small, so a hand-written container keeps cold start fast and
 * avoids an annotation-processing DI framework. Tests pass fakes through the constructor.
 */
class AppContainer(
    val context: Context,
    val clock: Clock = Clock.System,
    tokenCipher: TokenCipher = KeystoreTokenCipher(),
    apiOverride: MobileApi? = null,
    val database: SamfordDatabase = SamfordDatabase.create(context),
    sessionDataStore: DataStore<Preferences> = context.sessionStore,
    settingsDataStore: DataStore<Preferences> = context.settingsStore,
    enableBackgroundWork: Boolean = true,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val backgroundEnabled: Boolean = enableBackgroundWork

    val settings = SettingsStore(settingsDataStore)
    val tokens = TokenStore(sessionDataStore, tokenCipher)

    private val userAgent = "SamfordPatient/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}; build ${BuildConfig.VERSION_CODE})"

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val probeHttp: OkHttpClient = okHttp.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    val serverDirectory = ServerDirectory(
        settings = settings,
        http = probeHttp,
        defaultDiscoveryUrl = BuildConfig.DISCOVERY_URL,
        appVersionCode = BuildConfig.VERSION_CODE,
        allowCleartext = BuildConfig.DEBUG,
        clock = clock,
    )

    val api: MobileApi = apiOverride ?: HttpMobileApi(
        client = okHttp,
        roots = serverDirectory,
        tokens = tokens,
        devices = DeviceIdProvider { settings.installId() },
        userAgent = userAgent,
        appVersionCode = BuildConfig.VERSION_CODE,
        language = { LocaleStore.effectiveLocale(context).language },
        clock = clock,
    )

    private val shareDir = File(context.cacheDir, "share")
    private val renderDir = File(context.cacheDir, "pdf-pages")

    val blobStore = BlobStore(File(context.noBackupFilesDir, "blobs"), database.blobs(), api, clock)
    val notifier = Notifier(context)
    private val scheduler: SyncScheduler? = if (enableBackgroundWork) SyncScheduler(context) else null

    val session = SessionManager(
        db = database,
        blobs = blobStore,
        tokens = tokens,
        settings = settings,
        work = object : WorkCanceller {
            override fun cancelAll() {
                scheduler?.cancelAll()
            }
        },
        notifier = notifier,
        api = api,
        shareDir = shareDir,
        renderCacheDir = renderDir,
    )

    val syncEngine = SyncEngine(api, database, clock)

    val syncCoordinator = SyncCoordinator(
        engine = syncEngine,
        db = database,
        settings = settings,
        tokens = tokens,
        session = session,
        notifier = notifier,
        scheduling = scheduler?.let { s ->
            object : SyncScheduling {
                override fun scheduleRetry(atMillis: Long, nowMillis: Long) = s.scheduleRetry(atMillis, nowMillis)
                override fun schedulePrefetch() = s.schedulePrefetch()
            }
        },
        clock = clock,
        onUpdateRequired = { serverDirectory.reportUpdateRequired() },
    )

    val prefetcher = Prefetcher(database, blobStore, settings, tokens, clock)
    val auth = AuthRepository(api, tokens, settings, syncEngine, session)
    val records = RecordsRepository(database, syncEngine, blobStore, settings, clock)
    val exporter = DocumentExporter(blobStore, shareDir)
    val rasterizer = PdfPageRasterizer(renderDir)
    val sampleInstaller = SampleDataInstaller(database, blobStore, settings, clock)
    val appLock = AppLockManager(settings, clock)

    /** Server address carried by a QR deep link, waiting for the person to confirm it in Settings. */
    val pendingServerLink = MutableStateFlow<String?>(null)

    suspend fun onSignedIn() {
        scheduler?.schedulePeriodic(settings.installId())
        syncCoordinator.run(force = true, reason = "sign-in")
    }

    suspend fun ensureBackgroundSync() {
        if (tokens.isSignedIn() && !settings.current().sampleMode) {
            scheduler?.schedulePeriodic(settings.installId())
        }
    }

    suspend fun enterSampleMode() {
        session.wipeLocalData()
        settings.clearAccountData()
        sampleInstaller.install()
    }

    suspend fun signOut() = session.signOut(notifyServer = !settings.current().sampleMode)
}
