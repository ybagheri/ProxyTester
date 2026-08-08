package com.example.proxytester.model

/**
 * A single parsed proxy entry.
 *
 * [secret] is only meaningful for MTPROTO proxies (the hex/base64 secret
 * from the tg://proxy link). It is null for SOCKS5.
 *
 * [url] keeps the original raw line/link for display and re-sharing.
 */
data class Proxy(
    val type: ProxyType,
    val server: String,
    val port: Int,
    val secret: String? = null,
    val url: String
)
