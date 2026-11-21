package com.samford.patient.network

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.discovery.ManualServerResult
import com.samford.patient.core.discovery.ServerDirectory
import com.samford.patient.core.discovery.ServerSource
import com.samford.patient.core.discovery.ServerStatus
import com.samford.patient.core.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerDirectoryTest {
    private val server = MockWebServer()
    private var discoveryJson = ""
    private var metaMinVersion = 1
    private var metaUp = true
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/discovery.json" -> MockResponse.Builder().code(200).body(discoveryJson).build()
                "/api/mobile/v1/meta" -> if (metaUp) {
                    MockResponse.Builder().code(200).body(
                        """{"apiVersion":"1.0.0","serverVersion":"0.3","synthetic":true,"minDoctorAppVersion":1,"minPatientAppVersion":$metaMinVersion,"serverTime":"2026-11-21T00:00:00Z","docsUrl":"x"}""",
                    ).build()
                } else {
                    MockResponse.Builder().code(503).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val dir = Files.createTempDirectory("dir").toFile()
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(dir, "s.preferences_pb") })
    }

    @After
    fun tearDown() = server.close()

    private fun directory(versionCode: Int = 1, cleartext: Boolean = true) =
        ServerDirectory(settings, OkHttpClient(), server.url("/discovery.json").toString(), versionCode, cleartext)

    private fun discovery(status: String, base: String?, min: Int = 1) {
        discoveryJson = """{"schema":"samford-endpoint/1","status":"$status","apiBaseUrl":${base?.let { "\"$it\"" } ?: "null"},"minPatientAppVersion":$min,"synthetic":true,"message":"Public synthetic demo."}"""
    }

    @Test
    fun discoveryAddressIsValidatedWithMetaAndRemembered() = runTest {
        discovery("online", server.url("/api/mobile/v1").toString())
        val dir = directory()

        val state = dir.refresh()

        assertThat(state.status).isEqualTo(ServerStatus.ONLINE)
        assertThat(state.source).isEqualTo(ServerSource.DISCOVERY)
        assertThat(state.serverVersion).isEqualTo("0.3")
        assertThat(settings.current().lastGoodRoot).isEqualTo(server.url("/api/mobile/v1/").toString())
        assertThat(dir.root().toString()).endsWith("/api/mobile/v1/")
    }

    @Test
    fun lastGoodServerIsTriedBeforeDiscovery() = runTest {
        settings.setLastGoodRoot(server.url("/api/mobile/v1/").toString())
        discovery("offline", null)

        val state = directory().refresh()

        assertThat(state.source).isEqualTo(ServerSource.LAST_GOOD)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/mobile/v1/meta")
    }

    @Test
    fun offlineDiscoveryIsReportedWithTheOperatorMessage() = runTest {
        discovery("offline", null)
        val state = directory().refresh()
        assertThat(state.status).isEqualTo(ServerStatus.OFFLINE)
        assertThat(state.message).isEqualTo("Public synthetic demo.")
    }

    @Test
    fun tooOldAppIsToldToUpdate() = runTest {
        discovery("online", server.url("/api/mobile/v1").toString(), min = 5)
        assertThat(directory(versionCode = 1).refresh().status).isEqualTo(ServerStatus.UPDATE_REQUIRED)

        discovery("online", server.url("/api/mobile/v1").toString(), min = 1)
        metaMinVersion = 3
        assertThat(directory(versionCode = 1).refresh().status).isEqualTo(ServerStatus.UPDATE_REQUIRED)
    }

    @Test
    fun manualOverrideWinsAndIsNeverSilentlyReplaced() = runTest {
        val dir = directory()
        val result = dir.setManual(server.url("/").toString())
        assertThat(result).isInstanceOf(ManualServerResult.Saved::class.java)

        discovery("online", "https://elsewhere.example/api/mobile/v1")
        metaUp = false
        val state = dir.refresh()

        assertThat(state.source).isEqualTo(ServerSource.MANUAL)
        assertThat(state.status).isEqualTo(ServerStatus.UNREACHABLE)
        assertThat(state.root).isEqualTo(server.url("/api/mobile/v1/").toString())
    }

    @Test
    fun releaseBuildsRefusePlainHttpExceptLoopback() = runTest {
        val dir = directory(cleartext = false)
        assertThat(dir.setManual("http://192.168.1.20:4320")).isEqualTo(ManualServerResult.HttpsRequired)
        assertThat(dir.setManual("not a url at all ::")).isEqualTo(ManualServerResult.InvalidAddress)
    }

    @Test
    fun qrPayloadsAreRecognised() {
        val deepLink = ServerDirectory.parseQr("samfordpatient://connect?api=https%3A%2F%2Fx.trycloudflare.com%2Fapi%2Fmobile%2Fv1")!!
        assertThat(deepLink.apiRoot.toString()).isEqualTo("https://x.trycloudflare.com/api/mobile/v1/")
        val json = ServerDirectory.parseQr("""{"apiBaseUrl":"https://y.example/api/mobile/v1","status":"online"}""")!!
        assertThat(json.apiRoot.toString()).isEqualTo("https://y.example/api/mobile/v1/")
        val disc = ServerDirectory.parseQr("https://gist.githubusercontent.com/a/b/raw/samford-endpoint.json")!!
        assertThat(disc.discoveryUrl).endsWith("samford-endpoint.json")
        assertThat(ServerDirectory.parseQr("hello")).isNull()
    }
}
