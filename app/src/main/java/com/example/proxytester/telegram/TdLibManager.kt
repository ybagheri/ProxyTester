package com.example.proxytester.telegram

import com.example.proxytester.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File

data class TdLibProbeResult(val success: Boolean, val message: String)

/**
 * Thin wrapper around TDLib's Client for exactly one purpose: prove that a
 * given MTProto proxy can carry a real Telegram protocol session, not just
 * a raw TCP handshake. This deliberately does NOT log a user in — no phone
 * number / auth code flow — it only waits for TDLib to reach
 * ConnectionStateReady (or fail/timeout) after being pointed at the proxy.
 *
 * Event-driven: waits for AuthorizationStateWaitTdlibParameters before
 * sending SetTdlibParameters, and only sends AddProxy once that succeeds —
 * same pattern proven to work in TelegramSession, instead of firing both
 * requests blindly right after client creation. Also propagates errors
 * from those two calls instead of a silent `{ }` handler, so a proxy that
 * TDLib rejects outright shows up as a real failure reason rather than
 * just "timed out".
 *
 * Requires TELEGRAM_API_ID / TELEGRAM_API_HASH to be set in
 * local.properties (see local.properties.example) and the TDLib native
 * library to be added as a dependency — see docs/tdlib-integration.md.
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
        // Each probe gets its own throwaway DB dir so parallel probes for
        // different proxies never collide and nothing persists afterwards.
        val runDir = File(cacheDir, "tdlib-probe-${System.nanoTime()}").apply { mkdirs() }

        var proxyRequestSent = false
        lateinit var client: Client

        fun complete(result: TdLibProbeResult) {
            if (!readyDeferred.isCompleted) readyDeferred.complete(result)
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
                                complete(TdLibProbeResult(false, "SetTdlibParameters failed: ${paramsResult.message}"))
                                return@send
                            }
                            if (proxyRequestSent) return@send
                            proxyRequestSent = true
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
                                    complete(TdLibProbeResult(false, "AddProxy rejected: ${addProxyResult.message}"))
                                }
                            }
                        }
                    }
                }
                is TdApi.UpdateConnectionState -> when (update.state) {
                    is TdApi.ConnectionStateReady -> complete(
                        TdLibProbeResult(true, "TDLib reached ConnectionStateReady through the proxy")
                    )
                    else -> Unit // still connecting/waiting for network — keep waiting for the timeout
                }
                is TdApi.Error -> complete(
                    TdLibProbeResult(false, "TDLib error ${update.code}: ${update.message}")
                )
                else -> Unit
            }
        }

        client = Client.create(updateHandler, null, null)

        val result = withTimeoutOrNull(timeoutMs) { readyDeferred.await() }
            ?: TdLibProbeResult(false, "Timed out waiting for TDLib to connect through the proxy")

        client.send(TdApi.Close()) { }
        runDir.deleteRecursively()
        return result
    }
}
