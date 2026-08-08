This module is intentionally empty in the repo.

It gets populated with TDLib's real build output:

  tdlib/src/main/java/org/drinkless/tdlib/Client.java
  tdlib/src/main/java/org/drinkless/tdlib/TdApi.java
  tdlib/src/main/java/org/drinkless/tdlib/Log.java
  tdlib/src/main/jniLibs/arm64-v8a/libtdjni.so

Two ways to get those files:

1. Automatically — push this repo to GitHub and run the
   ".github/workflows/build-tdlib-apk.yml" workflow (Actions tab ->
   "Build TDLib + APK (arm64-v8a)" -> Run workflow). It builds TDLib from
   source via Docker, drops the output here, and produces the final APK
   as a downloadable artifact. This is the recommended path — see
   docs/tdlib-integration.md.

2. Manually — follow https://github.com/tdlib/td/tree/master/example/android
   yourself (needs Docker or a full native build toolchain) and copy the
   output into the paths above.

Until one of those has run, `:app` will fail to build because it depends
on `org.drinkless.tdlib.Client`/`TdApi` from this module.
