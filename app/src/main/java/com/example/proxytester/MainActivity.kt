package com.example.proxytester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.proxytester.model.ProxyResult
import com.example.proxytester.model.ProxyType
import com.example.proxytester.parser.ProxyParser
import com.example.proxytester.repository.ChannelProxyRepository
import com.example.proxytester.repository.ChannelTestSummary
import com.example.proxytester.repository.ProxyRepository
import com.example.proxytester.telegram.TelegramAuthState
import com.example.proxytester.telegram.TelegramSession
import com.example.proxytester.utils.SettingsStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val telegramSession = remember { TelegramSession(context.filesDir) }
    val proxyRepository = remember { ProxyRepository(cacheDir = context.cacheDir) }
    val channelRepository = remember { ChannelProxyRepository(telegramSession, proxyRepository) }

    var selectedTab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Single Proxy") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Channel Scan") }
            )
        }
        when (selectedTab) {
            0 -> ProxyTesterScreen(proxyRepository)
            1 -> ChannelScanScreen(telegramSession, channelRepository, settingsStore)
        }
    }
}

@Composable
fun ProxyTesterScreen(repository: ProxyRepository) {
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProxyResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Telegram Proxy Tester", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Proxy link (tg://, t.me/proxy, socks5://)") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                errorText = null
                result = null
                isTesting = true
                scope.launch {
                    try {
                        val r = repository.testSingle(input)
                        if (r == null) {
                            errorText = "Could not parse this proxy link. Check the format."
                        } else {
                            result = r
                        }
                    } catch (e: Exception) {
                        errorText = e.message ?: "Unknown error"
                    } finally {
                        isTesting = false
                    }
                }
            },
            enabled = input.isNotBlank() && !isTesting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isTesting) "Testing..." else "TEST")
        }

        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        result?.let { r ->
            val clipboard = LocalClipboardManager.current
            Divider()
            Text("Result", style = MaterialTheme.typography.titleMedium)
            Text("Type: ${r.proxy.type}")
            Text("Server: ${r.proxy.server}")
            Text("Port: ${r.proxy.port}")
            Text("Ping: ${r.pingMs} ms")
            Text("Status: ${if (r.success) "✅ WORKING" else "❌ FAILED (${r.reason})"}")
            Text(r.message, style = MaterialTheme.typography.bodySmall)
            if (r.success) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(r.proxy.url)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copy proxy link") }
            }
        }
    }
}

@Composable
fun ChannelScanScreen(
    telegramSession: TelegramSession,
    channelRepository: ChannelProxyRepository,
    settingsStore: SettingsStore
) {
    val scope = rememberCoroutineScope()
    val authState by telegramSession.authState.collectAsState()
    val sessionError by telegramSession.lastError.collectAsState()

    var channel by remember { mutableStateOf(settingsStore.getChannel()) }
    var messageLimitInput by remember { mutableStateOf("40") }
    var loginProxyInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }

    var isBusy by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<ChannelTestSummary?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var sendStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { telegramSession.start() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Scan a Telegram channel", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Reads recent messages from a public channel, extracts every " +
                "proxy link, and real-tests each one — same idea as the " +
                "Python collector, run from the phone.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = channel,
            onValueChange = {
                channel = it
                settingsStore.setChannel(it)
            },
            label = { Text("Channel username(s), comma or newline separated") },
            placeholder = { Text(SettingsStore.DEFAULT_CHANNEL) },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = messageLimitInput,
            onValueChange = { messageLimitInput = it.filter { c -> c.isDigit() } },
            label = { Text("How many recent posts to check") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Divider()

        Text(
            "If Telegram isn't directly reachable, set a proxy for the " +
                "login connection itself first (paste a link you already " +
                "know works, e.g. from the Single Proxy tab).",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = loginProxyInput,
            onValueChange = { loginProxyInput = it },
            label = { Text("Proxy for login (optional, tg:// or socks5://)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val parsed = ProxyParser.parse(loginProxyInput)
                if (parsed == null) {
                    sendStatus = "Could not parse that proxy link."
                } else {
                    telegramSession.configureProxy(parsed)
                    sendStatus = "Proxy applied for login."
                }
            },
            enabled = loginProxyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Apply proxy") }

        Divider()

        when (authState) {
            TelegramAuthState.Connecting -> {
                Text("Connecting to Telegram...")
            }
            TelegramAuthState.WaitingForPhoneNumber -> {
                Text("Log in to read channel messages (one-time).", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("Phone number, e.g. +989121234567") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { telegramSession.submitPhoneNumber(phoneInput) },
                    enabled = phoneInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send code") }
            }
            TelegramAuthState.WaitingForCode -> {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    label = { Text("Login code from Telegram") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { telegramSession.submitCode(codeInput) },
                    enabled = codeInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Confirm code") }
            }
            TelegramAuthState.WaitingForPassword -> {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Two-step verification password") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { telegramSession.submitPassword(passwordInput) },
                    enabled = passwordInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Confirm password") }
            }
            TelegramAuthState.Ready -> {
                Text("Logged in ✅", color = MaterialTheme.colorScheme.primary)
                Button(
                    onClick = {
                        errorText = null
                        sendStatus = null
                        summary = null
                        isBusy = true
                        scope.launch {
                            try {
                                val limit = messageLimitInput.toIntOrNull()?.coerceIn(1, 500) ?: 40
                                val channels = channel.split(",", "\n")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                summary = channelRepository.fetchAndTest(channels, limit)
                            } catch (e: Exception) {
                                errorText = e.message ?: "Unknown error"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    enabled = channel.isNotBlank() && !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "Scanning..." else "Fetch & Test")
                }
            }
            is TelegramAuthState.Error -> {
                Text(
                    "Telegram session error: ${(authState as TelegramAuthState.Error).reason}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        sessionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        summary?.let { s ->
            val clipboard = LocalClipboardManager.current
            Divider()
            Text("Total: ${s.total}   Working: ${s.workingCount}   Failed: ${s.failedCount}")
            Text(
                "MTProto — total ${s.totalByType[ProxyType.MTPROTO] ?: 0}, " +
                    "working ${s.workingByType[ProxyType.MTPROTO] ?: 0}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "SOCKS5 — total ${s.totalByType[ProxyType.SOCKS5] ?: 0}, " +
                    "working ${s.workingByType[ProxyType.SOCKS5] ?: 0}",
                style = MaterialTheme.typography.bodySmall
            )

            if (s.channelErrors.isNotEmpty()) {
                Text("Channel errors", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                s.channelErrors.forEach { (ch, err) ->
                    Text("• $ch: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            if (s.workingCount > 0) {
                Text("Working (fastest first)", style = MaterialTheme.typography.titleSmall)
                s.results.filter { it.success }.sortedBy { it.pingMs }.forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "✅ ${r.proxy.type} ${r.proxy.server}:${r.proxy.port}  (${r.pingMs} ms)",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { clipboard.setText(AnnotatedString(r.proxy.url)) }) {
                            Text("Copy")
                        }
                    }
                }
                Button(
                    onClick = {
                        val lines = s.results.filter { it.success }.sortedBy { it.pingMs }
                            .joinToString("\n") { it.proxy.url }
                        clipboard.setText(AnnotatedString(lines))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copy all working links") }
            }

            if (s.failedCount > 0) {
                Text("Failed (why)", style = MaterialTheme.typography.titleSmall)
                s.results.filter { !it.success }.forEach { r ->
                    Text(
                        "❌ ${r.proxy.type} ${r.proxy.server}:${r.proxy.port} — ${r.reason}: ${r.message}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (s.workingCount > 0) {
                Button(
                    onClick = {
                        scope.launch {
                            val lines = s.results.filter { it.success }.sortedBy { it.pingMs }
                                .joinToString("\n") { it.proxy.url }
                            try {
                                telegramSession.sendToSavedMessages("✅ ${s.workingCount} working proxies:\n\n$lines")
                                sendStatus = "Sent to Saved Messages."
                            } catch (e: Exception) {
                                sendStatus = "Failed to send: ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send results to Saved Messages") }
            }
        }

        sendStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
