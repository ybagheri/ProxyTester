package com.example.proxytester.checker

import com.example.proxytester.model.FailureReason
import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyResult
import com.example.proxytester.network.NetworkUtils
import com.example.proxytester.network.Socks5Client
import com.example.proxytester.network.Socks5Exception
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Real SOCKS5 test — back to real Telegram DC IPs (like the official
 * client actually connects to), same idea as the original version, but
 * with the ATYP encoding bug fixed: Socks5Client now sends a literal IP
 * using ATYP=IPv4 instead of encoding it as a "domain name" string, which
 * some SOCKS5 servers mishandled and was very likely producing false
 * negatives across the board.
 *
 * Steps:
 *  1. Real RFC1928 handshake against the proxy.
 *  2. CONNECT to a real Telegram DC IP:443 THROUGH the proxy (tries all
 *     known DCs, first one that connects wins).
 *  3. Writes the MTProto "abridged" transport marker byte (0xEF) and
 *     waits briefly to see the tunnel stay open / produce a response,
 *     rather than an immediate reset.
 *
 * This still isn't a full MTProto handshake (that needs real crypto and
 * lives in MtprotoChecker/TDLib for actual MTProto-type proxies) — but for
 * SOCKS5 proxies this is the most direct signal available without
 * reimplementing MTProto's crypto by hand.
 */
class Socks5Checker : ProxyChecker {

    override suspend fun check(proxy: Proxy): ProxyResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val attempts = mutableListOf<String>()

        for (dcIp in NetworkUtils.TELEGRAM_DC_IPS) {
            try {
                val client = Socks5Client(proxy.server, proxy.port)
                val socket = client.connect(dcIp, NetworkUtils.TELEGRAM_TEST_PORT)
                socket.use {
                    it.getOutputStream().write(byteArrayOf(0xEF.toByte()))
                    it.getOutputStream().flush()

                    it.soTimeout = 8000
                    val probe = ByteArray(1)
                    try {
                        it.getInputStream().read(probe)
                    } catch (readTimeout: SocketTimeoutException) {
                        // No bytes back is fine — a real MTProto reply needs
                        // a full req_pq round trip we're not doing here. The
                        // tunnel staying open without an immediate reset is
                        // the actual signal.
                    }
                }

                return@withContext ProxyResult(
                    proxy = proxy,
                    success = true,
                    pingMs = NetworkUtils.elapsedMs(start),
                    message = "SOCKS5 CONNECT to Telegram DC ($dcIp) succeeded"
                )
            } catch (e: Socks5Exception) {
                attempts.add("$dcIp: ${e.message}")
            } catch (e: SocketTimeoutException) {
                attempts.add("$dcIp: timed out")
            } catch (e: IOException) {
                attempts.add("$dcIp: ${e.message}")
            }
        }

        ProxyResult(
            proxy = proxy,
            success = false,
            pingMs = NetworkUtils.elapsedMs(start),
            message = "All DC attempts failed — " + attempts.joinToString(" | "),
            reason = classifyFailure(attempts)
        )
    }

    private fun classifyFailure(attempts: List<String>): FailureReason {
        val joined = attempts.joinToString(" ")
        return when {
            attempts.isEmpty() -> FailureReason.UNKNOWN
            joined.contains("timed out", ignoreCase = true) -> FailureReason.TIMEOUT
            joined.contains("not a SOCKS5 proxy", ignoreCase = true) ||
                joined.contains("authentication", ignoreCase = true) ->
                FailureReason.PROXY_BLOCKED
            joined.contains("refused", ignoreCase = true) -> FailureReason.CONNECTION_REFUSED
            joined.contains("not allowed by ruleset", ignoreCase = true) ||
                joined.contains("unreachable", ignoreCase = true) ->
                FailureReason.PROXY_BLOCKED
            else -> FailureReason.UNKNOWN
        }
    }
}
