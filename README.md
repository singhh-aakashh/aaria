# Aaria

Aaria is an Android-based hands-free AI assistant that creates a complete voice layer on top of WhatsApp. Incoming text messages are converted to voice and read aloud through your earbuds. You reply by speaking naturally — your voice is transcribed to text and sent back — all without ever touching your phone.

Hinglish is a first-class language, not an afterthought. The long-term vision is a full hands-free AI assistant. WhatsApp voice messaging is the first feature.

## Platform & Distribution

Android only. iOS is not feasible due to platform sandboxing restrictions. Initially distributed as a sideloaded APK — not Play Store — to avoid policy restrictions during development and testing.

---

## Architecture Overview

### Incoming Flow

```
WhatsApp notification arrives
        ↓
NotificationListenerService — captures text, sender, stores RemoteInput action
        ↓
Text Intelligence Layer — abbreviation expansion, language detection,
  word-level tagging, transliteration, SSML construction
        ↓
TTS Engine — Android built-in / Azure / AI Bharat
        ↓
Audio Output Manager — device routing, volume, music ducking, queue
        ↓
You hear the message
```

### Outgoing Flow

```
Wake word "Hey Aaria" detected
        ↓
Audio Capture — MediaRecorder starts
        ↓
Dual Stop Detection — 1.5s silence OR stop word "Done" (first wins)
        ↓
Audio Cleaning — noise suppression, silence strip, stop word clip
        ↓
Whisper STT — transcription returned
        ↓
Text Cleanup — filler removal, Devanagari→Roman, stop word safety strip
        ↓
Confirmation — TTS reads "Sending: [message]", 3s auto-send window
        ↓
RemoteInput Send — fires to WhatsApp
```

```
┌─────────────────────────────────────────────────────────────┐
│                        AARIA SYSTEM                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   INCOMING PIPELINE              OUTGOING PIPELINE          │
│                                                             │
│   WhatsApp Notification          Wake Word Detection        │
│          ↓                              ↓                   │
│   Notification Service           Audio Capture              │
│          ↓                              ↓                   │
│   Message Store                  Dual Stop Detection        │
│          ↓                              ↓                   │
│   Text Intelligence              Audio Cleaning             │
│          ↓                              ↓                   │
│   TTS Engine                     Whisper STT                │
│          ↓                              ↓                   │
│   Audio Output                   Text Cleanup               │
│          ↓                              ↓                   │
│   User Hears Message             Confirmation + Send        │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│              CORE SERVICES (Always Running)                  │
│   Mode Manager │ Queue Manager │ Audio Focus Manager        │
│   Wake Word Engine │ Language Engine │ Settings Store        │
└─────────────────────────────────────────────────────────────┘
```

---

## Layer Breakdown

### Layer 1 — Notification Capture (NotificationListenerService)

Official Android API. Filters strictly by WhatsApp package names (`com.whatsapp`, `com.whatsapp.w4b`) — all other notifications are discarded immediately and never stored.

From each WhatsApp notification, extracts: sender name, message preview text, group name (if applicable), and the RemoteInput action object.

**Critical:** The RemoteInput action must be stored immediately on notification arrival — it expires when the notification is dismissed or the conversation is opened in WhatsApp. If the action expires, Aaria detects this and notifies: "Reply window expired, open WhatsApp manually."

**Hard limitations:**
- Long messages are truncated to notification preview length
- Media messages show placeholders only ("📷 Photo", "🎤 Voice message") — actual content is inaccessible

### Layer 2 — Reply Sending (RemoteInput API)

The same mechanism Android uses when you reply from the notification shade. Identical to how smartwatches and Android Auto reply to WhatsApp. Completely official, no ToS violation, no ban risk, no Accessibility Service needed.

Aaria programmatically fires the stored RemoteInput action with the transcribed text, and WhatsApp receives it as a legitimate reply.

### Layer 3 — Text Intelligence (Incoming)

Runs entirely on-device. Zero cost. Processes text before TTS:

1. **Emoji processing** — media placeholders converted to spoken descriptions ("shared a photo")
2. **Abbreviation expansion** — Hinglish shorthand dictionary ("yr"→"yaar", "h"→"hai", "tbh"→"to be honest")
3. **Language detection** — fastText classifier via TFLite (<2MB, <5ms), outputs language profile (Hindi/English ratio, script type)
4. **Word-level tagging** — each word tagged as Hindi or English using classifier + vocabulary lookup
5. **Transliteration** — Roman Hindi words converted to Devanagari ("bhai"→"भाई"), English words untouched
6. **SSML construction** — tagged text wrapped in `<lang>` tags for per-segment voice switching

⚠️ **Risk:** Android built-in TTS has inconsistent SSML `<lang>` tag support across devices. This must be validated on the target device early. If unsupported, Azure TTS or a simpler single-language approach is the fallback.

⚠️ **Risk:** The Python `indic-transliteration` library has no Kotlin/Java equivalent. Options: port transliteration rules manually, use ICU4J (limited Hindi support), or build a rule-based mapping.

### Layer 4 — TTS Engine

| Tier | Engine | Cost | Notes |
|---|---|---|---|
| Primary | Android built-in TTS (hi-IN + en-IN) | Free | Works offline, SSML support varies by device |
| Upgrade | Azure Free Tier (SwaraNeural / NeerjaNeural) | Free <500k chars/month | Best free neural Hindi voice |
| Advanced | AI Bharat self-hosted on Oracle Cloud Free | Free | Highest quality Hindi, requires self-hosting |

### Layer 5 — Audio Output Manager

- `AudioFocusRequest` integration — plays nicely with calls, music, other audio apps
- Suppresses TTS during active phone calls
- Ducks background music while reading, restores after
- Routes audio to Bluetooth earbuds > wired earphones > earpiece/speaker
- TTS queue — multiple messages read sequentially, never simultaneously
- Group message prefix: "Message from Rahul in Team group: [message]"

### Layer 6 — Wake Word Detection (Picovoice Porcupine)

Runs on-device, no network, minimal battery. Custom wake word "Hey Aaria" trained via Picovoice Console.

**Not always on** — activates only after TTS finishes reading a message. Deactivates after reply window closes. Effective battery cost is near zero.

States:
- `LISTENING_FOR_REPLY` — awaiting "Hey Aaria" after message read
- `LISTENING_FOR_STOP` — awaiting "Done" during recording
- `LISTENING_FOR_CANCEL` — awaiting "cancel" during read-back window

Also serves as the general voice command interface for mode switching.

### Layer 7 — Dual Stop Detection

Both run in parallel from the moment recording starts. First to fire wins.

- **Silence detection** (Silero VAD) — 1.5s continuous silence → stop. Feels invisible in quiet environments.
- **Stop word** (Porcupine) — "Done" detected → immediate stop regardless of noise. Reliable fallback for driving, restaurants, streets.

The word "Done" is stripped from the transcription before sending.

### Layer 8 — Speech to Text

All transcription is fully **on-device** using [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with **Whisper Base (int8 quantized)**. No network call is made; no API key is required.

| Engine | Model | Size on disk | Notes |
|---|---|---|---|
| sherpa-onnx | Whisper Base multilingual int8 | ~160 MB | On-device, fully offline |

The multilingual Whisper Base model is configured with `language="hi"` to bias toward Hinglish (Roman-script Hindi mixed with English).

**Setup:** See [Getting Started](#getting-started) for model download and AAR instructions.

### Layer 9 — Text Cleanup (Outgoing)

Post-processes Whisper output:
1. Stop word safety strip ("Done"/"Send" at end of text)
2. Devanagari→Roman conversion (Whisper sometimes outputs Devanagari for Hindi words)
3. Filler word removal ("um", "uh", "you know")
4. Light punctuation and capitalization correction
5. Minimal normalization — preserves personal voice

### Layer 10 — Confirmation and Send

1. TTS reads: "Sending: [final message]"
2. 3-second auto-send window opens
3. "Cancel" during window → abort
4. No cancellation → RemoteInput fires → message delivered
5. Confirmation chime on success
6. Queue manager announces remaining messages if any

---

## Hands-Free Experience

**Scenario: Driving, 3 messages arrive simultaneously**

1. Soft chime in earbuds
2. Aaria: "3 messages — Rahul, Mom, Team group. Say a name to reply or say later."
3. You say: "Rahul"
4. Aaria reads Rahul's message
5. Chime — reply window open
6. You say: "Hey Aaria... haan yaar 20 minutes mein aata hoon... Done"
7. "Done" fires → audio sent to Whisper → transcription cleaned
8. Aaria: "Sending: haan yaar 20 minutes mein aata hoon"
9. 3 seconds → auto-sends via RemoteInput
10. Aaria: "Sent. 2 remaining — Mom, Team group."

Zero screen touches. Zero button presses.

⚠️ **Contact name selection** (step 3) requires open-vocabulary speech recognition, not keyword detection. Porcupine cannot do this. Options: (a) short Whisper/SpeechRecognizer burst for name detection, (b) numbered options ("say 1 for Rahul"), (c) read messages in arrival order.

---

## Modes

| Mode | Behavior |
|---|---|
| **Driving** | Full hands-free. All messages announced and queued. Stop word essential for noisy environments. Read-back before send. |
| **Focus** | Single contact filter. Only selected contact's messages read aloud. All others silently queued. Voice activated: "Hey Aaria, focus on Rahul" / "exit focus". |
| **Silent** | Nothing read aloud. All messages queue silently. Respects system DND. |
| **Normal** | Passive. No auto-reading. Hands-free reply available but not auto-triggered. Default state. |

Mode switching via wake word ("Hey Aaria, driving mode") or quick settings tile.

---

## Tech Stack

| Component | Tool | Cost |
|---|---|---|
| Language | Kotlin | Free |
| Message capture | NotificationListenerService | Free |
| Reply sending | RemoteInput API | Free |
| Language detection | ML Kit language-id | Free |
| Abbreviation expansion | Local Hinglish dictionary | Free |
| Transliteration | Rule-based / ICU4J | Free |
| TTS (primary) | Android built-in TTS | Free |
| TTS (upgrade) | Azure Free Tier Neural | Free |
| Wake word + stop word | Picovoice Porcupine | Free tier |
| Silence detection | Energy-based VAD | Free |
| STT | sherpa-onnx + Whisper Base int8 | Free — fully offline |
| Background service | Android Foreground Service | Free |

**Total monthly cost: $0**

---

## Getting Started

The following models are **not** included in the repo (they are large binaries). You must download them and place them in the exact paths below before building.

---

### 1. sherpa-onnx AAR (required for STT)

**Model:** sherpa-onnx Android library  
**Version:** 1.12.21 or newer (tested with 1.12.21)

| Where to get it | Direct download (1.12.21) |
|---|---|
| [Browse all versions](https://huggingface.co/csukuangfj/sherpa-onnx-libs/tree/main/android/aar) | [sherpa-onnx-1.12.21.aar](https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-1.12.21.aar) |

**Steps:**
1. Download the AAR (any 1.12.x version works; e.g. `sherpa-onnx-1.12.21.aar`).
2. **Rename** it to exactly `sherpa-onnx.aar`.
3. Place it at this **exact path** (create `app/libs/` if needed):

   ```
   <project-root>/app/libs/sherpa-onnx.aar
   ```

   Example full path: `C:\Users\You\Projects\aaria\app\libs\sherpa-onnx.aar`

---

### 2. Whisper Base model files (required for STT)

**Model:** Whisper Base multilingual (int8 quantized)  
**Source:** [csukuangfj/sherpa-onnx-whisper-base](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base)

| File | Size | Direct download |
|---|---|---|
| `base-encoder.int8.onnx` | ~29 MB | [Download](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx) |
| `base-decoder.int8.onnx` | ~131 MB | [Download](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx) |
| `base-tokens.txt` | ~817 KB | [Download](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt) |

**Steps:**
1. Create the directory: `<project-root>/app/src/main/assets/sherpa-onnx-whisper-base/`
2. Place all three files **directly inside** that folder. Do not nest them in a subfolder.
3. **Exact paths** (replace `<project-root>` with your repo path):

   ```
   <project-root>/app/src/main/assets/sherpa-onnx-whisper-base/base-encoder.int8.onnx
   <project-root>/app/src/main/assets/sherpa-onnx-whisper-base/base-decoder.int8.onnx
   <project-root>/app/src/main/assets/sherpa-onnx-whisper-base/base-tokens.txt
   ```

   Example full paths:
   ```
   C:\Users\You\Projects\aaria\app\src\main\assets\sherpa-onnx-whisper-base\base-encoder.int8.onnx
   C:\Users\You\Projects\aaria\app\src\main\assets\sherpa-onnx-whisper-base\base-decoder.int8.onnx
   C:\Users\You\Projects\aaria\app\src\main\assets\sherpa-onnx-whisper-base\base-tokens.txt
   ```

---

### 3. Picovoice Access Key (required for wake word)

Get a free key at [console.picovoice.ai](https://console.picovoice.ai/).

Add it to **either**:
- `gradle.properties`: `PICOVOICE_ACCESS_KEY=your_key_here`
- Or `.env` in the project root (copy from `sample.env` and fill in the key)

---

### 4. Build

Open the project in Android Studio and run **Build → Make Project**, or from terminal:

```bash
./gradlew assembleDebug
```

Without the AAR and model files above, the build will succeed but the app will crash on first transcription with a clear error about missing assets.

---

## Permissions

| Permission | Purpose |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read WhatsApp notifications |
| `RECORD_AUDIO` | Wake word, stop word, voice recording |
| `FOREGROUND_SERVICE` | Keep listener alive in background |
| `VIBRATE` | Haptic feedback |

---

## Development Phases

### Phase 1 — Message Capture

Build NotificationListenerService. Filter by WhatsApp package names. Extract sender, text, group info. Store RemoteInput action on arrival. Log all data to screen.

**Done when:** 50 consecutive WhatsApp messages correctly captured with sender, text, and usable RemoteInput action — including group messages and edge cases like multi-line text.

### Phase 2 — Voice Output

Integrate Android built-in TTS. Read incoming messages aloud. Implement TTS queue for sequential playback. Add group message prefixing. Implement AudioFocusRequest lifecycle.

**Done when:** Messages read aloud through speaker and Bluetooth earbuds, multiple simultaneous messages queue properly, music ducks during playback, TTS suppressed during phone calls.

**Validate early:** Test SSML `<lang>` tag support on target device. This determines whether the Hinglish language intelligence approach is viable with Android TTS.

### Phase 3 — Wake Word + Stop Word + Recording

Integrate Porcupine for "Hey Aaria" and "Done". Build recording pipeline with MediaRecorder. Implement dual stop detection (Silero VAD + Porcupine running in parallel). Test in quiet and noisy environments.

**Setup:** Get a free [Picovoice Access Key](https://console.picovoice.ai/) and set `PICOVOICE_ACCESS_KEY=your_key` in `gradle.properties` (or pass `-PPICOVOICE_ACCESS_KEY=your_key` when building). Without it, wake word is disabled and the app runs normally.

**Done when:** Wake word reliably triggers recording, silence detection stops recording in quiet rooms, stop word stops recording in noisy environments, both run without audio conflicts.

### Phase 4 — Full Reply Loop

Integrate Whisper STT. Connect end-to-end: wake word → record → dual stop → Whisper → text cleanup → read-back → 3s auto-send window → RemoteInput send. Add "cancel" detection during read-back window.

**Done when:** Complete voice reply delivered to WhatsApp recipient. Tested with Hinglish messages. Cancel flow works. RemoteInput expiry detected and communicated.

### Phase 5 — Language Intelligence

fastText classifier training and on-device integration. Abbreviation dictionary. Word-level language tagging. Transliteration (Roman Hindi→Devanagari). SSML construction. Per-segment voice switching in TTS.

**Done when:** Hinglish messages read with natural voice switching between Hindi and English segments. Abbreviations expanded correctly. Tested on 100+ real WhatsApp-style Hinglish messages.

### Phase 6 — Multi-Message Queue + Contact Selection

Queue management for simultaneous messages. TTS announcement of pending senders. Contact selection by voice (numbered options + fuzzy name match via Android SpeechRecognizer). Post-reply queue continuation.

**Implementation:**
- `MessageQueue` — extended with `pendingSenderKeys()`, `allBySenderKey()`, `removeAllBySenderKey()` for grouped conversation retrieval.
- `QueueAnnouncer` — builds and speaks the "N messages remaining — Rahul, Mom. Say 1 for Rahul, 2 for Mom, or say later." announcement.
- `ContactSelector` — short Android `SpeechRecognizer` burst; resolves spoken numbers ("1", "2", "one", "ek") and fuzzy name matches against pending sender names. "Later"/"skip"/"baad mein" releases the pipeline.
- `AariaForegroundService` — overhauled with `pipelineBusy` gate and full queue loop: read → reply → announce remaining → select → read → repeat until empty or "later".

**Done when:** 5+ simultaneous messages handled correctly. User can select which conversation to reply to by voice. Queue persists across reply cycles.

### Phase 7 — Modes

Implement Driving, Focus, Silent, Normal modes. Voice command switching via wake word. Quick settings tile for manual switching. Focus Mode contact filtering and background queue.

**Done when:** All four modes functional. Voice switching works. Focus mode correctly filters to single contact.

### Phase 8 — Polish + Edge Cases

Bluetooth earbud integration and button fallback. Battery optimization whitelisting onboarding (manufacturer-specific). Settings screen (voice selection, sensitivity, thresholds, mode preferences). RemoteInput expiry UX. DND integration. Stability testing.

**Done when:** App survives 24 hours of background operation on target device. All settings functional. Onboarding flow complete.

---

## Known Limitations

| Limitation | Type |
|---|---|
| Long messages truncated to notification preview | Hard limit of NotificationListenerService |
| Media messages show placeholders only | Hard limit — no media content access |
| RemoteInput expires when notification dismissed | Architectural constraint — must store action immediately |
| Android only | iOS sandboxing prevents this architecture |
| WhatsApp notification structure may change | External dependency, historically stable |

---

## Known Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| OEM battery optimization kills background service (Xiaomi, Realme, Samsung) | High | Onboarding flow with manufacturer-specific battery whitelist instructions |
| SSML `<lang>` tag unsupported on target device TTS | High | Validate in Phase 2. Fallback: Azure TTS or single-language approach |
| No Kotlin/Java `indic-transliteration` library | Medium | Build rule-based transliteration or use ICU4J |
| Contact name selection needs open-vocab STT, not Porcupine | Medium | Use short Whisper/SpeechRecognizer burst or numbered options |
| Notification listener permission scares users | Low (personal use) | Clear onboarding explanation |
| Wake word false positives | Low | Porcupine sensitivity tuning, listener not active 24/7 |
| Whisper Base int8 accuracy lower than whisper-1 for rare Hinglish | Medium | Acceptable for casual WhatsApp; upgrade to Small if needed |
| OTP/sensitive notification exposure | Low | Strict package whitelist, nothing stored |
| Audio focus conflicts | Medium | Proper AudioFocusRequest lifecycle |

---

## Future Ideas (Not in Scope for v1)

- **Voice cloning** — read messages in a specific person's voice using ElevenLabs/Coqui/OpenVoice
- **Whisper Small upgrade** — swap base-encoder/decoder for small int8 (~340 MB) for better Hinglish accuracy
- **Smart replies** — AI-suggested responses based on conversation context
- **Tone adjustment** — rephrase casual speech for clarity
- **Learning personal abbreviations** — auto-expand user-specific shorthand over time
- **AI Bharat self-hosted TTS** — highest quality Hindi voices on Oracle Cloud Free Tier
