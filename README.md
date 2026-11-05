# c4-media-remote

Android media remote for Control4 rooms. It shows up as a normal media card
(lock screen, notification shade, Pixel Watch media controls) and drives the
room's audio/video through a custom Control4 driver on the EA-1. A native Wear OS
app is planned, not started.

## How it works

- The phone app runs a foreground service with a `MediaSessionCompat` and a remote
  `VolumeProvider`, so hardware volume keys and the watch crown control the room volume.
- Transport buttons (play/pause, next, previous) and custom actions (mute toggle,
  cycle room, lock room) become HTTP commands to the **Media Remote Bridge** driver
  (`drivers/media-remote-bridge-untested` in
  [nightcinder/Control4-Drivers](https://github.com/nightcinder/Control4-Drivers)).
- The service polls `GET /v1/status` (every 3 s while media is active, every 10 s
  otherwise) and mirrors volume, mute, power, source and now-playing
  (title, artist, app, play state) onto the session.
- When nothing is playing the media card is hidden (after 3 idle polls) and only a
  minimal notification remains. Turn on **Always show controls** to keep the card visible.
- Volume changes are queued and coalesced, and polled volume does not overwrite a
  pending local change, so the slider does not snap back.
- Rooms are Upstairs and Downstairs. **Cycle room** switches the active room;
  **Lock room** pins it so cycling is ignored.
- A boot/app-update receiver restarts the service, but only if a bearer token is configured.

## Bridge API

All calls need `Authorization: Bearer <token>`. Plain HTTP on the LAN, default port 8765.

| Call | Purpose |
|---|---|
| `GET /v1/status` | active room, power, volume, mute, source, lock, now-playing fields |
| `POST /v1/command/<name>` | `play`, `pause`, `toggle`, `next`, `prev`, `mute`, `unmute`, `volume_up`, `volume_down`, `volume_set?level=N`, `set_active_room?room=Upstairs` |

Newer status fields (volume 0.3.0+, now-playing 0.4.0+ of the driver) are optional;
the app copes with a bridge that does not report them. Use a current driver build
(0.4.4 at the time of writing).

## Settings

Address, port, active room, room lock and Always show controls are stored in DataStore.
The bearer token is stored in EncryptedSharedPreferences. The settings screen has
**Test connection** and **Start media session**. A Wi-Fi diagnostics screen lists
nearby APs with 802.11mc/az responder flags and WifiRttManager availability (groundwork
for room detection by location).

## Build

Open in Android Studio, or run `./gradlew assembleDebug`. Needs JDK 17+ and the Android
SDK (compileSdk 35, minSdk 30, targetSdk 35). The app id is `com.nightcinder.c4mediaremote`.
A logging `StubControllerClient` exists for debugging (`USE_STUB_CONTROLLER` in
`MediaRemoteService`); release behaviour uses `HttpControllerClient`.

## Status and caveats

- Working: real HTTP client, now-playing, volume, mute, room cycle/lock, polling, auto-hide.
- Cleartext HTTP is allowed for all hosts in `network_security_config.xml` because the
  controller is a user-entered LAN IP. Narrow it or move the bridge to HTTPS before any release.
- Wear OS app, room auto-follow by location (BLE beacons or Wi-Fi RTT), and more
  on-device testing are still open. See [PLAN.md](PLAN.md).
