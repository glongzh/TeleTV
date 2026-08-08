package com.teletv.auth

import android.content.Context
import android.os.Build
import com.teletv.BuildConfig
import com.teletv.R
import com.teletv.tdlib.TdlibClient
import com.teletv.tdlib.TdlibException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Drives TDLib's authorization state machine and exposes an app-level [AuthState].
 *
 * Flow: waitTdlibParameters -> setTdlibParameters -> (waitPhoneNumber) ->
 *       requestQrCodeAuthentication -> waitOtherDeviceConfirmation (show QR) ->
 *       [waitPassword (2FA cloud password) ->] ready.
 *
 * Wrong-password retries never surface as new authorization updates — TDLib
 * stays in waitPassword and the failure is the request's error result — so this
 * repository owns the retry loop via [checkPassword] and re-emits [AuthState.WaitPassword].
 */
class AuthRepository(
    private val client: TdlibClient,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Initializing)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    // True while TDLib has a live connection to Telegram. The QR login token is
    // only refreshed (and confirmable) while connected; when this is false the
    // displayed QR is stale, so the QR screen shows a "can't connect" state.
    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private var disconnectJob: Job? = null

    private var parametersSent = false
    private var qrRequested = false

    fun start() {
        // Subscribe to future auth transitions...
        scope.launch {
            client.updates
                .filterIsInstance<TdApi.UpdateAuthorizationState>()
                .collect { handle(it.authorizationState) }
        }
        // Track connection health for the QR screen. Reconnecting immediately
        // clears the warning; a drop is debounced so brief transient reconnects
        // (normal during startup) don't flash a "can't connect" message.
        scope.launch {
            client.updates
                .filterIsInstance<TdApi.UpdateConnectionState>()
                .collect { handleConnection(it.state) }
        }
        // ...and reconcile the CURRENT state now, in case TDLib emitted the first
        // updateAuthorizationState before the collector above subscribed (replay=0
        // SharedFlow would otherwise drop it, leaving the app stuck on Initializing).
        scope.launch {
            runCatching { client.send<TdApi.AuthorizationState>(TdApi.GetAuthorizationState()) }
                .onSuccess { handle(it) }
                .onFailure { _state.value = AuthState.Error(it.message ?: "Failed to query auth state") }
        }
    }

    /**
     * Ready/Updating mean the connection is live (Updating = connected, syncing).
     * Any other state means we're not talking to Telegram; the QR can't refresh.
     */
    private fun handleConnection(state: TdApi.ConnectionState) {
        val live = state is TdApi.ConnectionStateReady || state is TdApi.ConnectionStateUpdating
        if (live) {
            disconnectJob?.cancel()
            disconnectJob = null
            _connected.value = true
        } else if (_connected.value && disconnectJob == null) {
            disconnectJob = scope.launch {
                delay(DISCONNECT_DEBOUNCE_MS)
                _connected.value = false
                disconnectJob = null
            }
        }
    }

    private suspend fun handle(authState: TdApi.AuthorizationState) {
        // The QR guard only dedupes repeats of the SAME waitPhoneNumber episode
        // (the update collector and the startup reconcile can both see it); any
        // other state ends the episode so a later waitPhoneNumber requests anew.
        if (authState !is TdApi.AuthorizationStateWaitPhoneNumber) qrRequested = false
        when (authState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> setParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> requestQr()
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _state.value = AuthState.ShowQr(authState.link)
            is TdApi.AuthorizationStateWaitCode ->
                _state.value = AuthState.Error("Unexpected SMS-code login; expected QR authentication.")
            is TdApi.AuthorizationStateWaitPassword ->
                _state.value = AuthState.WaitPassword(
                    hint = authState.passwordHint.takeIf { it.isNotBlank() }
                )
            is TdApi.AuthorizationStateReady -> _state.value = AuthState.Ready
            is TdApi.AuthorizationStateLoggingOut -> _state.value = AuthState.SigningOut
            is TdApi.AuthorizationStateClosed -> _state.value = AuthState.LoggedOut
            else -> Unit // Closing / others: no UI change
        }
    }

    /**
     * Sign out: send TDLib `LogOut`, which wipes its own local database/files and
     * drives the state machine through loggingOut -> closed. A failed request
     * (e.g. offline) is reported via [onError] and leaves the session intact.
     */
    fun logOut(onError: (String) -> Unit) {
        scope.launch {
            runCatching { client.send<TdApi.Ok>(TdApi.LogOut()) }
                .onFailure { e -> onError(e.message ?: "Sign-out failed") }
        }
    }

    /**
     * Submit the cloud password. Success advances via the normal
     * updateAuthorizationState -> Ready; failure re-emits [AuthState.WaitPassword]
     * with an error. Launches on the repository scope so an in-flight check
     * survives screen recomposition; duplicate submits are ignored.
     */
    fun checkPassword(password: String) {
        val current = _state.value as? AuthState.WaitPassword ?: return
        if (current.checking) return
        _state.value = current.copy(error = null, checking = true)
        scope.launch {
            runCatching { client.send<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password)) }
                .onFailure { e ->
                    val wrongPassword = e is TdlibException && e.error.message == "PASSWORD_HASH_INVALID"
                    val message = if (wrongPassword) {
                        context.getString(R.string.wrong_password)
                    } else {
                        e.message ?: context.getString(R.string.wrong_password)
                    }
                    // Only surface the failure if we're still waiting on a password.
                    val s = _state.value
                    if (s is AuthState.WaitPassword) {
                        _state.value = s.copy(error = message, checking = false)
                    }
                }
        }
    }

    /**
     * Abandon the password step and get a fresh QR (e.g. to sign in with a
     * different account). TDLib allows requestQrCodeAuthentication directly from
     * waitPassword and answers with a new waitOtherDeviceConfirmation.
     */
    fun restartLogin() {
        val current = _state.value as? AuthState.WaitPassword ?: return
        if (current.checking) return
        scope.launch {
            runCatching { client.send<TdApi.Ok>(TdApi.RequestQrCodeAuthentication(LongArray(0))) }
                .onFailure { e ->
                    val s = _state.value
                    if (s is AuthState.WaitPassword) {
                        _state.value = s.copy(error = e.message, checking = false)
                    }
                }
        }
    }

    private suspend fun setParameters() {
        if (parametersSent) return
        if (BuildConfig.TELEGRAM_API_ID == 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
            _state.value = AuthState.Error(
                "Missing Telegram credentials. Set TELEGRAM_API_ID and TELEGRAM_API_HASH in local.properties."
            )
            return
        }
        parametersSent = true
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(context.filesDir, "tdlib").absolutePath
            filesDirectory = File(context.filesDir, "tdlib-files").absolutePath
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = "en"
            deviceModel = "Android TV"
            systemVersion = Build.VERSION.RELEASE ?: "Android"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        runCatching { client.send<TdApi.Ok>(params) }
            .onFailure { _state.value = AuthState.Error(it.message ?: "setTdlibParameters failed") }
    }

    private suspend fun requestQr() {
        if (qrRequested) return
        qrRequested = true
        // Empty otherUserIds = normal QR login for this account.
        runCatching { client.send<TdApi.Ok>(TdApi.RequestQrCodeAuthentication(LongArray(0))) }
            .onFailure { _state.value = AuthState.Error(it.message ?: "requestQrCodeAuthentication failed") }
    }

    private companion object {
        const val DISCONNECT_DEBOUNCE_MS = 2500L
    }
}
