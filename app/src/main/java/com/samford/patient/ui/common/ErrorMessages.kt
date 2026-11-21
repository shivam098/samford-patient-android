package com.samford.patient.ui.common

import android.content.res.Resources
import com.samford.patient.R
import com.samford.patient.core.network.ApiError
import kotlin.math.ceil

/**
 * Maps API failures to plain-language, localised sentences. Technical codes are never shown.
 * Unknown errors fall back to a generic sentence rather than raw server text, so the patient
 * always reads their chosen language.
 */
fun ApiError.toMessage(res: Resources): String {
    val waitMinutes = retryAfter?.let { ceil(it.inWholeSeconds / 60.0).toInt().coerceAtLeast(1) }
    return when (code) {
        ApiError.CODE_NETWORK -> res.getString(R.string.error_network)
        ApiError.CODE_NOT_CONFIGURED -> res.getString(R.string.error_no_server)
        ApiError.CODE_INTEGRITY -> res.getString(R.string.error_integrity)
        ApiError.CODE_INVALID_MOBILE -> res.getString(R.string.error_invalid_mobile)
        ApiError.CODE_INVALID_CODE -> res.getString(R.string.error_code_format)
        "MOBILE_NOT_REGISTERED" -> res.getString(R.string.error_mobile_unknown)
        "OTP_INVALID" -> attemptsRemaining?.let { res.getQuantityString(R.plurals.error_otp_invalid_attempts, it, it) }
            ?: res.getString(R.string.error_otp_invalid)
        "OTP_EXPIRED" -> res.getString(R.string.error_otp_expired)
        "OTP_LOCKED" -> waitMinutes?.let { res.getQuantityString(R.plurals.error_otp_locked_wait, it, it) }
            ?: res.getString(R.string.error_otp_locked)
        "OTP_RATE_LIMITED", "RATE_LIMITED" -> waitMinutes?.let { res.getQuantityString(R.plurals.error_rate_limited_wait, it, it) }
            ?: res.getString(R.string.error_rate_limited)
        "ACCESS_REVOKED", "DEVICE_SIGNED_OUT" -> res.getString(R.string.error_session_revoked)
        "APP_UPDATE_REQUIRED" -> res.getString(R.string.server_update_required_body)
        else -> when {
            isUnauthorized -> res.getString(R.string.error_session_expired)
            httpStatus == 429 -> res.getString(R.string.error_rate_limited)
            httpStatus == 403 -> res.getString(R.string.error_forbidden)
            httpStatus == 404 -> res.getString(R.string.error_not_found)
            httpStatus != null && httpStatus >= 500 -> res.getString(R.string.error_server)
            code == ApiError.CODE_BAD_RESPONSE -> res.getString(R.string.error_server)
            else -> res.getString(R.string.error_generic)
        }
    }
}
