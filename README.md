# Taskwarrior Android

An Android app for [Taskwarrior](https://taskwarrior.org/) that syncs with a [TaskChampion](https://github.com/GothenburgBitFactory/taskchampion)-compatible sync server.

## Features

- **Task Management**: Create, view, complete, and delete tasks
- **Priority & Projects**: Assign priorities (H/M/L) and organize tasks by project
- **Tags & Due Dates**: Add tags and due dates to tasks
- **Urgency Scoring**: Tasks sorted by computed urgency (priority + due date + age)
- **TaskChampion Sync**: Full sync with any TaskChampion-compatible server
- **Offline First**: All data stored locally in Room database; sync when online
- **Material You**: Adaptive theming with light/dark mode support

## Sync Protocol

This app implements the **TaskChampion sync protocol**, which is the sync mechanism used by Taskwarrior 3.x. It is compatible with:

- [`taskchampion-sync-server`](https://github.com/GothenburgBitFactory/taskchampion/tree/main/sync-server)
- Any server implementing the TaskChampion HTTP sync API

### Protocol Overview

The sync protocol uses a version-based operation log:

1. **Operations** represent individual task changes (Create, Update property, Delete)
2. **Versions** are UUIDs identifying points in the operation log
3. **Snapshots** are full task database dumps used as checkpoints

#### HTTP Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/client/snapshot` | Fetch current snapshot |
| POST | `/v1/client/add-snapshot/{version_id}` | Upload new snapshot |
| GET | `/v1/client/get-child-version/{parent_version_id}` | Get child version |
| POST | `/v1/client/add-version/{parent_version_id}` | Upload new operations |

#### Authentication

Client identity is sent via `X-Client-Id` (UUID). Payloads are encrypted client-side using the encryption secret.

### Sync Configuration

In the app, go to **Settings** and configure:

- **Server URL**: e.g. `https://sync.example.com`
- **Client ID**: A UUID uniquely identifying this device (auto-generated)
- **Encryption Secret**: The password for your sync account

## Project Structure

```
app/src/main/java/com/taskwarrior/android/
├── MainActivity.kt              # Entry point, navigation setup
├── data/
│   ├── Task.kt                  # Task data model
│   ├── TaskDao.kt               # Room DAO
│   ├── TaskDatabase.kt          # Room database
│   ├── TaskRepository.kt        # Data access layer
│   └── Converters.kt            # Room type converters
├── sync/
│   ├── SyncModels.kt            # Operation/Snapshot/SyncResult types
│   ├── JsonSerializer.kt        # JSON encoding/decoding for sync protocol
│   ├── TaskChampionSyncClient.kt # HTTP client for sync server
│   ├── OperationBuilder.kt      # Task <-> Operation conversion
│   └── SyncManager.kt           # Full sync orchestration
└── ui/
    ├── Theme.kt                  # Material You theming
    ├── TaskViewModel.kt          # ViewModel (business logic)
    ├── TaskListScreen.kt         # Main task list
    ├── AddTaskScreen.kt          # New task form
    ├── TaskDetailScreen.kt       # Task detail view
    └── SettingsScreen.kt         # Sync server settings
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Database**: Room (SQLite)
- **Networking**: OkHttp
- **Settings**: DataStore Preferences
- **Navigation**: Navigation Compose
- **Architecture**: MVVM

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0+ (API 26+)
- Internet permission (for sync)

## License

GPL-3.0 — same as Taskwarrior itself.
