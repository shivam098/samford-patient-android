package com.samford.patient.data

import android.os.Build
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.MobileApi
import com.samford.patient.core.network.OtpChallengeDto
import com.samford.patient.core.network.OtpVerifyBody
import com.samford.patient.core.network.PatientSessionDto
import com.samford.patient.core.network.ProfileDto
import com.samford.patient.core.security.TokenStore
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.core.sync.SyncEngine
import java.util.UUID

data class DemoNumber(val mobile: String, val label: String)

/**
 * Mobile + OTP sign-in. There is no SMS provider in this synthetic demo, so the server returns the
 * code and the app shows it, clearly labelled as a demo code.
 */
class AuthRepository(
    private val api: MobileApi,
    private val tokens: TokenStore,
    private val settings: SettingsStore,
    private val engine: SyncEngine,
    private val session: SessionManager,
) {
    suspend fun requestOtp(mobile: String, idempotencyKey: String = UUID.randomUUID().toString()): ApiResult<OtpChallengeDto> {
        val normalized = normalizeMobile(mobile) ?: return ApiResult.Err(ApiError(ApiError.CODE_INVALID_MOBILE))
        return api.requestOtp(normalized, settings.installId(), idempotencyKey)
    }

    suspend fun verifyOtp(
        challengeId: String,
        mobile: String,
        code: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): ApiResult<PatientSessionDto> {
        val normalized = normalizeMobile(mobile) ?: return ApiResult.Err(ApiError(ApiError.CODE_INVALID_MOBILE))
        val digits = code.filter { it.isDigit() }
        if (digits.length != OTP_LENGTH) return ApiResult.Err(ApiError(ApiError.CODE_INVALID_CODE))
        val deviceId = settings.installId()
        val body = OtpVerifyBody(
            challengeId = challengeId,
            code = digits,
            deviceId = deviceId,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80),
        )
        val result = api.verifyOtp(body, idempotencyKey)
        if (result is ApiResult.Ok) establish(result.value, normalized, deviceId)
        return result
    }

    private suspend fun establish(dto: PatientSessionDto, mobile: String, deviceId: String) {
        val hint = BlobStore.sha256Hex("$deviceId:$mobile".toByteArray())
        val previous = tokens.mobileHint()
        // A different person, or leaving offline sample mode: never mix records on one phone.
        if (settings.current().sampleMode || (previous != null && previous != hint)) {
            session.wipeLocalData()
            settings.clearAccountData()
        }
        tokens.save(
            token = dto.token,
            accountId = dto.account?.accountId,
            mobileMasked = dto.account?.mobileMasked ?: maskMobile(mobile),
            mobileHint = hint,
            expiresAt = dto.expiresAt,
        )
        engine.storeProfiles(dto.profiles)
        settings.setSampleMode(false)
        val keep = settings.current().activeProfileId?.takeIf { id -> dto.profiles.any { it.profileId == id } }
        settings.setActiveProfile(keep ?: defaultProfile(dto.profiles)?.profileId)
        session.clearExpired()
    }

    companion object {
        const val OTP_LENGTH = 6
        private const val ADULT_AGE = 18

        /**
         * The profile shown first after sign-in. The contract does not say which family member owns
         * the mobile, and the server lists profiles by name, so prefer the first adult (most likely
         * the person holding the phone) over a child who happens to sort first.
         */
        fun defaultProfile(profiles: List<ProfileDto>): ProfileDto? =
            profiles.firstOrNull { (it.age ?: 0) >= ADULT_AGE } ?: profiles.firstOrNull()

        /** Seeded synthetic mobiles on every fresh Samford mock backend (no real numbers). */
        val DEMO_NUMBERS = listOf(
            DemoNumber("9000000001", "Demo patient A and Demo child A (earlier handwritten visit, dispensed prescription, lab result)"),
            DemoNumber("9000000002", "Demo patient B (prescription amended to version 2)"),
            DemoNumber("9000000003", "Demo patient C"),
        )

        /** Accepts 10-digit Indian mobiles, optionally with +91/0 and spaces. */
        fun normalizeMobile(input: String): String? {
            var digits = input.filter { it.isDigit() }
            if (digits.length == 12 && digits.startsWith("91")) digits = digits.drop(2)
            if (digits.length == 11 && digits.startsWith("0")) digits = digits.drop(1)
            return digits.takeIf { it.length == 10 && it[0] in '6'..'9' }
        }

        fun maskMobile(mobile: String): String =
            if (mobile.length >= 4) "******" + mobile.takeLast(4) else "******"
    }
}
