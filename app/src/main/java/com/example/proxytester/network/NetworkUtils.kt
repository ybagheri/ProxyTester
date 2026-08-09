package com.example.proxytester.network

object NetworkUtils {
    fun elapsedMs(startNanoTime: Long): Long =
        (System.nanoTime() - startNanoTime) / 1_000_000
}
