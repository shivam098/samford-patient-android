package com.samford.patient.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samford.patient.AppContainer
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.OtpChallengeDto
import com.samford.patient.data.AuthRepository
import com.samford.patient.data.DemoNumber
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignInStep { MOBILE, CODE }

data class SignInUiState(
    val step: SignInStep = SignInStep.MOBILE,
    val mobile: String = "",
    val code: String = "",
    val challenge: OtpChallengeDto? = null,
    val busy: Boolean = false,
    val error: ApiError? = null,
    val resendAtMillis: Long = 0,
    val demoNumbers: List<DemoNumber> = AuthRepository.DEMO_NUMBERS,
) {
    val mobileValid: Boolean get() = AuthRepository.normalizeMobile(mobile) != null
    val codeValid: Boolean get() = code.length == AuthRepository.OTP_LENGTH
}

class SignInViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    // Same key while retrying the same request, so a lost response replays instead of re-sending.
    private var requestKey: Pair<String, String>? = null
    private var verifyKey: Triple<String, String, String>? = null

    fun onMobileChange(value: String) {
        _state.update { it.copy(mobile = value.filter(Char::isDigit).take(10), error = null) }
    }

    fun onCodeChange(value: String) {
        _state.update { it.copy(code = value.filter(Char::isDigit).take(AuthRepository.OTP_LENGTH), error = null) }
    }

    fun useDemoNumber(number: DemoNumber) = onMobileChange(number.mobile)

    fun useDemoCode() {
        _state.value.challenge?.demoCode?.let { onCodeChange(it) }
    }

    fun changeNumber() {
        _state.update { it.copy(step = SignInStep.MOBILE, code = "", challenge = null, error = null) }
    }

    fun requestCode() {
        val s = _state.value
        if (s.busy || !s.mobileValid) {
            if (!s.mobileValid) _state.update { it.copy(error = ApiError(ApiError.CODE_INVALID_MOBILE)) }
            return
        }
        val key = requestKey?.takeIf { it.first == s.mobile }?.second ?: UUID.randomUUID().toString()
        requestKey = s.mobile to key
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = container.auth.requestOtp(s.mobile, key)) {
                is ApiResult.Ok -> {
                    requestKey = null
                    val now = container.clock.now().toEpochMilli()
                    _state.update {
                        it.copy(
                            busy = false, step = SignInStep.CODE, challenge = r.value, code = "",
                            resendAtMillis = now + r.value.resendAfterSeconds * 1000L,
                        )
                    }
                }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.error) }
                ApiResult.NotModified -> _state.update { it.copy(busy = false) }
            }
        }
    }

    fun verify() {
        val s = _state.value
        val challenge = s.challenge ?: return
        if (s.busy || !s.codeValid) return
        val key = verifyKey?.takeIf { it.first == challenge.challengeId && it.second == s.code }?.third
            ?: UUID.randomUUID().toString()
        verifyKey = Triple(challenge.challengeId, s.code, key)
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = container.auth.verifyOtp(challenge.challengeId, s.mobile, s.code, key)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(busy = false) }
                    container.appScope.launch { container.onSignedIn() }
                }
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = r.error, code = "") }
                ApiResult.NotModified -> _state.update { it.copy(busy = false) }
            }
        }
    }
}
