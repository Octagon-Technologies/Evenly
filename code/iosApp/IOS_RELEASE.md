# iOS release — archive and TestFlight

Companion to `../RELEASE_SIGNING.md` (Android). Signing team is `3VC8F74G23`, bundle id
`app.splitevenly`, automatic signing.

## Version numbers

Both live in `Configuration/Config.xcconfig`:

| Key | Meaning | Rule |
|---|---|---|
| `MARKETING_VERSION` | the "1.0" users see | bump per release |
| `CURRENT_PROJECT_VERSION` | build number | **must strictly increase per upload**; App Store Connect rejects a reused pair |

Keep these in step with `versionCode`/`versionName` in `../androidApp/build.gradle.kts` so a bug report's
version number means the same thing on both platforms.

## Archive

The heavy part is not Xcode, it is `:shared:linkReleaseFrameworkIosArm64` — device arm64 in opt mode,
several times slower and far more memory-hungry than any simulator build. Prove it links on its own
before spending an archive on it:

```bash
./gradlew -p code :shared:linkReleaseFrameworkIosArm64
```

Then archive. `-allowProvisioningUpdates` lets Xcode fetch/renew the profile rather than failing:

```bash
cd code/iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -allowProvisioningUpdates \
  -archivePath build/Evenly.xcarchive archive
```

Or Xcode → **Product → Destination → Any iOS Device** → **Product → Archive**. Same thing; the Organizer
window it opens is the easiest path to upload.

## Upload to TestFlight

From the Organizer: **Distribute App → App Store Connect → Upload**.

From the CLI you need an export step first, then `altool`/`notarytool`. The Organizer is less fuss for a
one-off; wire the CLI path only when there is CI to run it.

**Before the first upload ever succeeds**, the app record must exist: App Store Connect → **My Apps → +
→ New App**, bundle id `app.splitevenly`. Creating it is a one-time manual step and cannot be done from
this repo.

## Things that block a TestFlight build, in the order they bite

1. **Unsigned / wrong team** — automatic signing with team `3VC8F74G23`; the Apple ID running Xcode must
   be a member of it.
2. **No App Store Connect record** for `app.splitevenly` (see above).
3. **Reused `CURRENT_PROJECT_VERSION`** — always bump.
4. **Export compliance.** Pre-answered: `ITSAppUsesNonExemptEncryption=false` in `Info.plist`, accurate
   because the app's only crypto is HTTPS/TLS plus the OS keychain. Without that key every build parks
   in "Missing Compliance" until answered by hand.
5. **Entitlements the App ID does not have.** Adding e.g. `aps-environment` to
   `iosApp/iosApp.entitlements` by hand, without enabling Push Notifications on the App ID, breaks
   automatic signing. Use Xcode's *Signing & Capabilities* tab, which does both. See `PUSH_SETUP.md` §3.

## Deployment target

`IPHONEOS_DEPLOYMENT_TARGET = 18.2` in `project.pbxproj` — the Xcode 16.2 template default, not a
product decision, and it disagrees with the root `AGENTS.md`, which says iOS 16. At 18.2 the app will not
install on any device that cannot run iOS 18.2, which excludes iPhone X and older outright and every user
who has not updated. Lower it deliberately (and re-run the Release link plus a device smoke test) or
change the root `AGENTS.md` to say 18.2 — but do not leave the two disagreeing.

## Note: the scheme is not shared

There is no `iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme`; Xcode autocreates the scheme
per-user, so it works locally but a fresh clone or CI runner has no scheme to build. Tick **Manage
Schemes → Shared** and commit the result before wiring up CI.
