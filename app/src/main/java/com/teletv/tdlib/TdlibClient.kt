package com.teletv.tdlib

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/** Thrown when TDLib returns a [TdApi.Error] for a request. */
class TdlibException(val error: TdApi.Error) :
    Exception("TDLib error ${error.code}: ${error.message}")

/**
 * Thin coroutine/Flow wrapper around TDLib's async JNI interface.
 *
 * - Outgoing requests are `suspend` functions ([send]) that resolve when TDLib answers.
 * - Incoming updates are exposed as a shared [updates] flow (fan-out to many collectors).
 * - The latest snapshot of every file is cached ([latestFile]) so the video DataSource
 *   can poll download progress without missing edge-triggered updates.
 */
class TdlibClient {

    // Native libs must load before Client.create() calls any native method.
    // Declared first so it runs before the `client` property initializer.
    private val nativeReady: Boolean = NativeLibs.ensureLoaded()

    // IMPORTANT initialization order: Client.create() starts its receive thread
    // immediately and can invoke onIncoming() before this constructor finishes.
    // Every field that onIncoming() touches (_updates, fileCache) MUST therefore
    // be initialized BEFORE `client` — otherwise the first updates (including the
    // critical initial UpdateAuthorizationState) hit null fields and are dropped.
    private val _updates = MutableSharedFlow<TdApi.Update>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val fileCache = ConcurrentHashMap<Int, TdApi.File>()

    private val client: Client = Client.create(
        /* updateHandler = */ { obj -> onIncoming(obj) },
        /* updateExceptionHandler = */ { e -> e.printStackTrace() },
        /* defaultExceptionHandler = */ { e -> e.printStackTrace() }
    )

    init {
        // Quiet TDLib's native logging. Client.execute runs synchronous, log-only functions.
        Client.execute(TdApi.SetLogVerbosityLevel(1))
    }

    private fun onIncoming(obj: TdApi.Object) {
        if (obj is TdApi.Update) {
            if (obj is TdApi.UpdateFile) fileCache[obj.file.id] = obj.file
            _updates.tryEmit(obj)
        }
    }

    /** Latest known snapshot of a file, or null if none seen yet. */
    fun latestFile(fileId: Int): TdApi.File? = fileCache[fileId]

    /**
     * Send a TDLib request and suspend until its result arrives.
     * Throws [TdlibException] if TDLib answers with an error.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<*>): T {
        val deferred = CompletableDeferred<TdApi.Object>()
        client.send(function) { result -> deferred.complete(result) }
        val result = deferred.await()
        if (result is TdApi.Error) throw TdlibException(result)
        // Keep the file snapshot cache fresh from direct responses too: a fully
        // downloaded file emits no further updateFile events, so request results
        // (DownloadFile/GetFile) may be the ONLY source of its current state.
        if (result is TdApi.File) fileCache[result.id] = result
        return result as T
    }

    /**
     * Suspend until the next [TdApi.UpdateFile] for [fileId], or return null after [timeoutMs].
     * Used by the streaming DataSource to wait for more downloaded bytes.
     */
    suspend fun awaitFileUpdate(fileId: Int, timeoutMs: Long): TdApi.File? =
        withTimeoutOrNull(timeoutMs) {
            updates.filterIsInstance<TdApi.UpdateFile>().first { it.file.id == fileId }.file
        }

    fun close() {
        client.send(TdApi.Close()) { }
    }
}

/**
 * Loads the TDLib native libraries (from src/main/jniLibs) in dependency order.
 * libtdjni links against Telegram X's renamed OpenSSL libs (libcryptox / libsslx).
 */
private object NativeLibs {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!loaded) {
            System.loadLibrary("cryptox")
            System.loadLibrary("sslx")
            System.loadLibrary("tdjni")
            loaded = true
        }
        return true
    }
}
