# Camera Stream Android App

Android app that streams camera MJPEG video over HTTP to PC browser.

## Features

- Real-time MJPEG streaming via HTTP port 8080
- Camera switch (front/back) via web UI button
- Foreground service keeps streaming in background

## Requirements

- Android 7.0 (API 24) or higher
- Camera hardware
- WiFi connection (PC and phone must be same network)

## How to Use

1. Install APK on Android phone
2. Open app, tap **START**
3. On PC browser, go to `http://<phone-ip>:8080/stream`
4. Click "Switch Camera" button to toggle front/back camera

## Build & Run

```bash
cd CameraStream
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Endpoints

| Method | Path             | Description              |
|--------|------------------|--------------------------|
| GET    | `/stream`        | MJPEG video stream       |
| GET    | `/`              | HTML control page        |
| POST   | `/switch-camera` | Switch camera (front/back) |

## Architecture

- `MainActivity.kt`: UI start/stop, permission handling
- `CameraService.kt`: Camera2 capture, MJPEG HTTP server
- HTTP server runs in background thread, serves frames from `latestFrame`

## Network

- Phone and PC must be on same WiFi
- Get phone IP from app UI or logcat (`getLocalIpAddress()`)
