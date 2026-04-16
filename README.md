# ACIS — Minimal LLM Test App

A minimal Android app to test on-device inference with **Gemma 4 E2B** using Google's [LiteRT-LM](https://ai.google.dev/edge/litert/models/litert_lm) engine.

![Build](https://github.com/atavist89-max/ACIS/actions/workflows/build.yml/badge.svg)

## What it does

- Displays a single-screen Compose UI with a status indicator.
- Tries to load a local `.litertlm` model using the **GPU** backend first.
- Falls back to the **CPU** backend if GPU fails.
- Shows detailed error messages (exception class + message) when something goes wrong so you can debug quickly.

## Requirements

- **Android device** running API 36+ (Android 16+).
- **Model file** placed at:
  ```
  /storage/emulated/0/Download/GhostModels/gemma-4-e2b.litertlm
  ```
  The app checks that the file exists and is larger than 1,000 MB before attempting to load it.
- **JDK 21** to build (required by `litertlm-android:0.10.0`).
- **Android SDK 36**.

## Tech Stack

- **Language:** Kotlin 2.1.20
- **UI:** Jetpack Compose
- **Inference Engine:** `com.google.ai.edge.litertlm:litertlm-android:0.10.0`
- **Build:** Gradle 8.6, Android Gradle Plugin 8.3.0

## Build

```bash
./gradlew assembleDebug
```

The APK is produced at:
```
app/build/outputs/apk/debug/app-debug.apk
```

## Install & Run

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Launch the app, tap **"Test LLM Connection"**, and watch the status light:

| Color | Meaning |
|-------|---------|
| Gray  | Idle |
| Yellow| Testing |
| Green | Connected (GPU or CPU) |
| Red   | Failed — read the error message below the light |

## Debugging Backend Failures

If both backends fail, the app prints the exact exception on screen, e.g.:

```
GPU: UnsatisfiedLinkError: dlopen failed...
CPU: IllegalArgumentException: Invalid model format...
```

This helps distinguish between:
- Missing/corrupted model files
- Missing native libraries (`libOpenCL.so`, `libvndksupport.so`)
- Incompatible model format
- Out-of-memory errors

## Project Structure

```
app/src/main/java/com/llmtest/MainActivity.kt  # UI + connection test logic
app/src/main/AndroidManifest.xml                # Permissions & native libs
app/build.gradle                                # App module build config
build.gradle                                    # Root project plugins
settings.gradle                                 # Repositories & modules
```

## CI

A GitHub Actions workflow (`.github/workflows/build.yml`) builds the debug APK on every push and pull request to `main`.

## License

This is a personal test / debugging project. No production warranty implied.
