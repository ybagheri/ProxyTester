# Telegram Proxy Real Tester — MVP skeleton

Implements priorities #1–#3 from the project spec:

1. Android/Kotlin project (Gradle, Jetpack Compose) — done
2. Models + Parser (`model/`, `parser/ProxyParser.kt`) — done, handles
   `tg://proxy?...`, `https://t.me/proxy?...`, `socks5://host:port`
3. Real SOCKS5 test (`network/Socks5Client.kt`, `checker/Socks5Checker.kt`) —
   done. This does a real RFC 1928 handshake against the proxy and issues a
   CONNECT to a live Telegram Data Center IP through it, so a proxy whose
   port is open but that is actually blocked/DPI-filtered will correctly
   fail here (unlike a bare `socket.connect()`).

Priority #4 (MTProto real test via TDLib) is **wired up in code** —
`checker/MtprotoChecker.kt` and `telegram/TdLibManager.kt` are written
against TDLib's real Java/JNI API, and the `:tdlib` module + a GitHub
Actions workflow are set up to build TDLib and produce a real
**arm64-v8a-only debug APK**.

**I could not compile that APK myself in this conversation.** This
sandbox has a JRE but no Android SDK, no NDK, no Docker, and its network
access is restricted to a fixed allowlist of domains that doesn't include
Google's Android/Maven servers (I checked directly — `dl.google.com` is
blocked here, and building TDLib for Android needs Docker plus hours of
compute even when it *is* reachable). So the actual compile step has to
happen somewhere with real resources: your machine, or GitHub Actions,
which is already configured to do it for you — see
**`docs/tdlib-integration.md`**, "Option A".

Priorities #5–#7 (fuller UI polish, URL-based list testing UI, reporting
to a server) are partially scaffolded — `ProxyRepository.testListFromUrl()`
already downloads and tests a whole list — but the UI only exposes the
single-proxy flow right now.

## Building this

This project was written in a sandboxed Linux container **without Android
Studio, the Android SDK, or access to Google's Maven repository**, so it
has not been compiled or run here — only written and reviewed for
correctness. To build it you'll need, on your own machine:

- Android Studio (Koala or newer) or the command-line SDK tools
- JDK 17
- Open the `ProxyTester/` folder as an existing project; Gradle sync will
  pull the AndroidX/Compose/OkHttp dependencies from Google's/Maven
  Central's repos (already declared in `settings.gradle.kts`)

## Known gaps / things worth deciding next

- `Socks5Checker` treats "tunnel stayed open, no immediate reset" as a
  success signal since a full MTProto handshake needs real crypto. It's a
  meaningfully better test than a plain TCP connect, but it is not a full
  protocol-level guarantee — worth flagging in the UI copy once TDLib
  lands, so users know the difference between "SOCKS5 tunnel reached
  Telegram" and "verified working Telegram session".
- The Telegram DC IP list in `NetworkUtils.kt` is hardcoded and may drift;
  a later version could fetch it from Telegram's public config.
- No persistence/history yet (matches spec — that's v0.3 reporting work).
