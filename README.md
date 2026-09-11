<div align="center">

# 🛡️ Aegis Drive

### Unified Driver Safety & Navigation — powered by on-device Edge AI

[![Android CI](https://github.com/MalikAnees530/Aegis-Drive/actions/workflows/android.yml/badge.svg)](https://github.com/MalikAnees530/Aegis-Drive/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com/about)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Security Policy](https://img.shields.io/badge/Security-Policy-brightgreen.svg)](SECURITY.md)
[![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen.svg)](CONTRIBUTING.md)

*Real-time drowsiness detection, offline navigation, and a context-aware AI assistant — all running on the driver's phone, with sub-100ms latency and zero cloud dependency for safety-critical inference.*

</div>

---

## 📋 Overview

**Aegis Drive** is a smartphone-based Edge AI application built to reduce road accidents caused by driver fatigue and distraction. It fuses **real-time driver monitoring**, **offline-capable navigation**, and a **safety-aware AI assistant** into a single, privacy-first Android app.

All safety-critical vision inference runs **locally on the device** — no video ever leaves the phone — so alerts fire instantly, even without connectivity.

---

## ✨ Features

| Screen | What it does |
| :--- | :--- |
| 🏠 **Command Center** | At-a-glance dashboard: last drive score, alerts fired, safety rating, focus level, and session history — synced live from Firestore. |
| 🗺️ **Navigate** | Map-based routing (MapLibre + OSRM) with voice destination search and live heading. |
| 👁️ **Monitor** | The live safety HUD — front-camera drowsiness/distraction detection with an instrument-style "Control Deck": safety score, eye state, alert count, and a hard alarm on danger. |
| 💬 **AI Chat** | A safety-aware conversational assistant (hands-free voice in/out) that sees your live safety telemetry. |

Plus emergency **family notifications** when a single drive crosses the alert threshold, and a fully polished **Settings / Profile** experience.

---

## 🎨 Design System

Aegis Drive follows a **dark-first "Premium Minimal"** design language — spacious layouts, hairline-bordered flat surfaces, and a restrained accent palette.

- **Typography** — **Space Grotesk** (headings) + **Inter** (body), bundled offline.
- **Theming** — token-driven colors (`values/` + `values-night/`); light mode fully polished, dark mode default. The camera HUD uses a theme-independent dark-glass palette so it stays legible over any footage.
- **Motion** — purposeful, not decorative: staggered entrances, `MaterialSharedAxis` tab transitions, count-up scores, progress sweeps, and tactile press feedback across every screen.

---

## 🧠 AI & Vision Architecture

### 👁️ On-Device Driver Monitoring
- **CameraX** feeds frames directly into **Google MediaPipe** Face Landmarker for 3D facial geometry.
- A **TensorFlow Lite** model classifies **Normal / Drowsiness / Yawning** from Eye-Aspect-Ratio (EAR) and Mouth-Aspect-Ratio (MAR) sequences, with head-pose-aware thresholds to suppress false alarms.
- A rolling **Safety Score** drives escalating vibration + alarm feedback — all computed on-device for zero-latency response.

### 💬 Aegis AI Assistant
- Powered by **Llama 3.3 70B** via the **Groq API**, wired through a **Retrofit + Repository** networking stack.
- **Context-aware**: the assistant receives your live Safety Score to give personalized guidance.
- **Multimodal**: hands-free **Speech-to-Text** and **Text-to-Speech** for eyes-on-the-road interaction.

---

## 🔒 Security & Privacy

- **On-device inference** — camera frames are processed locally and never uploaded.
- **No secrets in source** — API keys are injected at build time via `BuildConfig`; `local.properties` and `google-services.json` are excluded from version control.
- **Clean history** — the repository underwent a full Git history scrub to remove any legacy configuration traces.

---

## 🚀 Getting Started

**Prerequisites:** Android Studio (latest), JDK 11+, an Android device/emulator on **API 26+**.

```bash
git clone https://github.com/MalikAnees530/Aegis-Drive.git
```

1. **Add `local.properties`** in the project root.
2. **Configure your Groq API key:**
   ```properties
   GROK_API_KEY=gsk_your_key_here
   ```
3. **Add `google-services.json`** (Firebase) to `app/`.
4. **Sync Gradle** in Android Studio to generate `BuildConfig`, then run:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 🧰 Tech Stack

| Layer | Technology |
| :--- | :--- |
| **Language** | Kotlin · JDK 11 |
| **UI** | Material 3 (XML Views) · Navigation Component · dark-first design tokens |
| **Architecture** | MVVM + Repository pattern |
| **On-device AI** | TensorFlow Lite · MediaPipe Vision Tasks |
| **Conversational AI** | Groq API (Llama 3.3 70B) · Retrofit + Gson |
| **Navigation** | MapLibre SDK · OSRM |
| **Backend** | Firebase Authentication · Cloud Firestore |
| **Camera** | CameraX |

---

## 👥 Team

| Role | Member |
| :--- | :--- |
| **Team Leader** | Malik Anees Ahmed |
| **FYP Group Member** | Mudassir Mukhtar |
| **FYP Group Member** | M. Niaz |

> Aegis Drive was designed and built end-to-end by **Malik Anees Ahmed**, who is the sole author and owner of the codebase. Mudassir Mukhtar and M. Niaz are final-year-project group members.

**Supervisor:** Dr. Farnaz Akbar
**Institution:** National University of Modern Languages (NUML), Islamabad

---

<div align="center">

*Built to keep drivers safe — on the edge, in real time.* 🛡️

</div>
