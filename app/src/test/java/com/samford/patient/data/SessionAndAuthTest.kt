package com.samford.patient.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.AppContainer
import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.network.ApiResult
import com.samford.patient.testing.FakeMobileApi
import com.samford.patient.testing.Fixtures
import com.samford.patient.testing.TestContainers
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionAndAuthTest {
    private lateinit var api: FakeMobileApi
    private lateinit var c: AppContainer

    @Before
    fun setUp() {
        api = FakeMobileApi()
        c = TestContainers.create(ApplicationProvider.getApplicationContext(), api)
        api.setRecord("patient-a", Fixtures.record("patient-a", Fixtures.document(api, "rx-1", "visit-1", "patient-a", versions = 2)))
        api.addEvent("patient-a", "DOCUMENT_AVAILABLE", "rx-1", "visit-1", true, "type" to "PRESCRIPTION")
    }

    private suspend fun signIn(mobile: String = "9000000001") {
        val challenge = c.auth.requestOtp(mobile) as ApiResult.Ok
        assertThat(challenge.value.demoCode).isEqualTo("123456")
        assertThat(c.auth.verifyOtp(challenge.value.challengeId, mobile, challenge.value.demoCode!!)).isInstanceOf(ApiResult.Ok::class.java)
    }

    @Test
    fun signInStoresOnlyEncryptedTokenAndLinkedFamilyProfiles() = runTest {
        signIn()

        assertThat(c.tokens.token()).isEqualTo("token-abc")
        assertThat(c.tokens.session.first()!!.mobileMasked).isEqualTo("******0001")
        assertThat(c.database.records().profiles().map { it.id }).containsExactly("patient-a", "patient-child")
        assertThat(c.settings.current().activeProfileId).isEqualTo("patient-a")
    }

    @Test
    fun signInOpensTheAdultProfileEvenWhenAChildIsListedFirst() = runTest {
        api.profiles.sortBy { it.name }
        assertThat(api.profiles.first().profileId).isEqualTo("patient-child")

        signIn()
        assertThat(c.settings.current().activeProfileId).isEqualTo("patient-a")

        // Signing in again keeps the person the user last chose.
        c.settings.setActiveProfile("patient-child")
        signIn()
        assertThat(c.settings.current().activeProfileId).isEqualTo("patient-child")
    }

    @Test
    fun wrongCodeReportsAttemptsRemaining() = runTest {
        val challenge = c.auth.requestOtp("9000000001") as ApiResult.Ok
        val result = c.auth.verifyOtp(challenge.value.challengeId, "9000000001", "000000") as ApiResult.Err
        assertThat(result.error.code).isEqualTo("OTP_INVALID")
        assertThat(result.error.attemptsRemaining).isEqualTo(2)
        assertThat(c.tokens.token()).isNull()
    }

    @Test
    fun signOutRemovesRecordsFilesTokenAndPendingWork() = runTest {
        signIn()
        c.syncEngine.syncAll(force = true)
        val page = c.database.records().pages("rx-1", 2).first()
        val file = (c.records.pageFile(page) as BlobResult.Ready).file
        c.records.markViewed("rx-1", 2)
        c.appLock.enable("2468")
        val shareDir = File(c.context.cacheDir, "share").apply { mkdirs() }
        File(shareDir, "Samford-Prescription-v2.pdf").writeText("pdf")
        c.settings.setServerOverride("http://10.0.2.2:4320/api/mobile/v1/")
        assertThat(file.exists()).isTrue()

        c.signOut()

        assertThat(api.count("logout")).isEqualTo(1)
        assertThat(c.tokens.token()).isNull()
        assertThat(c.tokens.session.first()).isNull()
        val records = c.database.records()
        assertThat(records.profiles()).isEmpty()
        assertThat(records.document("rx-1")).isNull()
        assertThat(records.pages("rx-1", 2)).isEmpty()
        assertThat(records.unnotifiedFeedItems()).isEmpty()
        assertThat(c.database.acks().pending()).isEmpty()
        assertThat(c.database.sync().all()).isEmpty()
        assertThat(c.database.blobs().count()).isEqualTo(0)
        assertThat(file.exists()).isFalse()
        assertThat(shareDir.exists()).isFalse()
        val settings = c.settings.current()
        assertThat(settings.activeProfileId).isNull()
        assertThat(settings.appLockEnabled).isFalse()
        // Device preferences survive sign-out.
        assertThat(settings.serverOverride).isEqualTo("http://10.0.2.2:4320/api/mobile/v1/")
    }

    @Test
    fun aDifferentMobileOnTheSamePhoneNeverSeesThePreviousRecords() = runTest {
        signIn("9000000001")
        c.syncEngine.syncAll(force = true)
        assertThat(c.database.records().document("rx-1")).isNotNull()

        api.registeredMobiles += "9000000002"
        signIn("9000000002")

        assertThat(c.database.records().document("rx-1")).isNull()
    }

    @Test
    fun enteringSampleModeAndSigningInLaterReplacesSampleRecords() = runTest {
        c.enterSampleMode()
        assertThat(c.settings.current().sampleMode).isTrue()
        assertThat(c.database.records().profiles().all { it.isSample }).isTrue()
        assertThat(c.database.records().document("sd-1001-rx")!!.currentVersion).isEqualTo(2)

        signIn()

        assertThat(c.settings.current().sampleMode).isFalse()
        assertThat(c.database.records().profiles().none { it.isSample }).isTrue()
        assertThat(c.database.records().document("sd-1001-rx")).isNull()
    }

    @Test
    fun revokedDeviceWipesButExpiredTokenKeepsRecordsReadable() = runTest {
        signIn()
        c.syncEngine.syncAll(force = true)

        c.session.onServerSignedOut(revoked = false)
        assertThat(c.session.sessionExpired.value).isTrue()
        assertThat(c.database.records().document("rx-1")).isNotNull()

        c.session.onServerSignedOut(revoked = true)
        assertThat(c.database.records().document("rx-1")).isNull()
        assertThat(c.tokens.token()).isNull()
    }
}
