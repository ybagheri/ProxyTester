package com.example.proxytester.telegram

import com.example.proxytester.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File

data class TdLibProbeResult(val success: Boolean, val message: String, val elapsedMs: Long)

/**
 * Thin wrapper around TDLib's Client for exactly one purpose: prove that a
 * given MTProto proxy can carry a real Telegram protocol session, not just
 * a raw TCP handshake. This deliberately does NOT log a user in — no phone
 * number / auth code flow — it only waits for TDLib to reach
 * ConnectionStateReady (or fail/timeout) after being pointed at the proxy.
 *
 * A note on the reported timing: this measures a fresh TDLib session
 * reaching ConnectionStateReady, which includes a full MTProto key
 * exchange (Diffie-Hellman handshake) for a brand-new, uncached session —
 * NOT a lightweight ping over an already-established connection like the
 * official Telegram app's "check proxy" does (which reuses a warm,
 * already-keyed session). That's an inherent, expected difference in what
 * is being measured, not a bug — a first-time handshake is always going
 * to look slower than a ping on a session that already exists. To keep
 * the number as fair as reasonably possible, the clock only starts once
 * AddProxy is actually sent, excluding TDLib's own client/database
 * bring-up time (which is fixed overhead unrelated to the proxy itself).
 */
class TdLibManager(private val cacheDir: File) {

    suspend fun testMtprotoProxy(
        proxyServer: String,
        proxyPort: Int,
        proxySecret: String,
        timeoutMs: Long = 20_000
    ): TdLibProbeResult {
        check(BuildConfig.TELEGRAM_API_ID != 0) {
            "TELEGRAM_API_ID is not set — copy local.properties.example to " +
                "local.properties and fill in credentials from my.telegram.org"
        }

        val readyDeferred = CompletableDeferred<TdLibProbeResult>()
        val runDir = File(cacheDir, "tdlib-probe-${System.nanoTime()}").apply { mkdirs() }

        var proxyRequestSent = false
        var proxySentAtNanos = System.nanoTime() // fallback if we never get that far
        lateinit var client: Client

        fun elapsed(): Long = (System.nanoTime() - proxySentAtNanos) / 1_000_000

        fun complete(success: Boolean, message: String) {
            if (!readyDeferred.isCompleted) {
                readyDeferred.complete(TdLibProbeResult(success, message, elapsed()))
            }
        }

        val updateHandler = Client.ResultHandler { update ->
            when (update) {
                is TdApi.UpdateAuthorizationState -> {
                    val state = update.authorizationState
                    if (state is TdApi.AuthorizationStateWaitTdlibParameters) {
                        client.send(
                            TdApi.SetTdlibParameters().apply {
                                apiId = BuildConfig.TELEGRAM_API_ID
                                apiHash = BuildConfig.TELEGRAM_API_HASH
                                databaseDirectory = runDir.absolutePath
                                filesDirectory = runDir.absolutePath
                                useMessageDatabase = false
                                useSecretChats = false
                                useFileDatabase = false
                                useChatInfoDatabase = false
                                systemLanguageCode = "en"
                                deviceModel = "ProxyTesterProbe"
                                applicationVersion = "0.2"
                            }
                        ) { paramsResult ->
                            if (paramsResult is TdApi.Error) {
                                complete(false, "SetTdlibParameters failed: ${paramsResult.message}")
                                return@send
                            }
                            if (proxyRequestSent) return@send
                            proxyRequestSent = true
                            proxySentAtNanos = System.nanoTime()
                            client.send(
                                TdApi.AddProxy().apply {
                                    proxy = TdApi.Proxy().apply {
                                        server = proxyServer
                                        port = proxyPort
                                        type = TdApi.ProxyTypeMtproto(proxySecret)
                                    }
                                    enable = true
                                }
                            ) { addProxyResult ->
                                if (addProxyResult is TdApi.Error) {
                                    complete(false, "AddProxy rejected: ${addProxyResult.message}")
                                }
                            }
                        }
                    }
                }
                is TdApi.UpdateConnectionState -> when (update.state) {
                    is TdApi.ConnectionStateReady ->
                        complete(true, "TDLib reached ConnectionStateReady through the proxy")
                    else -> Unit // still connecting/waiting for network — keep waiting for the timeout
                }
                is TdApi.Error -> complete(false, "TDLib error ${update.code}: ${update.message}")
                else -> Unit
            }
        }

        client = Client.create(updateHandler, null, null)

        val result = withTimeoutOrNull(timeoutMs) { readyDeferred.await() }
            ?: TdLibProbeResult(false, "Timed out waiting for TDLib to connect through the proxy", elapsed())

        client.send(TdApi.Close()) { }
        runDir.deleteRecursively()
        return result
    }
}
