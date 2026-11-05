# Plan

## Done
- Gradle Kotlin DSL project, applicationId com.nightcinder.c4mediaremote, minSdk 30, targetSdk 35.
- Compose settings screen (address, port, token, room, lock, Always show controls) and Wi-Fi diagnostics screen.
- Foreground MediaSession service with remote VolumeProvider and custom actions (mute, cycle room, lock room).
- Real HTTP `HttpControllerClient` against the Media Remote Bridge (`/v1/status`, `/v1/command/<name>`).
- Status polling, now-playing metadata, volume/mute/power/source mirroring, auto-hide when idle.
- Settings in DataStore, bearer token in EncryptedSharedPreferences, boot/update restart receiver.

## Open
- Wear OS app and its verification (Pixel Watch media controls via the phone session work today).
- Automatic active-room following by location (BLE beacons or Wi-Fi RTT; diagnostics screen is groundwork).
- Narrow cleartext HTTP in network_security_config.xml, or move the bridge to HTTPS.
- More real-device testing of the MediaSession, VolumeProvider and RTT flags.
