package com.example.proxytester.model

enum class FailureReason {
    NONE,
    INVALID_FORMAT,
    TIMEOUT,
    CONNECTION_REFUSED,
    PROXY_BLOCKED,
    TELEGRAM_UNREACHABLE,
    INVALID_SECRET,
    UNKNOWN
}

data class ProxyResult(
    val proxy: Proxy,
    val success: Boolean,
    val pingMs: Long,
    val message: String,
    val reason: FailureReason = FailureReason.NONE
)
