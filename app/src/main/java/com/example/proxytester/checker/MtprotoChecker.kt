package com.example.proxytester.checker

import com.example.proxytester.model.FailureReason
import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyResult
import com.example.proxytester.telegram.TdLibManager
import java.io.File

/**
 * Real MTProto proxy test via TDLib (priority #4 in the spec).
 *
 * Wiring here is done, but two things still need to happen before this
 * actually runs on a device:
 *   1. TELEGRAM_API_ID / TELEGRAM_API_HASH in local.properties (see
 *      local.properties.example).
 *   2. The TDLib native library added as a build dependency — see
 *      docs/tdlib-integration.md for the build steps. Until that
 *      dependency exists, this class will fail to compile/link at
 *      runtime (ClassNotFoundException for org.drinkless.tdlib.Client),
 *      which is expected and not a bug in this file.
 *
 * The test itself does not log a user in — it only waits for TDLib to
 * report a live connection through the proxy (or fail/time out), which is
 * enough to validate the proxy without needing phone/auth-code flows.
 */
class MtprotoChecker(private val cacheDir: File) : ProxyChecker {

    private val tdLibManager = TdLibManager(cacheDir)

    override suspend fun check(proxy: Proxy): ProxyResult {
        val secret = proxy.secret
        if (secret.isNullOrBlank()) {
            return ProxyResult(
                proxy = proxy,
                success = false,
                pingMs = 0,
                message = "MTProto proxy link is missing a secret",
                reason = FailureReason.INVALID_FORMAT
            )
        }

        val probe = try {
            tdLibManager.testMtprotoProxy(proxy.server, proxy.port, secret)
        } catch (e: Exception) {
            return ProxyResult(
                proxy = proxy,
                success = false,
                pingMs = 0,
                message = e.message ?: "TDLib probe failed to start (is the TDLib dependency wired up yet?)",
                reason = FailureReason.UNKNOWN
            )
        }

        return ProxyResult(
            proxy = proxy,
            success = probe.success,
            pingMs = probe.elapsedMs,
            message = probe.message,
            reason = if (probe.success) FailureReason.NONE else FailureReason.TELEGRAM_UNREACHABLE
        )
    }
}
