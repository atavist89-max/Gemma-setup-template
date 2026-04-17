# Gemma-setup-template — On-Device Gemma 4 E2B Reference Project

> **Purpose:** This repository is a reference implementation for future Android projects that need on-device inference with **Gemma 4 E2B** via Google's [LiteRT-LM](https://ai.google.dev/edge/litert/models/litert_lm) engine. Use this as the canonical guide for model setup, file access patterns, permissions, and known hardware constraints.

![Build](https://github.com/atavist89-max/Gemma-setup-template/actions/workflows/build.yml/badge.svg)

---

## 1. On-Device Model Storage & Access

### 1.1 Required File on Device

Place the model file in the exact path below. The app uses a hard-coded absolute path (see `GhostPaths.kt`).

```
/storage/emulated/0/Download/GhostModels/gemma-4-e2b.litertlm
```

| File | Purpose | Min Size Check |
|------|---------|----------------|
| `gemma-4-e2b.litertlm` | On-device LLM model | `> 2 GB` |

### 1.2 How the Model Is Accessed in Code

All paths are centralized in **`GhostPaths.kt`**:

```kotlin
object GhostPaths {
    val BASE_DIR = File("/storage/emulated/0/Download/GhostModels")
    val MODEL_FILE = File(BASE_DIR, "gemma-4-e2b.litertlm")

    fun isModelAvailable(): Boolean = MODEL_FILE.exists() && MODEL_FILE.length() > 1_000_000_000L
}
```

**Key access pattern:**
- **Model:** Passed as `modelPath = MODEL_FILE.absolutePath` to `EngineConfig`.

---

## 2. Mandatory Permissions

### 2.1 Android Manifest

```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

> **Note:** `READ_EXTERNAL_STORAGE` is **insufficient** on Android 11+ (API 30+) because the model lives outside app-scoped directories.

### 2.2 Runtime Permission Check (Android 11+)

Use `Environment.isExternalStorageManager()`:

```kotlin
private fun hasStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}
```

### 2.3 Requesting Permission

Redirect the user to system settings because `MANAGE_EXTERNAL_STORAGE` cannot be granted via a normal runtime dialog:

```kotlin
private fun requestStoragePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
```

### 2.4 Lifecycle-Aware Re-Check

Re-check permission in `ON_RESUME` so the UI updates automatically when the user returns from settings:

```kotlin
val observer = LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_RESUME) {
        hasPermission = hasStoragePermission()
    }
}
lifecycle.addObserver(observer)
```

---

## 3. LiteRT-LM Engine Setup (Gemma 4 E2B)

### 3.1 Dependency

```kotlin
// app/build.gradle
implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
```

### 3.2 Engine Initialization

Initialize **once** and reuse. The engine is **not thread-safe**.

```kotlin
val config = EngineConfig(
    modelPath = GhostPaths.MODEL_FILE.absolutePath,
    backend = Backend.GPU(),        // Falls back to CPU if GPU unavailable
    maxNumTokens = 2048,            // See Section 4 for hard limits
    cacheDir = cacheDir.path
)
val engine = Engine(config)
engine.initialize()
```

### 3.3 Inference Call

Always run on a background thread (`Dispatchers.IO`). Use a single `Conversation` per call:

```kotlin
val conversation = engine.createConversation(
    ConversationConfig(
        samplerConfig = SamplerConfig(
            temperature = 0.5,
            topK = 40,
            topP = 0.9
        )
    )
)
conversation.use {
    val response = it.sendMessage(Message.of(prompt))
    val text = response.toString()
}
```

### 3.4 Cleanup

Close the engine in `onDestroy()`:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    engine?.close()
}
```

---

## 4. Technical Constraints (Verified by Testing)

### 4.1 LLM Context Window

| Specification | Reality |
|---------------|---------|
| Advertised: 128K tokens | **Actual: ~2,500 tokens hard limit** |
| Practical safe limit | **2,000 tokens** |
| Failure mode | Any prompt > 2,500 tokens crashes with `INVALID_ARGUMENT` |

**Implication:** All prompts must be chunked to **< 2,000 tokens** to leave headroom for prompt-template overhead.

### 4.2 Verified Chunking Strategy

| Input Type | Guideline |
|------------|-----------|
| Free-text prompts | Keep under ~1,500 tokens to leave room for the response |
| Pre-processed context | Chunk to < 2,000 tokens per inference call |
| Max sequential calls | 10–20 (battery/heat constraints) |

### 4.3 Processing Architecture

- **Concurrency: NONE.** The LiteRT-LM Engine is single-threaded. Parallel inference attempts crash with `LiteRtLmJniException`.
- **Pattern:** Sequential pipeline with a **10-second cooldown** between LLM calls.
- **State Management:** Pass a shared state object between agents; do not access it concurrently.

### 4.4 Battery & Thermal Constraints

| Metric | Value |
|--------|-------|
| Per inference cost | 2–3% battery, 1–3 seconds |
| Maximum sustained | 3–4 inferences before GPU throttling |
| Cooldown between calls | 10 seconds |
| Extended cooldown (warm device) | 2 minutes |

### 4.5 Memory Constraints

| Metric | Value |
|--------|-------|
| Model loading overhead | +2.5 GB RAM when Engine initializes |
| Safe headroom | Maintain > 2 GB available RAM |

---

## 5. Project Structure

```
app/src/main/java/com/llmtest/
├── BugLogger.kt      # File-based timestamped logger (+ View Logs / Copy Logs)
├── GhostPaths.kt     # Hard-coded absolute path to the model
└── MainActivity.kt   # UI (Jetpack Compose), LLM inference, tests

app/src/main/AndroidManifest.xml   # MANAGE_EXTERNAL_STORAGE + native libs
app/build.gradle                    # Dependencies (litertlm-android:0.10.0)
```

---

## 6. Build & Install

**Requirements:**
- Android device running **API 36+ (Android 16+)**
- **JDK 21**
- **Android SDK 36**

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. CI

GitHub Actions (`.github/workflows/build.yml`) builds the debug APK on every push and PR to `main`.

---

## 8. License

Personal test / debugging project. No production warranty implied.
