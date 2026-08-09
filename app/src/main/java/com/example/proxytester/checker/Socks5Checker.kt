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
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real SOCKS5 test:
 *  1. Real RFC1928 handshake against the proxy.
 *  2. CONNECT to api.telegram.org:443 THROUGH the proxy — a real domain
 *     name (not a raw Telegram DC IP encoded as a fake "domain", which an
 *     earlier version of this file did and which some SOCKS5 servers
 *     handle inconsistently).
 *  3. A full TLS handshake + minimal HTTP request over that tunnel, and
 *     checking we get back something that looks like an HTTP response.
 *
 * This mirrors the approach already validated in this project's own
 * Python proxy collector (`requests.get("https://api.telegram.org",
 * proxies=...)`), rather than a custom raw-MTProto-marker probe against
 * a hardcoded, possibly-stale Data Center IP list.
 */
class Socks5Checker : ProxyChecker {

    companion object {
        private const val TARGET_HOST = "api.telegram.org"
        private const val TARGET_PORT = 443
        private const val SOCKET_TIMEOUT_MS = 8000
    }

    override suspend fun check(proxy: Proxy): ProxyResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()

        try {
            val client = Socks5Client(proxy.server, proxy.port)
            val tunnelSocket = client.connect(TARGET_HOST, TARGET_PORT)

            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(tunnelSocket, TARGET_HOST, TARGET_PORT, true) as SSLSocket
            sslSocket.soTimeout = SOCKET_TIMEOUT_MS

            sslSocket.use { s ->
                s.startHandshake()

                val request = "GET / HTTP/1.1\r\nHost: $TARGET_HOST\r\nConnection: close\r\n\r\n"
                s.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                s.outputStream.flush()

                val statusLine = s.inputStream.bufferedReader().readLine() ?: ""
                if (!statusLine.startsWith("HTTP/")) {
                    throw IOException("Unexpected response after TLS handshake: $statusLine")
                }
            }

            ProxyResult(
                proxy = proxy,
                success = true,
                pingMs = NetworkUtils.elapsedMs(start),
                message = "SOCKS5 tunnel reached $TARGET_HOST and completed a TLS+HTTP round trip"
            )
        } catch (e: Socks5Exception) {
            ProxyResult(proxy, false, NetworkUtils.elapsedMs(start), e.message ?: "SOCKS5 handshake failed", FailureReason.PROXY_BLOCKED)
        } catch (e: SocketTimeoutException) {
            ProxyResult(proxy, false, NetworkUtils.elapsedMs(start), e.message ?: "Timed out", FailureReason.TIMEOUT)
        } catch (e: IOException) {
            ProxyResult(proxy, false, NetworkUtils.elapsedMs(start), e.message ?: "Connection failed", FailureReason.CONNECTION_REFUSED)
        } catch (e: Exception) {
            ProxyResult(proxy, false, NetworkUtils.elapsedMs(start), e.message ?: "Unknown error", FailureReason.UNKNOWN)
        }
    }
}
