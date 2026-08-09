# APK updates (keeping data) + the Play Protect warning

## Why every build needed a clean reinstall until now

Android only treats installing a new APK as an **update** (keeping the
app's data — including your logged-in Telegram session) if the new APK is
signed with the **same certificate** as the one already installed, and has
a **higher versionCode**. Two things were missing:

1. **Signing key changed every CI run.** Nothing in the workflow
   configured a signing key, so Gradle fell back to its default debug
   keystore — which, on a GitHub Actions runner, doesn't exist yet and
   gets freshly auto-generated on every single job. A different key every
   time means Android sees each APK as coming from a different (unknown)
   source and refuses to "update" over the old one.
2. **versionCode was hardcoded to `1`.** Even with a stable key, Android
   won't install an APK whose versionCode isn't higher than what's
   already installed.

Both are fixed now:

- `app/build.gradle.kts` sets `versionCode` from `GITHUB_RUN_NUMBER`
  (a variable GitHub Actions sets automatically for every job — first
  run is 1, then 2, 3, ... forever increasing), and `versionName` to
  match, e.g. `0.3-14`.
- A dedicated keystore (`proxytester.keystore`) was generated and is
  reused on every build via GitHub secrets — see below.

## One-time setup: add the keystore as repo secrets

I generated `proxytester.keystore.b64` (base64-encoded) and shared it +
the passwords separately from this repo (see `keystore-instructions.txt`
that came with it). Add these as repo secrets — Settings → Secrets and
variables → Actions → New repository secret:

```
KEYSTORE_BASE64    = <the full contents of proxytester.keystore.b64>
KEYSTORE_PASSWORD  = <given alongside the keystore>
KEY_ALIAS          = proxytester
KEY_PASSWORD        = <same value as KEYSTORE_PASSWORD — PKCS12 keystores
                        use one password for both>
```

Once these exist, every future build from this workflow is signed with
the same key, and installing a new APK over the old one on your phone
will show as a normal update — no uninstall, no lost session/settings.

**Keep the keystore file and passwords somewhere safe outside of GitHub
too** (e.g. a password manager). If it's ever lost, there's no way to
"update" over an install signed with it again — you'd have to uninstall
first and start over with a new key.

## The Google Play Protect warning

> "Hasn't seen an app from this developer before. It may be unsafe."

This is expected and not something the app (or this signing setup) can
fully turn off. Play Protect's reputation system is built around apps
distributed via Google Play; any APK installed from outside Play —
regardless of how it's signed or built — will show this warning the first
few times, because Google has no install history for that
signing certificate. Consistent signing (which we just set up) is a
prerequisite for that reputation to ever build up over time and for
updates to be recognized correctly, but it doesn't suppress the warning
by itself.

"Install anyway" is the correct, expected action here for a personal/dev
build like this one. The only way to avoid the warning entirely is
distributing through Google Play (even an internal testing track), which
is a much bigger step (Play Console account, store listing, review) and
not something to take on unless you actually want this distributed beyond
your own device.
