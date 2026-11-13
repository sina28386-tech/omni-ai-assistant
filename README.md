# Omni AI Assistant

[فارسی](README.fa.md)

Omni is an experimental AI assistant workspace for Windows and Android. It combines a local desktop service and interface with an Android app, so people can connect their own model providers and use supported tools from one place. The interface includes Persian and English localization.

The project is under active development. Capabilities vary by platform, model, provider, device permissions, and operating system. A configured provider does not automatically mean that its model supports tool calling.

## What is in this repository

| Directory | Contents |
| --- | --- |
| [`desktop/`](desktop/) | React and TypeScript interface with a local Node.js service, conversations, provider profiles, workspaces, bots, and Windows accessibility tools. |
| [`windows-shell/`](windows-shell/) | Electron shell and Windows installer configuration for the desktop app. |
| [`android/`](android/) | Kotlin and Jetpack Compose application, device tools, provider profiles, and Android voice input. |

## Getting started

### Windows desktop

Install a current Node.js release supported by the dependencies and npm, then:

```powershell
cd desktop
npm ci
npm run build
npm start
```

Open `http://127.0.0.1:4317`. Provider credentials are supplied by the user in the app. To build an Electron installer, first build the desktop app, then on Windows:

```powershell
cd ..\windows-shell
npm install
powershell -ExecutionPolicy Bypass -File build.ps1
```

### Android

Install JDK 17, Android SDK Platform 36 and compatible build tools. Set the SDK path using Android Studio or a local `android/local.properties` file, then:

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. It is a development build, not a signed production release. Configure your own provider and permissions in the app.

## Device control and privacy

Some features use Android accessibility, microphone, media, contacts, or other platform permissions, or Windows accessibility and command execution. Availability and confirmation behavior depend on the action and user settings. Inspect a requested action and its destination before approving it. Not every application exposes controls that Omni can operate.

The Windows service stores conversations and provider data locally. Provider API keys are currently stored in a local `secrets.json` file without an operating-system credential vault. Android uses Android Keystore for protected app data. The chosen provider receives the content needed to answer a request; provider privacy policies and network availability apply. Do not expose the local desktop service to an untrusted network.

The Windows build can use a locally installed, signed-in Codex CLI when available. Direct Codex account sign-in on Android is not implemented. Android can use configured compatible model APIs, and its provider test can attempt a harmless tool call; an inconclusive result is not proof that tools are unsupported.

The optional legacy PC bridge is included at `android/scripts/hermes_pc_agent.py`. It is separate from the Windows desktop service, uses a bearer token and a fixed action allowlist, and listens on loopback by default. It does not provide TLS itself; use an encrypted connection and avoid exposing it directly to untrusted networks. The bridge does not provide an Android Codex login.

Android voice input uses the speech recognition service installed on the phone. Persian and English selection is available, but recognition quality, live partial results, connectivity, and language-pack availability depend on that service and device. Media search and playback support local files; online catalog search and playback are not established by the local media tool.

## Development checks

```powershell
cd desktop
npm run typecheck
npm test -- --configLoader runner
npm run build -- --configLoader runner
```

For Android, run `cd android` from the repository root and then `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Real-device permissions, speech recognition, external providers, and operating-system integrations require separate testing.

## Project status and contributions

Omni is an early-stage project. There is no claim here that it has a production release, verified adoption metrics, a completed security audit, or complete automation for every app. See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before contributing or reporting vulnerabilities. Omni's own code is licensed under the [MIT License](LICENSE). Third-party fonts are distributed under the [SIL Open Font License](desktop/public/fonts/OFL.txt).
