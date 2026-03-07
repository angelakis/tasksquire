# TaskSquire

An Android task manager compatible with [Taskwarrior](https://taskwarrior.org/) that can sync with [TaskChampion Sync-Server](https://github.com/GothenburgBitFactory/taskchampion-sync-server).

> **Warning**: This project is vibecoded. Expect bugs, rough edges, and potential security issues. Use at your own risk.

## Features

- Task management with priorities, projects, tags, due dates, and dependencies
- TaskChampion sync protocol (Taskwarrior 3.x compatible)

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

Taskwarrior is maintained by [Gothenburg Bit Factory](https://github.com/GothenburgBitFactory). TaskSquire is independently developed and is not affiliated with or endorsed by the Taskwarrior project. The Taskwarrior name is used solely to indicate compatibility with the TaskChampion sync protocol.

## License

[MIT](LICENSE)
