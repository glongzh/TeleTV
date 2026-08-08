package com.teletv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.teletv.tdlib.TdlibClient
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.RandomAccessFile

/** Build the URI ExoPlayer uses to route a video file through [TdlibDataSource]. */
fun tdlibMediaUri(fileId: Int, size: Long): Uri =
    Uri.parse("tdlib://file?id=$fileId&size=$size")

/**
 * Media3 [DataSource] that reads a video out of TDLib's partially-downloaded file,
 * chasing the download so playback can start before the whole file arrives.
 *
 * - open(): request download from the requested byte offset (async; the file may
 *   not exist on disk yet, so it is opened lazily on first read).
 * - read(): wait until the position falls inside TDLib's contiguous downloaded
 *   region, then serve bytes up to that boundary.
 * - seek: ExoPlayer opens a fresh DataSpec at the new position, so open() re-targets
 *   the download offset.
 */
@UnstableApi
class TdlibDataSource(private val client: TdlibClient) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId: Int = 0
    private var totalSize: Long = 0
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var localPath: String? = null
    private var raf: RandomAccessFile? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = dataSpec.uri.getQueryParameter("id")?.toIntOrNull()
            ?: throw IOException("tdlib DataSpec missing file id: ${dataSpec.uri}")
        totalSize = dataSpec.uri.getQueryParameter("size")?.toLongOrNull() ?: -1L
        position = dataSpec.position
        transferInitializing(dataSpec)

        // Start (or re-target) the download at the requested offset.
        val file: TdApi.File = runBlocking {
            client.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, position, /* limit = */ 0, /* synchronous = */ false))
        }
        if (totalSize <= 0) {
            totalSize = if (file.size > 0) file.size else file.expectedSize
        }
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalSize - position
        }

        // local.path is empty until TDLib actually creates the file on disk (the
        // async DownloadFile above returns immediately), so the RandomAccessFile
        // is opened lazily in read() once awaitData() confirms bytes exist.
        localPath = file.local?.path?.takeIf { it.isNotEmpty() }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val availableEnd = awaitData(position) ?: return C.RESULT_END_OF_INPUT
        val canRead = minOf(length.toLong(), availableEnd - position, bytesRemaining).toInt()
        if (canRead <= 0) return C.RESULT_END_OF_INPUT

        val file = raf ?: openLocalFile()
        file.seek(position)
        val read = file.read(buffer, offset, canRead)
        if (read == -1) return C.RESULT_END_OF_INPUT

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    /**
     * Block until [pos] is inside TDLib's contiguous downloaded region and return the
     * absolute end offset of that region. Returns null when the download is complete and
     * [pos] is at/after end of file.
     *
     * State is re-queried via GetFile on cache miss AND after each quiet interval:
     * a fully downloaded file emits no updateFile events at all, so waiting on the
     * event stream alone would spuriously time out (black screen on relaunch).
     */
    private fun awaitData(pos: Long): Long? = runBlocking {
        var quietMs = 0L
        while (true) {
            val file = client.latestFile(fileId)
                ?: client.send<TdApi.File>(TdApi.GetFile(fileId))
            val local = file.local
            if (local != null) {
                if (!local.path.isNullOrEmpty()) localPath = local.path
                if (local.isDownloadingCompleted) {
                    // Whole file is on disk — everything up to its size is readable.
                    val size = if (file.size > 0) file.size else totalSize
                    return@runBlocking if (pos >= size) null else size
                }
                val start = local.downloadOffset
                val end = start + local.downloadedPrefixSize
                if (pos in start until end) return@runBlocking end
                // Our position is outside the region being filled — re-target the download.
                if (!local.isDownloadingActive || pos < start || pos >= end) {
                    client.send<TdApi.File>(
                        TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, pos, 0, false)
                    )
                }
            } else {
                client.send<TdApi.File>(
                    TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, pos, 0, false)
                )
            }
            // Wait for the next progress event, then loop to re-check. On a quiet
            // interval, fall through and re-poll GetFile; only give up after the
            // total budget so slow networks buffer instead of erroring instantly.
            if (client.awaitFileUpdate(fileId, POLL_INTERVAL_MS) == null) {
                quietMs += POLL_INTERVAL_MS
                if (quietMs >= MAX_WAIT_MS) {
                    throw IOException("Timed out waiting for TDLib bytes of file $fileId at $pos")
                }
            } else {
                quietMs = 0L
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /**
     * Open TDLib's partial file. Only called after awaitData() has confirmed the
     * requested bytes are on disk, at which point local.path is guaranteed set.
     */
    private fun openLocalFile(): RandomAccessFile {
        val path = localPath
            ?: throw IOException("TDLib has no local path for file $fileId")
        return RandomAccessFile(path, "r").also { raf = it }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val DOWNLOAD_PRIORITY = 32 // highest
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_WAIT_MS = 30_000L
    }
}

/** Factory so ExoPlayer can create a [TdlibDataSource] per playback. */
@UnstableApi
class TdlibDataSourceFactory(private val client: TdlibClient) : DataSource.Factory {
    override fun createDataSource(): DataSource = TdlibDataSource(client)
}
