package com.teletv.proxy

/** The proxy protocols TeleTV supports entering. */
enum class ProxyKind { SOCKS5, MTPROTO }

/** A saved proxy as reported by TDLib, for display in the list. */
data class ProxyInfo(
    val id: Int,
    val kind: ProxyKind,
    val server: String,
    val port: Int,
    val enabled: Boolean,
)
