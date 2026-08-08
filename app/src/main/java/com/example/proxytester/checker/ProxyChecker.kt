package com.example.proxytester.checker

import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyResult

interface ProxyChecker {
    suspend fun check(proxy: Proxy): ProxyResult
}
