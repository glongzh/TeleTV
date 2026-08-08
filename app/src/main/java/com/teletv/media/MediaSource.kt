package com.teletv.media

/**
 * A browsable media source — any chat/channel the user has joined. Identified by
 * [chatId] with a cached [title]. [isDefault] marks Saved Messages (the always-
 * available home source); [isProtected] marks chats whose media cannot be
 * downloaded/played and are therefore non-selectable.
 */
data class MediaSource(
    val chatId: Long,
    val title: String,
    val isProtected: Boolean = false,
    val isDefault: Boolean = false,
)
