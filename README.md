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

## Requirements

- Android 8.0+ (API 26)

## Disclaimer

TaskSquire is an independent project and is not affiliated with or endorsed by [Gothenburg Bit Factory](https://github.com/GothenburgBitFactory) or the Taskwarrior project.

## License

[MIT](LICENSE)
