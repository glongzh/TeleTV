package com.teletv.media.index

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Facet categories a tag can belong to. Stored as the enum name string. */
enum class TagCategory { TYPE, CODE, NAME, TERM, YEAR }

/**
 * One scanned media message, scoped to its source chat. [rawText] keeps the
 * original caption + file name so extraction rules can be re-run over the
 * stored corpus without re-hitting Telegram.
 *
 * Message ids are unique only within a chat, so the primary key is composite
 * (chatId, messageId) — the same id in two sources must not collide.
 */
@Entity(tableName = "indexed_message", primaryKeys = ["chatId", "messageId"])
data class IndexedMessage(
    val chatId: Long,
    val messageId: Long,
    val type: String, // "video" | "photo"
    val date: Int, // unix seconds, message send date
    val durationSec: Int, // 0 for photos
    val rawText: String,
    /** File size in bytes; 0 for photos and for rows indexed before this column. */
    val size: Long = 0L,
    /** File name alone, kept apart from [rawText] for split detection. */
    val fileName: String = "",
)

/**
 * Membership of one message in a detected split-video group. [groupId] is the
 * first part's message id, giving the group a stable identity for playback
 * progress and for deduping tag counts.
 */
@Entity(
    tableName = "part_group",
    primaryKeys = ["chatId", "messageId"],
    indices = [Index("chatId", "groupId")],
)
data class PartGroup(
    val chatId: Long,
    val messageId: Long,
    val groupId: Long,
    val partIndex: Int, // 1-based
)

/**
 * One tag value within a source and category. `source` is "parsed" for
 * everything this phase produces; a later phase can add "scraped" rows.
 * Tags are scoped per chat so different sources' identifiers never merge.
 */
@Entity(
    tableName = "tag",
    indices = [Index(value = ["chatId", "category", "value", "source"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val category: String, // TagCategory name
    val value: String, // normalized (lowercased latin) form used for identity
    val display: String, // original-cased form shown in the UI
    val source: String = SOURCE_PARSED,
) {
    companion object {
        const val SOURCE_PARSED = "parsed"
    }
}

/**
 * Inverted-index link: this tag occurs in this message. [chatId] is carried for
 * the join back to [IndexedMessage]; (tagId, messageId) stays unique because a
 * tagId already belongs to exactly one chat.
 */
@Entity(
    tableName = "message_tag",
    primaryKeys = ["tagId", "messageId"],
    indices = [Index("chatId", "messageId")],
)
data class MessageTag(
    val tagId: Long,
    val chatId: Long,
    val messageId: Long,
)

/** Per-source scan cursors and completion state, so a scan can resume/sync. */
@Entity(tableName = "scan_state")
data class ScanState(
    @PrimaryKey val chatId: Long,
    /** Highest (newest) messageId ever indexed for this source; sync stops here. */
    val newestScannedMessageId: Long = 0,
    /** Pagination cursor of the in-progress full scan (0 = start from newest). */
    val fullScanCursor: Long = 0,
    val completedFullScan: Boolean = false,
    /** Unix millis when the full scan completed; 0 if never. */
    val completedAt: Long = 0,
    /**
     * Extraction rules this source's tags were built with. When it trails the
     * current version the tags are rebuilt from the stored rows — locally, with
     * no re-paging — so a rules change reaches existing libraries.
     */
    val extractorVersion: Int = 0,
)

/**
 * Remembered playback position of one video, scoped to its source chat for the
 * same reason [IndexedMessage] is: message ids collide across chats.
 *
 * A [completed] row keeps [positionMs] at 0 — the grid still needs the row to
 * draw its watched marker, but there is nothing left to resume to. [updatedAt]
 * (unix millis) orders the retention sweep, evicting least-recently-watched.
 */
@Entity(
    tableName = "playback_progress",
    primaryKeys = ["chatId", "messageId"],
    indices = [Index("updatedAt")],
)
data class PlaybackProgress(
    val chatId: Long,
    val messageId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

/** A user-favorited source, pinned in the source picker. */
@Entity(tableName = "favorite_source")
data class FavoriteSource(
    @PrimaryKey val chatId: Long,
    val title: String,
    val addedAt: Long,
)

/** A tag plus its distinct-message count, as listed in the filter panel. */
data class TagWithCount(
    val id: Long,
    val category: String,
    val value: String,
    val display: String,
    val count: Int,
)
