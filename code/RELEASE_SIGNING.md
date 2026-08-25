# Release signing — Android upload key

`:androidApp`'s `release` build type is signed from credentials that are **never committed**. Without
them the build still succeeds but produces an **unsigned** AAB, which Play rejects on upload (Gradle
prints a warning saying so).

## One-time: create the upload keystore

Run this once, from anywhere. Pick your own passwords; you will be prompted for a name/org (any
truthful value is fine, it is not shown to users).

```bash
keytool -genkeypair -v -keystore ~/keys/evenly-upload.jks -alias evenly-upload -keyalg RSA -keysize 2048 -validity 10000
```

**Back `evenly-upload.jks` up somewhere durable and private** (password manager, encrypted drive). Keep
it *outside* the repo — `code/.gitignore` blocks `*.jks`/`*.keystore` as a second line of defence, not
as the plan.

> Losing the upload key is recoverable *only* if Play App Signing is enabled for the app (it is the
> default for new apps): Google holds the real app-signing key, and you can request an upload-key reset.
> Losing it without Play App Signing means you can never update the listing again.

## Wire it up

Add to `code/local.properties` (gitignored, alongside the existing `sdk.dir` / `posthog.*` keys):

```properties
release.storeFile=/Users/you/keys/evenly-upload.jks
release.storePassword=...
release.keyAlias=evenly-upload
release.keyPassword=...
```

CI reads the same four values from environment variables instead:
`EVENLY_RELEASE_STORE_FILE`, `EVENLY_RELEASE_STORE_PASSWORD`, `EVENLY_RELEASE_KEY_ALIAS`,
`EVENLY_RELEASE_KEY_PASSWORD`. `local.properties` wins if both are set. A relative `storeFile` is
resolved against `code/androidApp/` first, then `code/`.

## Build the upload artifact

```bash
./gradlew -p code :androidApp:bundleRelease
```

Output: `code/androidApp/build/outputs/bundle/release/androidApp-release.aab`.

Verify it is actually signed before uploading — this prints the signer certificate, and errors out if
the bundle is unsigned:

```bash
jarsigner -verify -verbose:summary code/androidApp/build/outputs/bundle/release/androidApp-release.aab
```

## Version bumps

`versionCode` must strictly increase on every upload; Play rejects a reused one. Both live in
`code/androidApp/build.gradle.kts` under `defaultConfig`. The iOS equivalents are `MARKETING_VERSION`
and `CURRENT_PROJECT_VERSION` in `code/iosApp/Configuration/Config.xcconfig` — bump the two platforms
together so a bug report's version number is unambiguous.
