package com.teletv.auth

/** App-level view of TDLib's authorization state machine. */
sealed interface AuthState {
    /** TDLib is starting up / no decision yet. */
    data object Initializing : AuthState

    /** Show this login link as a QR code for the phone to scan. */
    data class ShowQr(val link: String) : AuthState

    /**
     * QR scan confirmed, but the account has a cloud password (2FA).
     * [hint] is the user's password hint (null when unset), [error] the last
     * failed attempt's message, [checking] true while a submission is in flight.
     */
    data class WaitPassword(
        val hint: String? = null,
        val error: String? = null,
        val checking: Boolean = false,
    ) : AuthState

    /** Authenticated and ready to load media. */
    data object Ready : AuthState

    /** Unrecoverable (missing credentials, TDLib error). */
    data class Error(val message: String) : AuthState

    /** Log-out in progress: TDLib is closing the session (in-app sign-out or
     *  external revocation). Transient — resolves to [LoggedOut] when closed. */
    data object SigningOut : AuthState

    /** Session closed. Transient: the app clears local account state, rebuilds the
     *  TDLib client, and relaunches into QR login — not a terminal screen. */
    data object LoggedOut : AuthState
}
