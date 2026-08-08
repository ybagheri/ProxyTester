package com.example.proxytester.network

object NetworkUtils {
    /**
     * Well-known Telegram MTProto Data Center IPs (production). Using one
     * of these as the CONNECT target through a SOCKS5 proxy is what turns
     * "port is open" into "we can actually reach Telegram through this
     * proxy", per the project's real-test requirement.
     *
     * Source: Telegram publishes these; they occasionally change, so this
     * list should be refreshed periodically (a future version could fetch
     * it from Telegram's own config).
     */
    val TELEGRAM_DC_IPS = listOf(
        "149.154.175.50", // DC2
        "149.154.167.51", // DC4
        "149.154.175.100" // DC1
    )
    const val TELEGRAM_TEST_PORT = 443

    fun elapsedMs(startNanoTime: Long): Long =
        (System.nanoTime() - startNanoTime) / 1_000_000
}
