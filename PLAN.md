# Plan

## Done (scaffold)
- Gradle Kotlin DSL project, applicationId com.nightcinder.c4mediaremote, minSdk 30, targetSdk 35.
- Compose settings screen and Wi-Fi diagnostics screen.
- Foreground MediaSession service with remote VolumeProvider and custom actions (stubs).
- Settings store (DataStore) and encrypted bearer token storage.
- ControllerClient interface with a logging StubControllerClient.

## Not implemented yet
- Real networking in ControllerClient (HTTP to the Media Remote Bridge on the EA-1).
- Now-playing metadata on the MediaSession.
- Wear OS app and its verification.
- Narrowing cleartext HTTP in network_security_config.xml (currently allowed for all hosts).
- Real device testing of the MediaSession, VolumeProvider, and RTT flags.
