# Aaria — Project Details

> Last updated: March 2026

---

## Table of Contents

1. [What is Aaria](#1-what-is-aaria)
2. [Vision & Goals](#2-vision--goals)
3. [Platform & Distribution](#3-platform--distribution)
4. [Architecture Overview](#4-architecture-overview)
5. [Module Reference](#5-module-reference)
6. [Implemented Features (Phase-by-Phase)](#6-implemented-features-phase-by-phase)
7. [Tech Stack](#7-tech-stack)
8. [Key Data Flows](#8-key-data-flows)
9. [Permissions](#9-permissions)
10. [Settings & Configuration](#10-settings--configuration)
11. [Known Limitations](#11-known-limitations)
12. [Known Risks & Mitigations](#12-known-risks--mitigations)
13. [Remaining Scope (Phases 7–8)](#13-remaining-scope-phases-78)
14. [Future Ideas (Post-v1)](#14-future-ideas-post-v1)
15. [Project Setup & Build](#15-project-setup--build)

---

## 1. What is Aaria

Aaria is an Android-only, hands-free voice assistant layered on top of WhatsApp. When WhatsApp messages arrive, Aaria reads them aloud through earbuds. The user replies by speaking — the voice is transcribed on-device and sent as a WhatsApp reply without touching the phone.

**Hinglish (Roman-script Hindi mixed with English) is treated as a first-class language**, not an afterthought. Every layer — from text processing to TTS voice selection — is designed with Hinglish in mind.

The long-term vision is a full ambient voice interface for messaging. WhatsApp voice messaging is the first shipped feature.

---

## 2. Vision & Goals

| Goal | Status |
|---|---|
| Read WhatsApp messages aloud, hands-free | Done |
| Reply by voice with on-device STT | Done |
| Natural Hinglish reading (voice switching per segment) | Done |
| Multi-message queue with voice contact selection | Done |
| Driving, Focus, Silent, Normal modes | Partially done (infra complete, voice switching TBD) |
| Zero monthly cost — all on-device or free tier | Done |
| No ToS violations — only official Android APIs | Done |
| Android-only sideloaded APK (not Play Store) | Done |

---

## 3. Platform & Distribution

- **Platform:** Android only (minSdk 26 / Android 8.0+, targetSdk 34)
- **Distribution:** Sideloaded APK — not published on Google Play
- **Reason for Android-only:** iOS sandboxing prevents `NotificationListenerService` and background audio capture
- **Package ID:** `com.aaria.app`
- **Version:** 0.1.0

---

## 4. Architecture Overview

### Incoming Pipeline

```
WhatsApp notification arrives
        ↓
WhatsAppNotificationListener (NotificationListenerService)
  - Filter by package (com.whatsapp / com.whatsapp.w4b)
  - Extract: sender, text, group name, RemoteInput action
  - Store RemoteInput in RemoteInputStore immediately
        ↓
MessageQueue.enqueue(MessageObject)
  - Deduplication by message ID
  - Group by senderKey
  - Notify all registered listeners
        ↓
AariaForegroundService (pipeline gate — pipelineBusy AtomicBoolean)
        ↓
Text Intelligence Pipeline (IncomingTextProcessor)
  1. EmojiProcessor       — emoji → spoken description
  2. AbbreviationExpander — Hinglish shorthand → full words
  3. LanguageDetector     — ML Kit: Hindi/English ratio, script type
  4. WordTagger           — per-word Hindi/English label
  5. Transliterator       — Roman Hindi → Devanagari
  6. SsmlBuilder          — wrap segments in <lang xml:lang="..."> tags
        ↓
MessageReader → TtsManager → AudioFocusManager
        ↓
User hears message in earbuds
```

### Outgoing Pipeline

```
TTS finishes reading last message from a sender
        ↓
AariaForegroundService.openReplyWindow()
  - WakeWordEngine starts listening (LISTENING_FOR_REPLY state)
  - 10-second timeout; if no wake word → continue queue
        ↓
"Porcupine" (wake word) detected
        ↓
DualStopDetector.start(outputWavFile)
  - AudioRecorder begins PCM capture → WavWriter
  - SilenceDetector: 1.5s silence → stop
  - WakeWordEngine (LISTENING_FOR_STOP): "Terminator" → stop
  (first to fire wins)
        ↓
SttManager.transcribe(wavFile)
  - SherpaWhisperEngine: Whisper Base int8 multilingual, on-device
  - Language biased to "hi" (Hinglish)
        ↓
OutgoingTextCleaner.clean(rawText)
  - Strip stop words ("Done", "Send")
  - Devanagari → Roman conversion
  - Filler word removal ("um", "uh")
  - Light punctuation/capitalization fix
        ↓
MessageReader.announce("Sending: [cleaned text]")
        ↓
3-second auto-send window
  - WakeWordEngine (LISTENING_FOR_CANCEL): "cancel" → abort
        ↓
ReplyManager.sendReply() — fires RemoteInput to WhatsApp
        ↓
"Sent. N messages remaining." → continue queue pipeline
```

### Queue Pipeline (Multi-Message)

```
After each conversation is read+replied:
        ↓
continueQueueOrFinish()
  0 remaining → pipeline idle
  1 remaining → readConversation() directly
  2+ remaining → announceAndSelect()
        ↓
QueueAnnouncer.announceRemaining()
  "2 messages remaining — Mom, Team group.
   Say 1 for Mom, 2 for Team group, or say later."
        ↓
ContactSelector.listen()
  - Android SpeechRecognizer burst (offline, fast)
  - Resolves: "1"/"one"/"ek" → senderKey[0]
              fuzzy name match → senderKey[n]
              "later"/"skip"/"baad mein" → finishPipeline
  - One automatic retry on NoMatch
        ↓
readConversation(selected senderKey) → loop
```

---

## 5. Module Reference

### Application Layer

| File | Purpose |
|---|---|
| `AariaApplication.kt` | Application class. Initialises and owns all singletons as `lateinit var` properties. Single source of truth for shared state (`recordingStatus`, `currentReplyTarget`). |
| `MainActivity.kt` | Debug/control UI. Notification access status, battery optimisation status, SSML probe status, mode selector, mark-as-read toggle, recording indicator, live message + language-analysis log. |

### Notification Layer (`notification/`)

| File | Purpose |
|---|---|
| `WhatsAppNotificationListener.kt` | `NotificationListenerService`. Filters strictly by WhatsApp package names. Extracts sender, text, group name. Calls `MessageExtractor` + stores `RemoteInput`. Wires `onCancelNotification` and `onTriggerMarkAsRead` callbacks. |
| `MessageExtractor.kt` | Stateless helper: parses `StatusBarNotification` → `MessageObject`. Handles group vs DM detection. |
| `RemoteInputStore.kt` | Thread-safe map of `senderKey → StoredAction`. Tracks whether each action has expired. `retrieve()` returns `null` if expired. |
| `MarkAsReadStore.kt` | Records which message IDs have been marked as read to avoid double-marking. |

### Queue Layer (`queue/`)

| File | Purpose |
|---|---|
| `MessageObject.kt` | Immutable data class: `id`, `sender`, `text`, `isGroup`, `groupName`, `senderKey`, `timestamp`, `replyAvailable`, `notificationPackage`, `notificationTag`, `notificationId`. |
| `MessageQueue.kt` | Thread-safe `ConcurrentLinkedQueue`. Deduplication by `id`. Multi-listener support. Methods: `enqueue`, `pendingSenderKeys`, `findBySenderKey`, `removeAllBySenderKey`, `addListener`, `removeListener`. |
| `QueueAnnouncer.kt` | Builds and speaks "N messages remaining — A, B. Say 1 for A, 2 for B, or say later." Calls `onDone` with the ordered `pendingKeys` list when TTS finishes. |
| `ContactSelector.kt` | Short Android `SpeechRecognizer` burst. Resolves spoken number (digit or word, including punctuation-stripped variants and per-word parsing), fuzzy name match, or "later"/"skip"/"baad mein". Returns `SelectionResult`: `Selected`, `Later`, `NoMatch`, or `Error`. |

### Intelligence — Incoming (`intelligence/incoming/`)

| File | Purpose |
|---|---|
| `IncomingTextProcessor.kt` | Orchestrates the full pipeline: emoji → abbrev → detect → tag → transliterate → SSML. Returns `ProcessedMessage(original, plainText, ssml, languageProfile)`. |
| `EmojiProcessor.kt` | Converts emoji to spoken descriptions. Also handles WhatsApp media placeholders ("📷 Photo" → "shared a photo"). |
| `AbbreviationExpander.kt` | ~170-entry Hinglish dictionary. "yr"→"yaar", "h"→"hai", "tbh"→"to be honest", etc. Case-insensitive whole-word matching. |
| `LanguageDetector.kt` | ML Kit `LanguageIdentification`. Outputs `LanguageProfile`: `primary` language, `hindiRatio`, `englishRatio`, `script` (Latin/Devanagari/Mixed). |
| `WordTagger.kt` | Per-word Hindi/English classification using vocabulary sets (`HINDI_VOCAB`, `ENGLISH_VOCAB`) and suffix heuristics (`HINDI_SUFFIXES`). Falls back to `LanguageProfile` for ambiguous words. |
| `Transliterator.kt` | Rule-based Roman Hindi → Devanagari mapping. Word dictionary + phoneme rules. English words pass through untouched. |
| `SsmlBuilder.kt` | Groups consecutive same-language words into segments, wraps each in `<lang xml:lang="hi-IN">` or `<lang xml:lang="en-IN">`, assembles final `<speak>` SSML string. |

### Intelligence — Outgoing (`intelligence/outgoing/`)

| File | Purpose |
|---|---|
| `OutgoingTextCleaner.kt` | Post-processes Whisper transcription. Strips stop words, Devanagari→Roman, removes fillers ("um", "uh"), light punctuation correction. |

### TTS Layer (`tts/`)

| File | Purpose |
|---|---|
| `TtsManager.kt` | Wraps Android `TextToSpeech`. Sequential job queue (`TtsQueue`). SSML probe on init (`ssmlSupported: Boolean?`). `speak(text)`, `speakSsml(ssml, plainFallback)` — falls back to plain text if SSML unsupported. |
| `TtsQueue.kt` | Internal serial job queue. Ensures utterances never overlap. |
| `MessageReader.kt` | Higher-level wrapper. Suppresses TTS during phone calls. Builds group-message prefix ("Message from X in Y group: …"). Chooses SSML vs plain path. Exposes `announce(text)` for system strings. |

### Audio Layer (`audio/`, `recording/`)

| File | Purpose |
|---|---|
| `AudioFocusManager.kt` | `AudioFocusRequest` lifecycle. `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — ducks music. `isInCall()` check. |
| `AudioRecorder.kt` | `AudioRecord`-based PCM capture at 16 kHz, 16-bit mono. Streams samples to `WavWriter` and `SilenceDetector`. |
| `WavWriter.kt` | Writes 44-byte WAV header + raw PCM. Outputs valid `.wav` files for Whisper. |
| `SilenceDetector.kt` | Energy-based VAD. Computes RMS per frame. `onSilenceDetected` fires after configurable ms of continuous silence (default 1500 ms). |
| `DualStopDetector.kt` | Runs `SilenceDetector` + `WakeWordEngine` (stop state) in parallel. First to fire calls `onStopDetected(reason)`. |

### Wake Word Layer (`wakeword/`)

| File | Purpose |
|---|---|
| `WakeWordEngine.kt` | Picovoice Porcupine wrapper. Three operating states: `LISTENING_FOR_REPLY` (keyword: "Porcupine"/custom), `LISTENING_FOR_STOP` (keyword: "Terminator"), `LISTENING_FOR_CANCEL` (keyword: cancel phrase). Captures audio on a background thread. Callbacks: `onWakeWordDetected`, `onStopDetected`, `onCancelDetected`. |

### STT Layer (`stt/`)

| File | Purpose |
|---|---|
| `SherpaWhisperEngine.kt` | Lazily initialises `sherpa-onnx` `OfflineRecognizer` with Whisper Base int8 multilingual. Reads model files from `assets/sherpa-onnx-whisper-base/`. Converts WAV → float samples → transcription string. Language biased to `"hi"`. |
| `WhisperClient.kt` | Thin adapter: wraps `SherpaWhisperEngine`, exposes `transcribe(wavFile): String`. |
| `SttManager.kt` | Entry point for the service: `transcribe(wavFile): Result<String>`. Error handling, `failureReason(e)` for user-facing messages. |

### Reply Layer (`reply/`)

| File | Purpose |
|---|---|
| `ReplyManager.kt` | Fires `RemoteInput` to WhatsApp via `PendingIntent`. Looks up stored action from `RemoteInputStore`. Returns `ReplyResult`: `Sent`, `Expired`, `NoAction`, `Failed(error)`. |

### Service Layer (`service/`, `receiver/`)

| File | Purpose |
|---|---|
| `AariaForegroundService.kt` | Core always-running foreground service. Owns the full pipeline: `pipelineBusy` gate, queue loop, `startQueuePipeline`, `readConversation`, `openReplyWindow`, `runReplyPipeline`, `runAutoSendWindow`, `continueQueueOrFinish`. |
| `BootReceiver.kt` | `BroadcastReceiver` for `BOOT_COMPLETED`. Restarts `AariaForegroundService` after device reboot. |

### Mode Layer (`mode/`)

| File | Purpose |
|---|---|
| `ModeManager.kt` | Holds current `AariaMode` (`NORMAL`, `DRIVING`, `FOCUS`, `SILENT`). `shouldReadMessage(sender)` — returns false in Silent mode; in Focus mode, only returns true for the focused contact. `shouldActivateReplyWindow()` — returns false in Silent mode. |

### Data Layer (`data/`)

| File | Purpose |
|---|---|
| `SettingsStore.kt` | `SharedPreferences` wrapper. Persists: `defaultMode`, `silenceThresholdMs` (1500 ms default), `autoSendDelayMs` (3000 ms default), `readBackEnabled`, `markAsReadAfterTts`, `whisperApiKey`. |

---

## 6. Implemented Features (Phase-by-Phase)

### Phase 1 — Message Capture ✅

- `NotificationListenerService` filtering by `com.whatsapp` and `com.whatsapp.w4b`
- Extracts sender name, message text, group name, `isGroup` flag
- `RemoteInput` action stored immediately on notification arrival
- `MessageObject` deduplication by notification ID
- Live log display in `MainActivity`
- Notification dismissal (`onCancelNotification`) and mark-as-read (`onTriggerMarkAsRead`) callbacks

### Phase 2 — Voice Output ✅

- Android built-in TTS with `hi-IN` and `en-IN` language support
- Sequential TTS queue (no overlapping utterances)
- Group message prefix: "Message from X in Y group: …"
- `AudioFocusRequest` with `GAIN_TRANSIENT_MAY_DUCK` — ducks music during TTS
- TTS suppressed during active phone calls
- SSML probe: `ssmlSupported` Boolean? exposed in UI
- Mark-as-read toggle (marks notification as read after TTS plays)

### Phase 3 — Wake Word + Stop Word + Recording ✅

- Picovoice Porcupine wake word engine
- Three-state operation: reply detection, stop word detection, cancel detection
- `AudioRecord`-based PCM capture at 16 kHz, 16-bit mono
- `WavWriter` outputs valid WAV with 44-byte header
- Energy-based `SilenceDetector` (configurable threshold, default 1500 ms)
- `DualStopDetector`: silence OR stop word — first to fire wins
- 10-second reply window timeout: if no wake word, pipeline continues automatically

### Phase 4 — Full Reply Loop ✅

- End-to-end: wake word → record → dual stop → Whisper → clean → read-back → 3s auto-send → RemoteInput send
- `SherpaWhisperEngine`: fully on-device, Whisper Base int8 multilingual
- `OutgoingTextCleaner`: stop word strip, Devanagari→Roman, filler removal
- "Sending: [text]" confirmation before send
- 3-second cancel window with "cancel" wake word detection
- `RemoteInput` expiry detection: announces "Reply window expired. Open WhatsApp manually."
- WAV file cleanup after each reply

### Phase 5 — Language Intelligence ✅

- `EmojiProcessor`: emoji + WhatsApp media placeholder descriptions
- `AbbreviationExpander`: ~170-entry Hinglish dictionary
- `LanguageDetector`: ML Kit language identification, `LanguageProfile` output
- `WordTagger`: per-word Hindi/English labels using vocabulary sets and suffix heuristics
- `Transliterator`: rule-based Roman Hindi → Devanagari
- `SsmlBuilder`: per-segment `<lang>` SSML with fallback to plain text
- Live language analysis log in `MainActivity` (primary language, Hindi %, English %, script, SSML preview)

### Phase 6 — Multi-Message Queue + Contact Selection ✅

- `MessageQueue` extended: `pendingSenderKeys()`, `findBySenderKey()`, `removeAllBySenderKey()`
- `QueueAnnouncer`: builds numbered announcement ("Say 1 for X, 2 for Y, or say later")
- `ContactSelector`: short `SpeechRecognizer` burst resolving:
  - Digit strings: `"1"`, `"2"`
  - Word numbers: `"one"`, `"two"`, `"ek"`, `"do"`, etc.
  - Punctuation-stripped variants: `"1."`, `"one,"`
  - Phrase-embedded numbers: `"option 1"`, `"say one"` (per-word parsing)
  - Homophone: `"won"` → 1
  - Fuzzy name match: any word in phrase matches any word in sender name
  - Later words: `"later"`, `"skip"`, `"not now"`, `"ignore"`, `"baad mein"`, `"baad"`
  - One automatic retry on `NoMatch` before giving up
- `AariaForegroundService` pipeline loop: read → reply → announce → select → read → repeat
- `pipelineBusy` `AtomicBoolean` gate prevents re-entrant pipeline starts
- Pipeline auto-continues to next sender after reply without user re-triggering

---

## 7. Tech Stack

| Component | Implementation | Cost |
|---|---|---|
| Language | Kotlin | Free |
| Min Android | API 26 (Android 8.0) | — |
| Message capture | `NotificationListenerService` | Free |
| Reply sending | `RemoteInput` API | Free |
| Language detection | ML Kit `language-id` v17.0.6 | Free |
| Abbreviation expansion | Local Hinglish dictionary | Free |
| Transliteration | Rule-based Kotlin | Free |
| TTS | Android built-in TTS (`hi-IN` / `en-IN`) | Free |
| Wake word + stop word | Picovoice Porcupine 3.0.2 | Free tier |
| Silence detection | Energy-based VAD (RMS) | Free |
| STT | sherpa-onnx + Whisper Base int8 multilingual (~160 MB) | Free — fully offline |
| Contact selection | Android `SpeechRecognizer` (short burst) | Free |
| Background service | Android `LifecycleService` | Free |
| Coroutines | `kotlinx-coroutines-android` 1.7.3 | Free |
| UI | Material 3 (`material:1.11.0`) | Free |
| **Total monthly cost** | | **$0** |

---

## 8. Key Data Flows

### Message Object lifecycle

```
WhatsApp notification
  → MessageExtractor.extract() → MessageObject
  → MessageQueue.enqueue()     (dedup by id, grouped by senderKey)
  → Listener callbacks         (AariaForegroundService, MainActivity)
  → readConversation()         → MessageQueue.removeAllBySenderKey()
  → readSingleMessage()        → TTS plays
  → notification dismissed     (onCancelNotification)
```

### RemoteInput lifecycle

```
Notification arrives
  → RemoteInputStore.store(senderKey, action, pendingIntent)

TTS reads last message from sender
  → openReplyWindow()          (wake word listening begins)

Wake word detected → recording → transcription → cleanup
  → action = RemoteInputStore.retrieve(senderKey)
  → if action == null || action.expired → "Reply window expired"
  → else ReplyManager.sendReply(senderKey, text, store)
         → fires PendingIntent with RemoteInput extras
         → WhatsApp receives as legitimate reply
```

### Pipeline state machine

```
Idle (pipelineBusy = false)
  ↓ new message arrives
Busy (pipelineBusy = true)
  ↓ 1 sender  → readConversation
  ↓ 2+ senders → announceAndSelect → readConversation
    ↓ after reading
    ↓ reply window (10s timeout)
    ↓ continueQueueOrFinish
      ↓ 0 remaining → Idle
      ↓ 1 remaining → readConversation
      ↓ 2+ remaining → announceAndSelect → ...
```

---

## 9. Permissions

| Permission | Purpose |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read WhatsApp notifications |
| `RECORD_AUDIO` | Wake word engine, stop word detection, voice recording |
| `FOREGROUND_SERVICE` | Keep service alive in background |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required for `foregroundServiceType="specialUse"` on API 34 |
| `INTERNET` | Reserved (not currently used — all processing is on-device) |
| `VIBRATE` | Haptic feedback |
| `POST_NOTIFICATIONS` | Foreground service notification (API 33+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart after device reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery whitelist onboarding flow |
| `SYSTEM_ALERT_WINDOW` | Reserved for future overlay UI |

---

## 10. Settings & Configuration

### User-facing settings (via `SettingsStore`)

| Setting | Default | Description |
|---|---|---|
| `markAsReadAfterTts` | `false` | Mark WhatsApp notification as read after TTS plays |
| `silenceThresholdMs` | 1500 ms | How long of silence before recording stops |
| `autoSendDelayMs` | 3000 ms | How long the cancel window stays open before auto-sending |
| `readBackEnabled` | `true` | Whether to read back the transcription before sending |
| `defaultMode` | `NORMAL` | Starting mode on launch |

### Build-time configuration

| Key | Where | Description |
|---|---|---|
| `PICOVOICE_ACCESS_KEY` | `gradle.properties` or `.env` | Required for Porcupine wake word |

### Assets required

| Asset | Path | Size |
|---|---|---|
| sherpa-onnx AAR | `app/libs/sherpa-onnx.aar` | ~varies |
| Whisper Base encoder | `app/src/main/assets/sherpa-onnx-whisper-base/base-encoder.int8.onnx` | ~29 MB |
| Whisper Base decoder | `app/src/main/assets/sherpa-onnx-whisper-base/base-decoder.int8.onnx` | ~131 MB |
| Whisper tokens | `app/src/main/assets/sherpa-onnx-whisper-base/base-tokens.txt` | ~817 KB |

---

## 11. Known Limitations

| Limitation | Type |
|---|---|
| Long messages truncated to notification preview length | Hard limit — `NotificationListenerService` only receives the notification text |
| Media messages show placeholders only ("📷 Photo", "🎤 Voice message") | Hard limit — media content is inaccessible via notification API |
| RemoteInput expires when notification is dismissed or conversation opened in WhatsApp | Architectural constraint — action must be captured immediately on arrival |
| Android only — iOS not feasible | iOS sandboxing prevents `NotificationListenerService` and background audio |
| WhatsApp notification structure may change between app updates | External dependency — historically stable |
| Whisper Base int8 accuracy lower than API-based Whisper for rare Hinglish | Model limitation — acceptable for casual messages |

---

## 12. Known Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| OEM battery optimisation kills background service (Xiaomi, Realme, Samsung) | High | `BootReceiver` auto-restart + onboarding flow for battery whitelist |
| SSML `<lang>` tag unsupported on device TTS | High | `ssmlSupported` probe on init; `TtsManager.speakSsml()` falls back to plain text |
| No Kotlin/Java `indic-transliteration` library equivalent | Medium | Rule-based transliteration built in `Transliterator.kt` |
| Contact name selection needs open-vocabulary STT, not Porcupine | Medium | Solved with `ContactSelector` using Android `SpeechRecognizer` + numbered options |
| Notification listener permission may concern users | Low (personal use) | Clear onboarding explanation; strict package whitelist |
| Wake word false positives | Low | Porcupine sensitivity tuning; listener not always-on (activates only after message read) |
| Audio focus conflicts with other apps | Medium | Proper `AudioFocusRequest` lifecycle in `AudioFocusManager` |
| OTP or sensitive notifications exposed | Low | Strict whitelist — only `com.whatsapp` and `com.whatsapp.w4b` are processed |

---

## 13. Remaining Scope (Phases 7–8)

### Phase 7 — Modes (Not started)

The infrastructure exists (`ModeManager`, `AariaMode` enum, `shouldReadMessage`, `shouldActivateReplyWindow`), but the following are not yet implemented:

- **Voice command mode switching** — "Hey Aaria, driving mode" / "exit focus" via wake word
- **Quick settings tile** — instant mode toggle from notification shade
- **Focus Mode** — voice activation of focus on a specific contact ("Hey Aaria, focus on Rahul"); only that contact's messages read aloud
- **Mode persistence** across service restarts via `SettingsStore.defaultMode`
- **Normal mode behaviour** — passive mode with no auto-reading but hands-free reply available

### Phase 8 — Polish & Edge Cases (Not started)

- **Bluetooth earbud integration** — detect connected earbuds, prefer audio routing; hardware button fallback for reply trigger
- **Manufacturer-specific battery whitelist onboarding** — per-OEM instructions for Xiaomi, Realme, Samsung, OnePlus to prevent service kill
- **Full settings screen** — voice engine selection, sensitivity sliders, threshold adjustments, mode preferences
- **DND (Do Not Disturb) integration** — respect system DND for message reading
- **24-hour stability testing** — background service survival on target device
- **Notification action refresh** — detect and handle RemoteInput expiry more gracefully
- **Audio routing** — explicit Bluetooth > wired > speaker priority chain

---

## 14. Future Ideas (Post-v1)

| Idea | Notes |
|---|---|
| **Voice cloning** | Read messages in the sender's voice — ElevenLabs / Coqui / OpenVoice |
| **Whisper Small upgrade** | Swap Base int8 for Small int8 (~340 MB) for better Hinglish accuracy |
| **Smart replies** | AI-suggested replies based on conversation context |
| **Tone adjustment** | Rephrase casual speech for clarity without changing meaning |
| **Personal abbreviation learning** | Auto-expand user-specific shorthand over time |
| **AI Bharat self-hosted TTS** | Highest quality Hindi voices on Oracle Cloud Free Tier |
| **Multi-app support** | Extend beyond WhatsApp to Telegram, Signal, SMS |
| **Conversation summarisation** | "Summarise the last 10 messages from Rahul" |

---

## 15. Project Setup & Build

### Prerequisites

- Android Studio (Hedgehog or newer)
- JDK 17
- Android device or emulator running API 26+

### Step 1: sherpa-onnx AAR

Download from:
```
https://huggingface.co/csukuangfj/sherpa-onnx-libs/tree/main/android/aar
```
Rename the downloaded file to `sherpa-onnx.aar` and place it at:
```
app/libs/sherpa-onnx.aar
```

### Step 2: Whisper Base model files

Download from:
```
https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base
```
Place all three files at:
```
app/src/main/assets/sherpa-onnx-whisper-base/
  base-encoder.int8.onnx   (~29 MB)
  base-decoder.int8.onnx   (~131 MB)
  base-tokens.txt          (~817 KB)
```

### Step 3: Picovoice Access Key

Get a free key at [console.picovoice.ai](https://console.picovoice.ai/) and add it to `gradle.properties`:
```
PICOVOICE_ACCESS_KEY=your_key_here
```
Or add to `.env` at the project root (auto-loaded by the root `build.gradle.kts`):
```
PICOVOICE_ACCESS_KEY="your_key_here"
```

### Step 4: Build

Open in Android Studio → **Build → Make Project**, or:
```
./gradlew assembleDebug
```

### Step 5: Install & Configure

1. Install the APK on your Android device
2. Open Aaria → tap **Enable Notification Access** → grant permission for Aaria
3. Tap **Battery Optimisation** → allow Aaria to run in background
4. Open WhatsApp and send yourself a test message — Aaria should read it aloud

---

*Aaria is a personal project. All processing is on-device. No message data leaves the device. Only `com.whatsapp` and `com.whatsapp.w4b` notifications are processed — all others are discarded immediately.*
