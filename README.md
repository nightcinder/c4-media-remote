# c4-media-remote

Android + Wear OS media remote for Control4 rooms (Wear OS is planned, not started).

Architecture:
- Phone app (Kotlin, Jetpack Compose) runs a foreground MediaSession with a remote VolumeProvider.
- Session media buttons and custom actions (mute toggle, cycle room, lock room) map to controller commands.
- Commands go over HTTP to the Control4 driver "Media Remote Bridge" (nightcinder/Control4-Drivers) on the EA-1.
- API: `GET /v1/status` and `POST /v1/command/<name>` with `Authorization: Bearer <token>`.
- Level and room are passed as query params: `level=` (volume) and `room=` (Upstairs/Downstairs).
- Settings (address, port 8765, active room, room lock) live in DataStore; the bearer token lives in EncryptedSharedPreferences.
- Diagnostics screen scans Wi-Fi and lists 802.11mc/az responder flags and WifiRttManager availability.

Current state: scaffold only. All controller calls are stubs that log. See PLAN.md.

Build: open in Android Studio, or run `gradlew assembleDebug`. Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 30).
