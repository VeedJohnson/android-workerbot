# On-Device RAG WorkerBot Android App

This repository contains an Android application that implements Retrieval-Augmented Generation (RAG) on-device using MediaPipe for LLM inference and an in‑memory vector store for fast context retrieval.

## Prerequisites

1. **Android Studio**
2. **JDK 21**
3. **ADB access** to a physical device with API 26+
4. **Hugging Face access token** with permission to download the task model

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/VeedJohnson/android-workerbot
cd android-workerbot
```

### 2. Configure Local Properties

Create (or update) the file `local.properties` in the root of the project with your Hugging Face token:

```properties
# local.properties
HF_TOKEN=hf_xxxYourTokenHere
```

The Gradle build reads `HF_TOKEN` and embeds it into `BuildConfig.HF_TOKEN` at compile time.

### 3. Set Up Model Download

On first app launch, the model will be downloaded from Hugging Face and cached in the app's private storage.\
**Ensure your device has Internet access.**

### 4. Build & Run

1. Open the project in Android Studio.
2. Let Gradle sync and build.
3. Connect an Android device.
4. Run the `app` configuration.

*On success, the splash screen will appear, initialize the knowledge base, download the model, and open the chat interface.*

### 5. Permissions

No special Android permissions are required beyond network access.

## Project Structure

- `app/src/main/java/com/veedjohnson/workerbot`: Kotlin source
  - `domain/`: LLM API, model downloader, RAG prompt builder
  - `data/`: ObjectBox definitions and DB helpers
  - `ui/`: Compose screens (Splash, Chat)
- `app/src/main/assets/`: Static knowledge base text file(s) and embedder
- `ModelDownloaderService.kt`: Fetches and caches the `.task` file
- `LLMAPI.kt`: Wraps MediaPipe LLM inference

## Troubleshooting

- **Download failures:** Check network and valid HF token.
- **Out-Of-Range errors:** Ensure model’s `maxTokens` ≤ model’s context window.

---
