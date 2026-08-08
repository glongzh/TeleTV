package com.teletv.media

import android.content.Context
import com.teletv.R
import com.teletv.media.index.GroupDetector
import com.teletv.tdlib.TdlibClient
import com.teletv.tdlib.TdlibException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the newest-first, photo/video-only media stream for any joined chat.
 * Every stream operation is parameterized by chat id; Saved Messages (chat id
 * equal to the signed-in user id) is just the default source.
 */
class MediaRepository(private val client: TdlibClient, private val context: Context) {

    private var cachedSavedChatId: Long = 0L
    private val savedMutex = Mutex()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** Resolve (and cache) the Saved Messages chat id. Idempotent. */
    suspend fun savedChatId(): Long {
        savedMutex.withLock {
            if (cachedSavedChatId != 0L) return cachedSavedChatId
            val me = client.send<TdApi.User>(TdApi.GetMe())
            // Saved Messages is the private chat with yourself; this ensures it exists.
            val chat = client.send<TdApi.Chat>(TdApi.CreatePrivateChat(me.id, false))
            cachedSavedChatId = chat.id
            return cachedSavedChatId
        }
    }

    /** The default Saved Messages source. */
    suspend fun savedSource(): MediaSource =
        MediaSource(
            chatId = savedChatId(),
            title = context.getString(R.string.saved_messages),
            isProtected = false,
            isDefault = true,
        )

    /**
     * Resolve a chat into a [MediaSource] (title + protected flag). Returns null
     * if the chat can't be resolved (e.g. a persisted favorite the user left).
     */
    suspend fun sourceForChat(chatId: Long): MediaSource? = runCatching {
        val chat = client.send<TdApi.Chat>(TdApi.GetChat(chatId))
        MediaSource(
            chatId = chat.id,
            title = chat.title.ifBlank { "Chat ${chat.id}" },
            isProtected = chat.hasProtectedContent,
            isDefault = chat.id == cachedSavedChatId,
        )
    }.getOrNull()

    /**
     * Recent joined chats from the main chat list, recency-ordered, excluding the
     * Saved Messages chat (shown separately as the pinned default).
     */
    suspend fun recentSources(limit: Int = 40): List<MediaSource> {
        val saved = savedChatId()
        // LoadChats populates the main list from the server; a 404 means fully loaded.
        runCatching { client.send<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), limit)) }
            .onFailure { if (it !is TdlibException || it.error.code != 404) throw it }
        val chats = client.send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), limit))
        return chats.chatIds.asList()
            .filter { it != saved }
            .mapNotNull { sourceForChat(it) }
    }

    /**
     * Load one page of media older than [fromMessageId] (0 = start from newest)
     * for [chatId].
     */
    suspend fun loadPage(chatId: Long, fromMessageId: Long, limit: Int = 30): MediaPage {
        val request = TdApi.SearchChatMessages(
            /* chatId = */ chatId,
            /* topicId = */ null,
            /* query = */ "",
            /* senderId = */ null,
            /* fromMessageId = */ fromMessageId,
            /* offset = */ 0,
            /* limit = */ limit,
            /* filter = */ TdApi.SearchMessagesFilterPhotoAndVideo()
        )
        val found = client.send<TdApi.FoundChatMessages>(request)
        val items = found.messages.mapNotNull { it.toMediaItem() }
        return MediaPage(items = items, nextFromMessageId = found.nextFromMessageId)
    }

    /**
     * One page of text-only metadata for the tag index scan of [chatId]. Reads
     * captions and file names but never touches thumbnail or media files.
     */
    suspend fun scanPage(chatId: Long, fromMessageId: Long, limit: Int = 100): ScanPage {
        val request = TdApi.SearchChatMessages(
            /* chatId = */ chatId,
            /* topicId = */ null,
            /* query = */ "",
            /* senderId = */ null,
            /* fromMessageId = */ fromMessageId,
            /* offset = */ 0,
            /* limit = */ limit,
            /* filter = */ TdApi.SearchMessagesFilterPhotoAndVideo()
        )
        val found = client.send<TdApi.FoundChatMessages>(request)
        val rows = found.messages.mapNotNull { msg ->
            when (val content = msg.content) {
                is TdApi.MessagePhoto -> ScanRow(
                    messageId = msg.id,
                    type = "photo",
                    date = msg.date,
                    durationSec = 0,
                    text = content.caption?.text?.trim().orEmpty(),
                )
                is TdApi.MessageVideo -> ScanRow(
                    messageId = msg.id,
                    type = "video",
                    date = msg.date,
                    durationSec = content.video.duration,
                    text = listOf(content.video.fileName.trim(), content.caption?.text?.trim().orEmpty())
                        .filter { it.isNotEmpty() }.joinToString(" "),
                    fileName = content.video.fileName.trim(),
                    size = content.video.video.let { if (it.size > 0) it.size else it.expectedSize },
                )
                else -> null
            }
        }
        return ScanPage(rows = rows, nextFromMessageId = found.nextFromMessageId, totalCount = found.totalCount)
    }

    /**
     * Fetch specific messages by id from [chatId] in one batch (filtered mode).
     * Ids missing from the chat (deleted) come back in [ByIdResult.missingIds].
     */
    suspend fun loadByIds(chatId: Long, messageIds: List<Long>): ByIdResult {
        if (messageIds.isEmpty()) return ByIdResult(emptyList(), emptyList())
        val result = client.send<TdApi.Messages>(
            TdApi.GetMessages(chatId, messageIds.toLongArray())
        )
        val byId = (result.messages ?: emptyArray()).filterNotNull()
            .mapNotNull { it.toMediaItem() }
            .associateBy { it.messageId }
        val items = ArrayList<MediaItem>(messageIds.size)
        val missing = ArrayList<Long>()
        for (id in messageIds) byId[id]?.let(items::add) ?: missing.add(id)
        return ByIdResult(items = items, missingIds = missing)
    }

    private fun formatDate(unixSeconds: Int): String =
        dateFormat.format(Date(unixSeconds * 1000L))

    /** Smallest photo size that is still crisp enough for a grid cell. */
    private fun thumbSizeOf(photo: TdApi.Photo): TdApi.PhotoSize? =
        photo.sizes.sortedBy { it.width }.let { sorted ->
            sorted.firstOrNull { it.width >= 320 } ?: sorted.lastOrNull()
        }

    /**
     * Fold a detected split group into one item: the newest part supplies the
     * cell's identity and artwork (so the group sits where the stream puts it),
     * while size and duration are summed and the parts are carried in play order.
     */
    fun mergeGroup(representative: MediaItem.Video, orderedParts: List<MediaItem.Video>): MediaItem.Video {
        if (orderedParts.size < 2) return representative
        val parts = orderedParts.map { it.parts.single() }
        return representative.copy(
            size = parts.sumOf { it.size },
            durationSec = parts.sumOf { it.durationSec },
            supportsStreaming = parts.all { it.supportsStreaming },
            // Title the cell with the shared name, not "…_000.mp4": the cell now
            // stands for the whole film, so naming it after one part is wrong.
            label = GroupDetector.displayName(orderedParts.first().label),
            parts = parts,
        )
    }

    private fun TdApi.Message.toMediaItem(): MediaItem? = when (val content = this.content) {
        is TdApi.MessagePhoto -> {
            content.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.let { size ->
                val caption = content.caption?.text?.trim().orEmpty()
                MediaItem.Photo(
                    messageId = id,
                    fileId = size.photo.id,
                    date = date,
                    minithumbBytes = content.photo.minithumbnail?.data,
                    thumbFileId = thumbSizeOf(content.photo)?.photo?.id,
                    label = caption.ifEmpty { formatDate(date) },
                    width = size.width,
                    height = size.height,
                )
            }
        }
        is TdApi.MessageVideo -> {
            val video = content.video
            val total = if (video.video.size > 0) video.video.size else video.video.expectedSize
            val name = video.fileName.trim()
            val caption = content.caption?.text?.trim().orEmpty()
            MediaItem.Video(
                messageId = id,
                fileId = video.video.id,
                date = date,
                minithumbBytes = video.minithumbnail?.data,
                thumbFileId = video.thumbnail?.file?.id,
                label = name.ifEmpty { caption.ifEmpty { formatDate(date) } },
                size = total,
                supportsStreaming = video.supportsStreaming,
                width = video.width,
                height = video.height,
                durationSec = video.duration,
                mimeType = video.mimeType,
                parts = listOf(
                    VideoPart(
                        messageId = id,
                        fileId = video.video.id,
                        size = total,
                        durationSec = video.duration,
                        supportsStreaming = video.supportsStreaming,
                    )
                ),
            )
        }
        else -> null
    }
}
