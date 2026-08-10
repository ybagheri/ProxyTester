package com.example.proxytester.repository

import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyResult
import com.example.proxytester.model.ProxyType
import com.example.proxytester.parser.ProxyParser
import com.example.proxytester.telegram.TelegramSession

data class ChannelTestSummary(
    val total: Int,
    val results: List<ProxyResult>,
    /** Channels that failed to fetch (e.g. wrong username, private chat), with the error message. */
    val channelErrors: List<Pair<String, String>> = emptyList()
) {
    val workingCount: Int get() = results.count { it.success }
    val failedCount: Int get() = results.count { !it.success }

    val totalByType: Map<ProxyType, Int> get() = results.groupingBy { it.proxy.type }.eachCount()
    val workingByType: Map<ProxyType, Int> get() =
        results.filter { it.success }.groupingBy { it.proxy.type }.eachCount()
    val failedByType: Map<ProxyType, Int> get() =
        results.filter { !it.success }.groupingBy { it.proxy.type }.eachCount()
}

/**
 * Ties the pieces together for the channel-scan feature: read recent
 * messages from one or more channels, extract every embedded proxy link,
 * de-duplicate across all of them, and real-test each one with the
 * existing checkers.
 */
class ChannelProxyRepository(
    private val telegramSession: TelegramSession,
    private val proxyRepository: ProxyRepository
) {
    /** Convenience overload for a single channel. */
    suspend fun fetchAndTest(channelUsername: String, messageLimit: Int = 40): ChannelTestSummary =
        fetchAndTest(listOf(channelUsername), messageLimit)

    suspend fun fetchAndTest(channelUsernames: List<String>, messageLimit: Int = 40): ChannelTestSummary {
        val seenKeys = HashSet<String>()
        val proxies = mutableListOf<Proxy>()
        val channelErrors = mutableListOf<Pair<String, String>>()

        for (channelUsername in channelUsernames) {
            val cleaned = channelUsername.trim()
            if (cleaned.isEmpty()) continue

            val messageTexts = try {
                telegramSession.fetchRecentMessageTexts(cleaned, messageLimit)
            } catch (e: Exception) {
                channelErrors.add(cleaned to (e.message ?: "Unknown error"))
                continue
            }

            for (text in messageTexts) {
                for (proxy in ProxyParser.extractFromText(text)) {
                    val key = "${proxy.type}:${proxy.server}:${proxy.port}"
                    if (seenKeys.add(key)) {
                        proxies.add(proxy)
                    }
                }
            }
        }

        val results = proxyRepository.testAll(proxies)
        return ChannelTestSummary(total = proxies.size, results = results, channelErrors = channelErrors)
    }
}
