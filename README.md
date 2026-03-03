# PipBoy Android (MVP)

This Android app connects to the `PipBoyBridge` UDP stream and renders:
- real-time player position (`x`,`y`)
- facing direction (`facing_deg`)
- explored overlay blocks (`map_overlay_sync`)

## Project Path
- `/Users/xinbiguo/Downloads/TurtleWoW/pipboy-android`

## Atlas Packaging
Atlas PNGs are bundled directly into APK assets:
- `app/src/main/assets/worldmap_atlas`

Sync source atlas into APK assets:

```bash
cd /Users/xinbiguo/Downloads/TurtleWoW/pipboy-android
scripts/sync-atlas-assets.sh
```

Atlas file naming rule:
- `<ZoneName>_atlas_rowmajor.png`
- example: `Elwynn_atlas_rowmajor.png`

## Build
Open `pipboy-android` in Android Studio and run `app` (debug), or use:

```bash
cd /Users/xinbiguo/Downloads/TurtleWoW/pipboy-android
scripts/build-apk.sh --http-proxy http://192.168.0.102:8080
```

This script auto-detects Android SDK, writes `local.properties`, enforces JDK 17,
and uses project-local `GRADLE_USER_HOME` to avoid global Gradle cache issues.

## Runtime Setup in App
1. Enter WoW bridge IP and port (default `38442`).
2. Tap `Connect`.

## Protocol Notes
- Handshake: `hello` -> `hello_ack`
- Position: `type=pos`
- Overlay sync: `type=map_overlay_sync`

## Current MVP Scope
- Single-device client render
- UDP receive + keepalive hello
- Dynamic fog-of-war by explored overlays
- Rotating marker by facing
