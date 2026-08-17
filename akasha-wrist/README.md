# Akasha Wrist v0.1

Voice-first Wear OS client for Akasha.

## Features
- Tap the animated orb to use Wear OS speech recognition.
- Sends transcribed speech to a configurable HTTPS relay.
- Displays replies and speaks them with Android Text-to-Speech.
- Long-press the orb to configure relay URL and optional shared token.
- Safe demo mode when no relay is configured.

## Security
The APK intentionally contains no OpenAI API key. Keep `OPENAI_API_KEY` server-side in the relay environment.

## Build
- compileSdk 34
- minSdk 30
- Java 17
- Android Gradle Plugin 8.2.2
- package `ai.akasha.wrist`

Build with `gradle :app:assembleDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install on Galaxy Watch4
Enable Developer options and Wireless debugging on the watch, connect ADB over Wi-Fi, then run `adb install -r app-debug.apk`.

## Relay
The included `relay/worker.js` expects `OPENAI_API_KEY` as a server-side secret and supports optional `AKASHA_TOKEN`, `AKASHA_SYSTEM_PROMPT`, and `AKASHA_MODEL` environment variables. Default model is `gpt-5.6`.
