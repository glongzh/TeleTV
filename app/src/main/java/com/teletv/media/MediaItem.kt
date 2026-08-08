package com.teletv.media

/** A single photo or video from the Saved Messages media stream. */
sealed interface MediaItem {
    val messageId: Long
    val fileId: Int

    /** Unix timestamp (seconds) the message was sent. */
    val date: Int

    /** Tiny embedded JPEG for an instant blurred grid placeholder; may be null. */
    val minithumbBytes: ByteArray?

    /** File id of the small thumbnail the grid downloads; null if unavailable. */
    val thumbFileId: Int?

    /** Text shown under the grid cell. */
    val label: String

    data class Photo(
        override val messageId: Long,
        override val fileId: Int,
        override val date: Int,
        override val minithumbBytes: ByteArray?,
        override val thumbFileId: Int?,
        override val label: String,
        val width: Int,
        val height: Int,
    ) : MediaItem

    /**
     * A video item. [parts] always has at least one entry: a lone video holds
     * exactly one, a detected split group holds its parts in order. [size] and
     * [durationSec] are the aggregate over [parts].
     */
    data class Video(
        override val messageId: Long,
        override val fileId: Int,
        override val date: Int,
        override val minithumbBytes: ByteArray?,
        override val thumbFileId: Int?,
        override val label: String,
        val size: Long,
        val supportsStreaming: Boolean,
        val width: Int,
        val height: Int,
        val durationSec: Int,
        val mimeType: String,
        val parts: List<VideoPart> = emptyList(),
    ) : MediaItem {
        /** Identity for playback progress: the first part, or the video itself. */
        val groupId: Long get() = parts.firstOrNull()?.messageId ?: messageId
        val isGroup: Boolean get() = parts.size > 1
    }
}

/** One file backing a [MediaItem.Video]; several of them make a split group. */
data class VideoPart(
    val messageId: Long,
    val fileId: Int,
    val size: Long,
    val durationSec: Int,
    val supportsStreaming: Boolean,
)

/** One page of media plus the cursor for requesting the next (older) page. */
data class MediaPage(
    val items: List<MediaItem>,
    val nextFromMessageId: Long,
)

/** Text-only metadata of one message, consumed by the tag index scan. */
data class ScanRow(
    val messageId: Long,
    val type: String, // "video" | "photo"
    val date: Int,
    val durationSec: Int,
    val text: String, // fileName + caption, trimmed and joined
    /** File name alone; split detection must not see the caption glued on. */
    val fileName: String = "",
    /** Bytes; 0 for photos. Corroborates split-part detection. */
    val size: Long = 0L,
)

/** One scanned page plus cursor and the chat's approximate total media count. */
data class ScanPage(
    val rows: List<ScanRow>,
    val nextFromMessageId: Long,
    val totalCount: Int,
)

/** Batch fetch-by-id result: found items in request order, plus ids now gone. */
data class ByIdResult(
    val items: List<MediaItem>,
    val missingIds: List<Long>,
)
