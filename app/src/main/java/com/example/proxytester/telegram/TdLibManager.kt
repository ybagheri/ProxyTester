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
 * Requires TELEGRAM_API_ID / TELEGRAM_API_HASH to be set in
 * local.properties (see local.properties.example) and the TDLib native
 * library to be added as a dependency — see docs/tdlib-integration.md.
 * Until both of those are in place this will throw when used.
 */
class TdLibManager(private val cacheDir: File) {

    suspend fun testMtprotoProxy(
        proxyServer: String,
        proxyPort: Int,
        proxySecret: String,
        timeoutMs: Long = 15_000
    ): TdLibProbeResult {
        check(BuildConfig.TELEGRAM_API_ID != 0) {
            "TELEGRAM_API_ID is not set — copy local.properties.example to " +
                "local.properties and fill in credentials from my.telegram.org"
        }

        val readyDeferred = CompletableDeferred<TdLibProbeResult>()
        // Each probe gets its own throwaway DB dir so parallel probes for
        // different proxies never collide and nothing persists afterwards.
        val runDir = File(cacheDir, "tdlib-probe-${System.nanoTime()}").apply { mkdirs() }

        val updateHandler = Client.ResultHandler { update ->
            when (update) {
                is TdApi.UpdateConnectionState -> when (update.state) {
                    is TdApi.ConnectionStateReady -> readyDeferred.complete(
                        TdLibProbeResult(true, "TDLib reached ConnectionStateReady through the proxy")
                    )
                    is TdApi.ConnectionStateConnectingToProxy -> Unit // keep waiting
                    is TdApi.ConnectionStateConnecting -> Unit // keep waiting
                    else -> Unit
                }
                is TdApi.Error -> readyDeferred.complete(
                    TdLibProbeResult(false, "TDLib error ${update.code}: ${update.message}")
                )
                else -> Unit
            }
        }

        val client = Client.create(updateHandler, null, null)

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
        ) { }

        client.send(
            // TDLib's current (master) schema wraps server/port/type inside
            // a nested TdApi.Proxy object, and AddProxy itself only takes
            // that proxy + an enable flag — confirmed against TDLib's own
            // addProxy JSON shape: {"enable":true,"proxy":{"server":...,
            // "port":...,"type":{...}}}. Using field-based init (not a
            // positional constructor) so this keeps compiling even if a
            // future TDLib version adds/reorders fields again.
            TdApi.AddProxy().apply {
                proxy = TdApi.Proxy().apply {
                    server = proxyServer
                    port = proxyPort
                    type = TdApi.ProxyTypeMtproto(proxySecret)
                }
                enable = true
            }
        ) { }

        val result = withTimeoutOrNull(timeoutMs) { readyDeferred.await() }
            ?: TdLibProbeResult(false, "Timed out waiting for TDLib to connect through the proxy")

        client.send(TdApi.Close()) { }
        runDir.deleteRecursively()
        return result
    }
}
