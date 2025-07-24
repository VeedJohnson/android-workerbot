# On-Device RAG-Powered Android Knowledge Base Assistant

> An offline-first Android app that ships with a built-in text knowledge base, breaks it into chunks, embeds & indexes them on-device, and answers user questions via a local LLM (MediaPipe GenAI).

---

## 🚀 Features

- **Pre-loaded TXT KB**  
  At first launch we load `knowledge_base_eng.txt` (in `assets/`) into a local NoSQL/vector store.

- **Whitespace Chunking**  
  We split text on paragraphs and words with overlap to preserve context boundaries.

- **On-Device Embeddings**  
  Generate 384-dim embeddings for each chunk via a lightweight MediaPipe Text Embedder.

- **Vector Store**  
  Ingest embeddings into an ObjectBox-backed in-memory vector database.

- **Retrieval Augmented Generation**  
  • **Retrieve** top-K similar chunks for each query  
  • **Augment** user query with retrieved context  
  • **Answer** via an on-device LLM (Mediapipe GenAI / Gemini Android SDK)

- **Streaming Responses**  
  Partial outputs appear in the UI as soon as they’re generated.

- **Koin DI**  
  All core components (KB loader, splitter, embeddings, ObjectBox, LLM) are wired via Koin.

---

## 📦 Setup

1. **Clone & open**
   ```bash
   git clone 
   cd Repo
   ./gradlew :app:build
   
2. **Push model to device**
   During development, push the on‑device LLM model via ADB:
   ```bash
   adb shell rm -rf /data/local/tmp/llm
   adb shell mkdir -p /data/local/tmp/llm
   adb push android/models/gemma3-1b-it-int4.task /data/local/tmp/llm/

3. **Run on device**  
   - Launch from Android Studio  
   - On first launch the app will:  
     1. Load `knowledge_base_eng.txt` from `assets/`  
     2. Chunk & embed it  
     3. Index in ObjectBox  
     4. Initialize the MediaPipe LLM session  
