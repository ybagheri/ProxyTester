package com.example.proxytester.telegram

import com.example.proxytester.BuildConfig
import com.example.proxytester.model.Proxy
import com.example.proxytester.model.ProxyType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File

sealed class TelegramAuthState {
    object Connecting : TelegramAuthState()
    object WaitingForPhoneNumber : TelegramAuthState()
    object WaitingForCode : TelegramAuthState()
    object WaitingForPassword : TelegramAuthState()
    object Ready : TelegramAuthState()
    data class Error(val reason: String) : TelegramAuthState()
}

/**
 * A persistent, logged-in TDLib session for reading channel messages the
 * way a normal Telegram user client would — this is the Kotlin/TDLib
 * counterpart of what the Python script did with Telethon's
 * `TelegramClient(...).iter_messages(channel, limit=...)`.
 *
 * Unlike [TdLibManager] (a one-shot, throwaway-DB probe used purely to
 * test whether a single proxy can reach Telegram), this session keeps one
 * long-lived [Client] with a persistent database directory under the
 * app's files dir, so the person only has to log in once — the session
 * survives app restarts, same as the official Telegram app.
 *
 * Login is a normal phone-number + code (+ optional 2FA password) flow;
 * [authState] drives the UI through it. No credentials are sent anywhere
 * except directly to Telegram via TDLib itself.
 */
class TelegramSession(private val filesDir: File) {

    private var client: Client? = null

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Connecting)
    val authState: StateFlow<TelegramAuthState> = _authState

    // Surfaces TDLib errors from login steps (wrong code, banned number,
    // etc.) that previously went into an empty {} result handler and
    // silently vanished. UI observes this separately from authState so an
    // error doesn't get overwritten by the next state transition.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val dbDir: File by lazy { File(filesDir, "tdlib-session").apply { mkdirs() } }

    fun start() {
        if (client != null) return
        client = Client.create({ update -> handleUpdate(update) }, null, null)
    }

    private fun handleUpdate(update: TdApi.Object) {
        val authorizationState = (update as? TdApi.UpdateAuthorizationState)?.authorizationState ?: return
        applyAuthState(authorizationState)
    }

    private fun applyAuthState(state: TdApi.AuthorizationState) {
        val c = client ?: return
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                c.send(
                    TdApi.SetTdlibParameters().apply {
                        apiId = BuildConfig.TELEGRAM_API_ID
                        apiHash = BuildConfig.TELEGRAM_API_HASH
                        databaseDirectory = dbDir.absolutePath
                        filesDirectory = dbDir.absolutePath
                        useMessageDatabase = true
                        useSecretChats = false
                        useFileDatabase = false
                        useChatInfoDatabase = true
                        systemLanguageCode = "en"
                        deviceModel = "ProxyTester"
                        applicationVersion = "0.3"
                    }
                ) { }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _authState.value = TelegramAuthState.WaitingForPhoneNumber
            is TdApi.AuthorizationStateWaitCode ->
                _authState.value = TelegramAuthState.WaitingForCode
            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = TelegramAuthState.WaitingForPassword
            is TdApi.AuthorizationStateReady ->
                _authState.value = TelegramAuthState.Ready
            is TdApi.AuthorizationStateClosed ->
                _authState.value = TelegramAuthState.Connecting
            else -> Unit
        }
    }

    /**
     * Routes this session's connection (including the login handshake
     * itself) through [proxy]. Call this BEFORE submitting a phone number
     * if Telegram isn't directly reachable — which, for this app's whole
     * reason to exist (testing from inside Iran), is the common case. The
     * previous version had no equivalent of this at all: login always
     * went straight to Telegram with no proxy, same as the real thing
     * MtprotoChecker exists to test.
     */
    fun configureProxy(proxy: Proxy) {
        _lastError.value = null
        val c = client
        if (c == null) {
            _lastError.value = "Session not started yet — try again in a moment."
            return
        }

        val proxyType: TdApi.ProxyType = when (proxy.type) {
            ProxyType.SOCKS5 -> TdApi.ProxyTypeSocks5()
            ProxyType.MTPROTO -> TdApi.ProxyTypeMtproto(proxy.secret ?: "")
            ProxyType.UNKNOWN -> {
                _lastError.value = "Unrecognized proxy type for login"
                return
            }
        }

        c.send(
            TdApi.AddProxy().apply {
                this.proxy = TdApi.Proxy().apply {
                    server = proxy.server
                    port = proxy.port
                    type = proxyType
                }
                enable = true
            }
        ) { result ->
            if (result is TdApi.Error) {
                _lastError.value = "Failed to set login proxy: ${result.message}"
            }
        }
    }

    fun submitPhoneNumber(phoneNumberInput: String) {
        _lastError.value = null
        val c = client
        if (c == null) {
            _lastError.value = "Session not started yet — try again in a moment."
            return
        }
        c.send(
            TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = phoneNumberInput }
        ) { result ->
            if (result is TdApi.Error) {
                _lastError.value = "Telegram rejected the phone number: ${result.message}"
            }
        }
    }

    fun submitCode(codeInput: String) {
        _lastError.value = null
        val c = client
        if (c == null) {
            _lastError.value = "Session not started yet — try again in a moment."
            return
        }
        c.send(
            TdApi.CheckAuthenticationCode().apply { code = codeInput }
        ) { result ->
            if (result is TdApi.Error) {
                _lastError.value = "Telegram rejected the code: ${result.message}"
            }
        }
    }

    fun submitPassword(passwordInput: String) {
        _lastError.value = null
        val c = client
        if (c == null) {
            _lastError.value = "Session not started yet — try again in a moment."
            return
        }
        c.send(
            TdApi.CheckAuthenticationPassword().apply { password = passwordInput }
        ) { result ->
            if (result is TdApi.Error) {
                _lastError.value = "Telegram rejected the password: ${result.message}"
            }
        }
    }

    /**
     * Fetches up to [messageLimit] most recent message texts from a public
     * channel by username (with or without a leading @) — the counterpart
     * of the Python script's `client.iter_messages(CHANNEL, limit=LIMIT)`.
     *
     * Only works for public channels TDLib can resolve by username. For a
     * private channel you'd need to already be a member and use its chat
     * id instead — not implemented here since the default channel and the
     * spec's use case are both public.
     */
    suspend fun fetchRecentMessageTexts(channelUsername: String, messageLimit: Int = 40): List<String> {
        val cleanUsername = channelUsername.removePrefix("@").trim()
        val chat = sendAndAwait(
            TdApi.SearchPublicChat().apply { username = cleanUsername }
        ) as TdApi.Chat

        val texts = mutableListOf<String>()
        var nextFromMessageId = 0L

        while (texts.size < messageLimit) {
            val batchLimit = (messageLimit - texts.size).coerceAtMost(50)
            val history = sendAndAwait(
                TdApi.GetChatHistory().apply {
                    chatId = chat.id
                    fromMessageId = nextFromMessageId
                    offset = 0
                    limit = batchLimit
                    onlyLocal = false
                }
            ) as TdApi.Messages

            val messages = history.messages ?: emptyArray()
            if (messages.isEmpty()) break

            for (message in messages) {
                val content = message.content
                if (content is TdApi.MessageText) {
                    texts.add(content.text.text)
                }
            }
            nextFromMessageId = messages.last().id
        }

        return texts
    }

    /** Sends [messageText] to Telegram's "Saved Messages" chat (a chat with yourself). */
    suspend fun sendToSavedMessages(messageText: String) {
        val me = sendAndAwait(TdApi.GetMe()) as TdApi.User

        val formattedText = TdApi.FormattedText().apply {
            text = messageText
            entities = emptyArray()
        }
        val content = TdApi.InputMessageText().apply {
            text = formattedText
        }

        sendAndAwait(
            TdApi.SendMessage().apply {
                chatId = me.id
                inputMessageContent = content
            }
        )
    }

    private suspend fun sendAndAwait(function: TdApi.Function<*>): TdApi.Object {
        val c = client ?: throw IllegalStateException("Session not started — call start() first")
        val deferred = CompletableDeferred<TdApi.Object>()
        c.send(function) { result -> deferred.complete(result) }
        val result = deferred.await()
        if (result is TdApi.Error) {
            throw RuntimeException("TDLib error ${result.code}: ${result.message}")
        }
        return result
    }
}
