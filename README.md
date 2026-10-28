# TaskSquire

An Android task manager that syncs with [Taskwarrior](https://taskwarrior.org/) via the [TaskChampion sync protocol](https://github.com/GothenburgBitFactory/taskchampion-sync-server). Manage your tasks on mobile and keep them in sync with your Taskwarrior 3.x setup on desktop.

> **Warning**: This project is vibe coded. I would be surprised if it's not full of bugs or security issues. Use at your own risk.

## Features

- **Task management** — priorities, projects, tags, due dates, dependencies, recurrence, and annotations
- **Sync** — bidirectional sync with TaskChampion Sync-Server, with client-side encryption and automatic conflict resolution
- **Auto-sync** — periodic background sync and sync-on-open via WorkManager
- **Filtering** — search and filter by project, tags, priority, due date, and dependency status
- **Import/Export** — bulk import tasks from JSON
- **Material You** — built with Jetpack Compose and Material 3

## Building

```bash
# Debug
./gradlew assembleDebug

# Release (minified)
./gradlew assembleRelease
```

Both are signed with the stable key when `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are set; otherwise debug uses the local debug key and release is unsigned.

### With Nix

With [Nix](https://nixos.org/) (flakes enabled), no JDK or Android SDK needs installing:

```bash
nix run                                 # debug APK → app/build/outputs/apk/debug/app-debug.apk
nix run . -- test assembleRelease       # any Gradle tasks instead
nix develop                             # shell with the same JDK 17 and SDK, for ./gradlew
```

`nix run` brings in JDK 17 and an Android SDK (platform 34, build tools 34.0.0, licenses accepted), sets the version code to the commit count like CI, and runs `./gradlew`. The signing variables above still apply. It also works on NixOS: the SDK's aapt2 is used in place of the one AGP downloads from Maven, which doesn't run there.

## CI/CD

- `.github/workflows/ci.yml` builds an unsigned release APK on PRs to `main`, to catch build and R8 errors. It publishes no APK.
- `.github/workflows/release.yml` runs on `v*` tags and attaches `tasksquire.apk` to a GitHub Release (version name from the tag).
- Both build through the flake (`nix run`), so CI uses the same JDK, Android SDK and aapt2 as a local build, pinned by `flake.lock`. A release can be checked by rebuilding its tag on x86_64 Linux with the signing variables set:

  ```bash
  git checkout v1.0.0
  VERSION_NAME=v1.0.0 nix run . -- assembleRelease
  sha256sum app/build/outputs/apk/release/app-release.apk   # same as the release's tasksquire.apk
  ```

Every release is signed with the same key (from the secrets below) and its version code is the commit count, so a newer release installs as an update and keeps your data. Without the secrets, the release APK is unsigned.

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

## Requirements

- Android 8.0+ (API 26)

## Disclaimer

TaskSquire is an independent project and is not affiliated with or endorsed by [Gothenburg Bit Factory](https://github.com/GothenburgBitFactory) or the Taskwarrior project.

## License

[MIT](LICENSE)
