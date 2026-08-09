package com.example.proxytester.parser

import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyType
import java.net.URI

/**
 * Parses the three supported link formats into [Proxy] objects:
 *   tg://proxy?server=...&port=...&secret=...
 *   https://t.me/proxy?server=...&port=...&secret=...
 *   socks5://host:port  (optionally socks5://user:pass@host:port)
 */
object ProxyParser {

    fun parse(rawLine: String): Proxy? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null

        return try {
            when {
                line.startsWith("tg://proxy") -> parseMtproto(line)
                line.contains("t.me/proxy") -> parseMtproto(line)
                line.startsWith("socks5://") -> parseSocks5(line)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Parses a whole file's worth of lines, skipping blanks/comments/unparseable ones. */
    fun parseList(text: String): List<Proxy> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parse(it) }
            .toList()

    // Mirrors the Python scraper's MTPROTO_PATTERN / SOCKS_PATTERN: finds
    // proxy links/addresses embedded anywhere inside a longer message,
    // not just a proxy link on its own line.
    private val MTPROTO_LINK_REGEX = Regex(
        """(?:https?://t\.me/proxy|tg://proxy)\?[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )
    private val SOCKS_ADDRESS_REGEX = Regex(
        """(?:socks5?://)?(?:\d{1,3}\.){3}\d{1,3}:\d{2,5}""",
        RegexOption.IGNORE_CASE
    )

    /** Scans free-form text (e.g. a channel message) for embedded proxy links. */
    fun extractFromText(text: String): List<Proxy> {
        val found = mutableListOf<Proxy>()

        MTPROTO_LINK_REGEX.findAll(text).forEach { match ->
            parse(match.value)?.let { found.add(it) }
        }

        SOCKS_ADDRESS_REGEX.findAll(text).forEach { match ->
            val raw = match.value
            val normalized = if (raw.contains("://")) raw else "socks5://$raw"
            parse(normalized)?.let { found.add(it) }
        }

        return found
    }

    private fun parseMtproto(line: String): Proxy? {
        val uri = URI(line)
        val query = uri.query ?: return null
        val params = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            val key = parts.getOrNull(0) ?: return@associate "" to ""
            val value = parts.getOrNull(1) ?: ""
            key to value
        }

        val server = params["server"] ?: return null
        val port = params["port"]?.toIntOrNull() ?: return null
        val secret = params["secret"]

        return Proxy(
            type = ProxyType.MTPROTO,
            server = server,
            port = port,
            secret = secret,
            url = line
        )
    }

    private fun parseSocks5(line: String): Proxy? {
        // Strip scheme, then optional user:pass@ prefix.
        val withoutScheme = line.removePrefix("socks5://")
        val hostPart = if (withoutScheme.contains("@")) {
            withoutScheme.substringAfter("@")
        } else {
            withoutScheme
        }
        val host = hostPart.substringBefore(":")
        val port = hostPart.substringAfter(":", "").toIntOrNull() ?: return null
        if (host.isBlank()) return null

        return Proxy(
            type = ProxyType.SOCKS5,
            server = host,
            port = port,
            secret = null,
            url = line
        )
    }
}
