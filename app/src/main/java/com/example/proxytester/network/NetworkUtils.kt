package com.example.proxytester.network

object NetworkUtils {
    /**
     * Real, current Telegram production Data Center IPs — same idea as
     * what the official client connects to. An earlier version of this
     * list had DC1 wrong (149.154.175.50 instead of .53); values below
     * were re-checked. These do occasionally move, so treat this as
     * "current as of this check", not permanent.
     */
    val TELEGRAM_DC_IPS = listOf(
        "149.154.175.53",  // DC1 - Miami
        "149.154.167.51",  // DC2 - Amsterdam
        "149.154.175.100", // DC3 - Miami
        "149.154.167.91",  // DC4 - Amsterdam
        "91.108.56.130"    // DC5 - Singapore
    )
    const val TELEGRAM_TEST_PORT = 443

    fun elapsedMs(startNanoTime: Long): Long =
        (System.nanoTime() - startNanoTime) / 1_000_000
}
