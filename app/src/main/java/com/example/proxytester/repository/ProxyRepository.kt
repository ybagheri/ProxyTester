package com.example.proxytester.repository

import com.example.proxytester.checker.MtprotoChecker
import com.example.proxytester.checker.Socks5Checker
import com.example.proxytester.model.FailureReason
import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyResult
import com.example.proxytester.model.ProxyType
import com.example.proxytester.parser.ProxyParser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class ProxyRepository(
    /** Pass context.cacheDir here — TDLib needs a writable scratch directory per probe. */
    cacheDir: File,
    // SOCKS5 checks are plain sockets — cheap, fine at high concurrency.
    private val maxConcurrentSocks5: Int = 20,
    // Each MTProto check spins up a full TDLib client (its own thread,
    // JNI, network stack). Running 20 of those at once on a phone was
    // very likely why every proxy timed out — keep this modest.
    private val maxConcurrentMtproto: Int = 4
) {
    private val mtprotoChecker = MtprotoChecker(cacheDir)
    private val socks5Checker = Socks5Checker()
    private val httpClient = OkHttpClient()

    suspend fun testSingle(rawLine: String): ProxyResult? {
        val proxy = ProxyParser.parse(rawLine) ?: return invalidFormatResult(rawLine)
        return testOne(proxy)
    }

    /** Downloads a plain-text proxy list (one link per line) and tests all of them. */
    suspend fun testListFromUrl(url: String): List<ProxyResult> {
        val text = downloadText(url)
        val proxies = ProxyParser.parseList(text)
        return testAll(proxies)
    }

    suspend fun testAll(proxies: List<Proxy>): List<ProxyResult> = coroutineScope {
        val socks5Semaphore = Semaphore(maxConcurrentSocks5)
        val mtprotoSemaphore = Semaphore(maxConcurrentMtproto)
        proxies.map { proxy ->
            async {
                val semaphore = if (proxy.type == ProxyType.MTPROTO) mtprotoSemaphore else socks5Semaphore
                semaphore.withPermit { testOne(proxy) }
            }
        }.map { it.await() }
    }

    private suspend fun testOne(proxy: Proxy): ProxyResult = when (proxy.type) {
        ProxyType.MTPROTO -> mtprotoChecker.check(proxy)
        ProxyType.SOCKS5 -> socks5Checker.check(proxy)
        ProxyType.UNKNOWN -> ProxyResult(
            proxy = proxy,
            success = false,
            pingMs = 0,
            message = "Unrecognized proxy type",
            reason = FailureReason.INVALID_FORMAT
        )
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Failed to download proxy list: HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    private fun invalidFormatResult(rawLine: String): ProxyResult? {
        // Can't build a Proxy without at least a type/server, so we just
        // signal null and let the UI show a generic parse-error message.
        return null
    }
}
