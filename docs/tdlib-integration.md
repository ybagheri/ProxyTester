# Wiring up TDLib

The code (`telegram/TdLibManager.kt`, `checker/MtprotoChecker.kt`) is
already written against TDLib's standard Java/JNI API
(`org.drinkless.tdlib.Client`/`TdApi`). What's still missing is the actual
library binary, because TDLib doesn't publish an official Maven artifact.

**Building TDLib for Android needs Docker and a fairly beefy machine (the
official process can take hours and multiple GB of RAM) — none of which
this sandbox has, and it also can't reach Google's Android SDK/Maven
servers at all (network is allowlisted to a small set of domains). So the
build step below has to happen somewhere with real internet access and
Docker: your own machine, or GitHub Actions (recommended — free, and
already set up in this repo).**

## Option A (recommended): let GitHub Actions build it

1. Push this project to a GitHub repo.
2. Get your `api_id` / `api_hash` (steps below) and add them as repo
   secrets: Settings → Secrets and variables → Actions → New repository
   secret → `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`.
3. Go to the Actions tab → "Build TDLib + APK (arm64-v8a)" → Run workflow.
4. It runs two jobs: build TDLib for Android via Docker, then assemble
   the app. When it finishes, download the `ProxyTester-arm64-v8a-debug`
   artifact from the run's summary page — that's your `.apk`.

This is untested by me end-to-end (I can't run GitHub Actions from here),
so the first run may need a small path fix in the "Wire TDLib into the
:tdlib module" step if TDLib's Docker output layout doesn't match what the
workflow expects — the "Inspect TDLib output layout" step right before it
prints the actual folder structure to diagnose that quickly.

## Option B: build it yourself locally

1. Clone https://github.com/tdlib/td
2. Follow the "Building for Android" section of `example/android/README.md`
   — either the Docker one-liner (`docker build --output tdlib .`) or the
   manual `check-environment.sh` / `fetch-sdk.sh` / `build-openssl.sh` /
   `build-tdlib.sh` scripts if you don't want Docker.
3. Copy the output into this project:
   - Java classes → `tdlib/src/main/java/org/drinkless/tdlib/`
   - `libtdjni.so` for `arm64-v8a` → `tdlib/src/main/jniLibs/arm64-v8a/`
4. Open the project in Android Studio (or run `./gradlew assembleDebug`
   with the Android SDK installed) to get the APK.

## Sanity check before trusting results

Before relying on `MtprotoChecker`, test it against a proxy you already
know works (e.g. one you're actively using in official Telegram apps) and
one you know is dead, to confirm `ConnectionStateReady` actually fires for
the good one within the 15s timeout in `TdLibManager`.

## Notes / trade-offs to keep in mind

- We're only keeping `arm64-v8a`, per your request — that's already
  enforced both in the CI workflow (it strips other ABI folders before
  packaging) and in `app/build.gradle.kts` (`ndk.abiFilters`). If you
  later need 32-bit devices or an emulator (which is usually x86_64),
  you'll need to add that ABI back in both places.
- `TdLibManager` intentionally never calls any auth/login TDLib methods —
  it only waits for a connection-state update, so no phone number or code
  is ever requested from the person running the proxy test.
