# WibeChat Android SDK

Real-time audio, video and data for Android apps, built on WebRTC.

Supports Android API level 21 and above.

## Installation

The SDK is on Maven Central. One of its dependencies is served from JitPack, so add
both repositories in `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Then add the SDK to your module's `build.gradle`:

```groovy
dependencies {
    implementation "com.wibechat:wibechat-android:<version>"

    // Optional: CameraX support
    implementation "com.wibechat:wibechat-android-camerax:<version>"

    // Optional: track processors such as virtual background
    implementation "com.wibechat:wibechat-android-track-processors:<version>"
}
```

## Quick start

```kotlin
val room = WibeChat.create(applicationContext)

lifecycleScope.launch {
    room.connect(url = wsUrl, token = token)
    room.localParticipant.setMicrophoneEnabled(true)
    room.localParticipant.setCameraEnabled(true)
}
```

Observe state from Compose or flows through `@FlowObservable` properties:

```kotlin
val participants by room::remoteParticipants.flow.collectAsState()
```

## Sample apps

| Module                    | Description                                 |
|---------------------------|---------------------------------------------|
| `sample-app`              | Full-featured video call app (Views)        |
| `sample-app-compose`      | Full-featured video call app (Compose)      |
| `sample-app-basic`        | Minimal connect-and-publish example         |
| `sample-app-record-local` | Records local audio and video to a file     |

Install one with `./gradlew sample-app-compose:installDebug`.

## Building

```sh
./gradlew assemble          # build all modules
./gradlew test              # unit tests
./gradlew spotlessCheck     # code style
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
