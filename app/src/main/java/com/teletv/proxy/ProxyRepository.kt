package com.teletv.proxy

import com.teletv.tdlib.TdlibClient
import org.drinkless.tdlib.TdApi

/**
 * Wraps TDLib's proxy management. TDLib persists proxies in its own database and
 * applies the enabled one to every connection attempt, so this repository holds
 * no state of its own: [list] re-queries `GetProxies` as the single source of
 * truth, and it works in any authorization state (proxies can be set before login).
 */
class ProxyRepository(private val client: TdlibClient) {

    suspend fun list(): List<ProxyInfo> =
        client.send<TdApi.AddedProxies>(TdApi.GetProxies()).proxies.mapNotNull { it.toInfo() }

    suspend fun addSocks5(server: String, port: Int, username: String, password: String, enable: Boolean) {
        add(buildSocks5(server, port, username, password), enable)
    }

    suspend fun addMtproto(server: String, port: Int, secret: String, enable: Boolean) {
        add(buildMtproto(server, port, secret), enable)
    }

    suspend fun enable(id: Int) {
        client.send<TdApi.Ok>(TdApi.EnableProxy(id))
    }

    /** Disable every proxy — i.e. use a direct connection. */
    suspend fun disableAll() {
        client.send<TdApi.Ok>(TdApi.DisableProxy())
    }

    suspend fun remove(id: Int) {
        client.send<TdApi.Ok>(TdApi.RemoveProxy(id))
    }

    /** Ping a saved proxy by id; returns latency in seconds. Throws on failure. */
    suspend fun ping(id: Int): Double {
        val proxy = client.send<TdApi.AddedProxies>(TdApi.GetProxies())
            .proxies.firstOrNull { it.id == id }?.proxy
            ?: error("Proxy not found")
        return client.send<TdApi.Seconds>(TdApi.PingProxy(proxy)).seconds
    }

    /** Ping an unsaved draft (Test-before-save). Returns latency in seconds. */
    suspend fun pingSocks5(server: String, port: Int, username: String, password: String): Double =
        client.send<TdApi.Seconds>(TdApi.PingProxy(buildSocks5(server, port, username, password))).seconds

    suspend fun pingMtproto(server: String, port: Int, secret: String): Double =
        client.send<TdApi.Seconds>(TdApi.PingProxy(buildMtproto(server, port, secret))).seconds

    private suspend fun add(proxy: TdApi.Proxy, enable: Boolean) {
        client.send<TdApi.AddedProxy>(TdApi.AddProxy(proxy, enable, ""))
    }

    private fun buildSocks5(server: String, port: Int, username: String, password: String) =
        TdApi.Proxy(server, port, TdApi.ProxyTypeSocks5(username, password))

    private fun buildMtproto(server: String, port: Int, secret: String) =
        TdApi.Proxy(server, port, TdApi.ProxyTypeMtproto(secret))

    private fun TdApi.AddedProxy.toInfo(): ProxyInfo? {
        val kind = when (proxy.type) {
            is TdApi.ProxyTypeSocks5 -> ProxyKind.SOCKS5
            is TdApi.ProxyTypeMtproto -> ProxyKind.MTPROTO
            else -> return null // HTTP or unknown: not shown (out of scope)
        }
        return ProxyInfo(id = id, kind = kind, server = proxy.server, port = proxy.port, enabled = isEnabled)
    }
}
