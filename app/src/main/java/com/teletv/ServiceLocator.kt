package com.teletv

import android.app.Application
import android.content.Intent
import com.teletv.analytics.Analytics
import com.teletv.analytics.Events
import com.teletv.auth.AuthRepository
import com.teletv.auth.AuthState
import com.teletv.media.AllowAllEntitlement
import com.teletv.media.MediaRepository
import com.teletv.media.SourceRepository
import com.teletv.media.index.PlaybackProgressDao
import com.teletv.media.index.TagIndexDao
import com.teletv.media.index.TagIndexDatabase
import com.teletv.media.index.TagIndexer
import com.teletv.proxy.ProxyRepository
import com.teletv.security.PinRepository
import com.teletv.storage.CachePrefs
import com.teletv.storage.StorageManager
import com.teletv.storage.StorageRepository
import com.teletv.tdlib.TdlibClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Minimal manual DI — one TDLib client and its repositories for the whole app.
 *
 * A closed TDLib client cannot be reused, so signing out (or an external session
 * revocation) rebuilds the client-bound objects as a new "generation": the old
 * generation's background work is cancelled, fresh instances replace the fields,
 * and [MainActivity] is relaunched so no ViewModel keeps a reference to a dead
 * repository. Generation-independent state (the Room index DB, the PIN store)
 * is built once and reused.
 */
object ServiceLocator {
    lateinit var client: TdlibClient
        private set
    lateinit var auth: AuthRepository
        private set
    lateinit var media: MediaRepository
        private set
    lateinit var sources: SourceRepository
        private set
    lateinit var pin: PinRepository
        private set
    lateinit var indexer: TagIndexer
        private set
    lateinit var tagDao: TagIndexDao
        private set
    lateinit var progressDao: PlaybackProgressDao
        private set
    lateinit var cachePrefs: CachePrefs
        private set
    lateinit var storage: StorageRepository
        private set
    lateinit var proxy: ProxyRepository
        private set
    private lateinit var storageManager: StorageManager

    private lateinit var app: Application

    /**
     * Outlives every ViewModel and TDLib generation. Exposed so teardown-time
     * writes (e.g. the player's final position flush in `onCleared`) survive the
     * cancellation of the scope that requested them.
     */
    lateinit var appScope: CoroutineScope
        private set
    private lateinit var db: TagIndexDatabase

    // Client-bound work runs in a per-generation scope, cancelled on rebuild.
    private var genScope: CoroutineScope? = null
    // Bumped each generation; stale auth-state watchers compare against it and no-op.
    private var generation = 0
    // Last auth state reported to analytics; see [reportAuth].
    private var lastAuthReported: String? = null

    fun init(app: Application, appScope: CoroutineScope) {
        this.app = app
        this.appScope = appScope
        // Generation-independent: reused across sign-ins.
        pin = PinRepository(app)
        cachePrefs = CachePrefs(app)
        db = TagIndexDatabase.open(app)
        tagDao = db.dao()
        progressDao = db.progressDao()
        buildGeneration()
    }

    /**
     * Wire a fresh TDLib client and its repositories, start auth, and register an
     * auth-state watcher for this generation. Idempotent field reassignment: each
     * call fully replaces the client-bound objects.
     */
    private fun buildGeneration() {
        val gen = ++generation
        val scope = CoroutineScope(SupervisorJob(appScope.coroutineContext[Job]) + Dispatchers.Default)
        genScope = scope

        client = TdlibClient()
        auth = AuthRepository(client, app, scope)
        media = MediaRepository(client, app)
        indexer = TagIndexer(media, tagDao, scope)
        sources = SourceRepository(media, db.sourceDao(), app, AllowAllEntitlement)
        storage = StorageRepository(client)
        storageManager = StorageManager(client, storage, cachePrefs, scope)
        proxy = ProxyRepository(client)
        auth.start()

        // The watcher lives on the OUTER app scope so it survives the genScope
        // cancellation it triggers on sign-out; the generation guard makes the
        // previous (now-stale) watcher inert once a new generation exists.
        appScope.launch {
            auth.state.collectLatest { state ->
                if (gen != generation) return@collectLatest
                reportAuth(state)
                when (state) {
                    is AuthState.Ready -> {
                        runCatching { indexer.autoStartSaved(media.savedChatId()) }
                        storageManager.start()
                    }
                    AuthState.LoggedOut -> onSessionEnded()
                    else -> Unit
                }
            }
        }
    }

    /**
     * Auth milestones, deduped by state CLASS: [AuthState.ShowQr] carries a link
     * that TDLib refreshes every ~30 s, so the StateFlow's own equality dedupe is
     * not enough to keep one login from emitting a dozen identical events.
     */
    private fun reportAuth(state: AuthState) {
        val key = state::class.simpleName ?: return
        if (key == lastAuthReported) return
        lastAuthReported = key
        when (state) {
            is AuthState.ShowQr -> Analytics.capture(Events.LOGIN_QR_SHOWN)
            is AuthState.WaitPassword -> Analytics.capture(Events.LOGIN_PASSWORD_REQUIRED)
            is AuthState.Ready -> Analytics.capture(Events.LOGIN_COMPLETED)
            // TDLib error codes only (PASSWORD_HASH_INVALID etc.) — no user data.
            is AuthState.Error -> Analytics.capture(Events.LOGIN_FAILED, mapOf("reason" to state.message))
            AuthState.LoggedOut -> Analytics.capture(Events.SIGNED_OUT)
            else -> Unit
        }
    }

    /**
     * Session end (in-app sign-out or external revocation): wipe local account
     * state, tear down this generation, rebuild a fresh one, and relaunch so the
     * app returns to QR login instead of dead-ending on a signed-out screen.
     */
    private suspend fun onSessionEnded() {
        // Local wipes first (all idempotent). TDLib wipes its own db/files on close.
        runCatching { indexer.clear() }
        runCatching { progressDao.clearAll() }
        sources.clearOnLogout()
        pin.clearPin()
        // New anonymous id: a TV that changes hands must not stitch the next
        // account's usage onto the previous one's timeline.
        Analytics.reset()
        // Cancel this generation's background work (old auth's collectors, scans).
        genScope?.cancel()
        genScope = null
        lastAuthReported = null // the next login is a new login, not a repeat
        buildGeneration()
        relaunchActivity()
    }

    /**
     * Relaunch into a new task, clearing the old one so its ViewModelStore — and
     * every reference it holds to the now-dead repositories — is discarded.
     */
    private fun relaunchActivity() {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
    }
}
