package com.example.proxytester.repository

import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyResult
import com.example.proxytester.parser.ProxyParser
import com.example.proxytester.telegram.TelegramSession

data class ChannelTestSummary(
    val total: Int,
    val results: List<ProxyResult>
) {
    val workingCount: Int get() = results.count { it.success }
    val failedCount: Int get() = results.count { !it.success }
}

/**
 * Ties the pieces together for the channel-scan feature (the Kotlin
 * counterpart of the Python script): read recent messages from a channel,
 * extract every embedded proxy link, de-duplicate, and real-test each one
 * with the existing checkers.
 */
class ChannelProxyRepository(
    private val telegramSession: TelegramSession,
    private val proxyRepository: ProxyRepository
) {
    suspend fun fetchAndTest(channelUsername: String, messageLimit: Int = 40): ChannelTestSummary {
        val messageTexts = telegramSession.fetchRecentMessageTexts(channelUsername, messageLimit)

        val seenKeys = HashSet<String>()
        val proxies = mutableListOf<Proxy>()
        for (text in messageTexts) {
            for (proxy in ProxyParser.extractFromText(text)) {
                val key = "${proxy.type}:${proxy.server}:${proxy.port}"
                if (seenKeys.add(key)) {
                    proxies.add(proxy)
                }
            }
        }

        val results = proxyRepository.testAll(proxies)
        return ChannelTestSummary(total = proxies.size, results = results)
    }
}
