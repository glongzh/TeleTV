package com.teletv.analytics

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.posthog.PostHog
import com.posthog.PostHogPropertiesSanitizer
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.teletv.BuildConfig

/**
 * The app's single entry point to PostHog.
 *
 * Everything routes through here rather than calling [PostHog] directly, for two
 * reasons: the SDK stays optional (no `POSTHOG_API_KEY` in local.properties ->
 * [enabled] is false and every call below is a no-op, so a fresh checkout builds
 * and runs untouched), and one place owns what leaves the device.
 *
 * ## What is deliberately NOT sent
 * TeleTV browses a personal Telegram account, so titles, chat names, file names
 * and search text are the user's private data. Event properties are therefore
 * restricted to shapes and outcomes — counts, durations, lengths, booleans, error
 * codes — never content. The SDK is also never handed a Telegram user id: with
 * no [PostHog.identify] call, events stay on PostHog's anonymous device id.
 *
 * ## Network
 * PostHog talks straight to its own host; it does NOT go through the app's TDLib
 * proxy. On a network where Telegram is blocked, events simply queue and are
 * dropped once the queue fills — analytics never blocks or delays the UI.
 */
object Analytics {
    private const val TAG = "Analytics"
    private const val PREFS = "analytics"
    private const val KEY_OPT_OUT = "opt_out"

    @Volatile
    private var enabled = false

    private var prefs: SharedPreferences? = null

    /**
     * True when a key was compiled in. The settings screen hides the opt-out
     * entry when this is false — there is nothing to opt out of.
     */
    val isConfigured: Boolean get() = BuildConfig.POSTHOG_API_KEY.isNotBlank()

    /**
     * User's choice, persisted here rather than left to the SDK: PostHog stores
     * its own opt-out flag in preferences that [PostHog.reset] wipes, so a
     * sign-out would silently switch analytics back on.
     */
    var isOptedOut: Boolean
        get() = prefs?.getBoolean(KEY_OPT_OUT, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_OPT_OUT, value)?.apply()
            applyOptOut(value)
        }

    /** Call once, from `Application.onCreate`. Safe to call when unconfigured. */
    fun init(app: Application) {
        if (enabled) return
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiKey = BuildConfig.POSTHOG_API_KEY
        if (apiKey.isBlank()) {
            Log.i(TAG, "POSTHOG_API_KEY is not set - analytics disabled.")
            return
        }
        val config = PostHogAndroidConfig(apiKey = apiKey, host = BuildConfig.POSTHOG_HOST).apply {
            // $application_installed / _updated / _opened / _backgrounded.
            captureApplicationLifecycleEvents = true
            // Single-Activity Compose app: an Activity-level screen event would
            // only ever say "MainActivity". Screens are reported by [screen].
            captureScreenViews = false
            captureDeepLinks = false // leanback launcher only, no deep links
            // Session replay has no Compose-for-TV support and nothing to capture
            // on a 10-foot UI; it would also record the user's media on screen.
            sessionReplay = false
            // Honoured from the very first lifecycle event, before the
            // reconcile below has run.
            optOut = isOptedOut
            // Nothing in the app reads a flag, and both of these would fire a
            // blocking-ish network request during startup on a TV that may be
            // sitting behind a blocked link.
            preloadFeatureFlags = false
            remoteConfig = false
            debug = BuildConfig.DEBUG
            // Keeps debug runs from being mistaken for real usage in PostHog.
            propertiesSanitizer = PostHogPropertiesSanitizer { props ->
                props.apply { put("build_type", if (BuildConfig.DEBUG) "debug" else "release") }
            }
        }
        PostHogAndroid.setup(app, config)
        enabled = true
        // setup() lets the SDK's OWN stored opt-out flag override the config
        // value above. Ours is the authority, so restate it.
        applyOptOut(isOptedOut)
    }

    private fun applyOptOut(optOut: Boolean) {
        if (!enabled) return
        if (optOut) PostHog.optOut() else PostHog.optIn()
    }

    fun capture(event: String, properties: Map<String, Any>? = null) {
        if (!enabled) return
        PostHog.capture(event, properties = properties)
    }

    /** A `$screen` event. [name] is one of [Screens]. */
    fun screen(name: String) {
        if (!enabled) return
        PostHog.screen(name)
    }

    /**
     * Drop the anonymous id and start a new one. Called on sign-out so a TV that
     * changes hands does not stitch the next account's usage onto the previous
     * one's timeline.
     */
    fun reset() {
        if (!enabled) return
        PostHog.reset()
        // reset() clears the SDK's preferences, opt-out included. Signing out
        // must not re-enable analytics behind the user's back.
        applyOptOut(isOptedOut)
    }

    /** Push the queue now. Used at points where the process may not come back. */
    fun flush() {
        if (!enabled) return
        PostHog.flush()
    }
}

/**
 * Event names. Snake_case, `object`-`verb` past tense, matching PostHog's own
 * convention so custom events sort next to the SDK's `$`-prefixed ones.
 */
object Events {
    // Auth
    const val LOGIN_QR_SHOWN = "login_qr_shown"
    const val LOGIN_PASSWORD_REQUIRED = "login_password_required"
    const val LOGIN_COMPLETED = "login_completed"
    const val LOGIN_FAILED = "login_failed"
    const val SIGNED_OUT = "signed_out"

    // Playback
    const val PLAYBACK_STARTED = "playback_started"
    const val PLAYBACK_COMPLETED = "playback_completed"
    const val PLAYBACK_FAILED = "playback_failed"

    // Library
    const val SEARCH_PERFORMED = "search_performed"
    const val FILTER_APPLIED = "filter_applied"
    const val FILTER_CLEARED = "filter_cleared"
    const val SOURCE_SWITCHED = "source_switched"
    const val INDEX_SCAN_REQUESTED = "index_scan_requested"
}

/** `$screen_name` values passed to [Analytics.screen]. */
object Screens {
    const val GRID = "Grid"
    const val PLAYER = "Player"
    const val SETTINGS = "Settings"
    const val SOURCE_PICKER = "SourcePicker"
}
