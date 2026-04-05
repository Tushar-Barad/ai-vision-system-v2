# 🦯 BT7 Smart Stick — AI Vision System v2

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"/>
  <img src="https://img.shields.io/badge/Hardware-ESP32-E7352C?style=for-the-badge&logo=espressif&logoColor=white"/>
  <img src="https://img.shields.io/badge/AI-GPT--4o%20Vision-412991?style=for-the-badge&logo=openai&logoColor=white"/>
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge"/>
</p>

<p align="center">
  <b>An AI-powered assistive smart stick for visually impaired users.</b><br/>
  Combines a Kotlin Android app + ESP32 hardware to deliver real-time vision, voice, navigation, and emergency features.
</p>

---

## 📖 Table of Contents

- [About](#-about)
- [Features](#-features)
- [System Architecture](#-system-architecture)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Hardware Setup](#-hardware-setup)
- [Getting Started](#-getting-started)
- [ESP32 Firmware](#-esp32-firmware)
- [Button → Feature Mapping](#-button--feature-mapping)
- [Languages Supported](#-languages-supported)
- [Important Notes](#️-important-notes)
- [Author](#-author)

---

## 🧠 About

**BT7 Smart Stick** is an AI-powered assistive device designed to help **visually impaired users** interact with the world around them. The system consists of two parts:

1. **Android App (BT7v2)** — A Kotlin + Jetpack Compose app that handles all AI processing, voice output, GPS navigation, and emergency alerts.
2. **ESP32 Firmware** — Runs on the physical smart stick and sends Bluetooth commands to the Android app when the user presses any of the 7 physical buttons.

Together, they form a hands-free, voice-driven assistant that describes surroundings, reads text, detects currency, and more — all triggered by a simple button press on the stick.

---

## ✨ Features

| # | Feature | Description |
|---|---------|-------------|
| 👁️ | **Vision AI** | Uses the phone camera + GPT-4o Vision API to describe the surroundings in natural language |
| 📝 | **Text Reader** | Points camera at any text and reads it aloud using AI-powered OCR |
| 💵 | **Currency Detector** | Identifies Indian Rupee banknotes and announces the total amount |
| 🚨 | **Emergency SOS** | Sends the user's live GPS location via SMS and auto-calls an emergency contact |
| 🤖 | **Voice Assistant** | Answers spoken questions using GPT-4o, fully hands-free |
| 🗺️ | **GPS Navigation** | Speaks the current address and location using Google Fused Location |
| 🔄 | **Object Scanner** | Real-time obstacle detection using a TensorFlow Lite model on-device |

---

## 🏗️ System Architecture

```
[ ESP32 Smart Stick ]
        │
        │  Bluetooth Classic (SPP)
        │  Commands: VISION, TEXT, CURRENCY,
        │            EMERGENCY, ASSISTANT, NAVIGATE, SCANNER
        ▼
[ Android App (BT7v2) ]
        │
        ├──► CameraX ──────────► GPT-4o Vision API  (Vision / Text / Currency)
        ├──► Microphone ────────► GPT-4o Chat API    (Voice Assistant)
        ├──► TensorFlow Lite ───► detect.tflite      (Object Scanner)
        ├──► Fused Location ────► Google Maps        (GPS Navigation)
        └──► SMS + Phone Call ──► Emergency Contact  (Emergency SOS)
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Kotlin |
| **UI Framework** | Jetpack Compose |
| **AI / Vision** | OpenAI GPT-4o Vision API |
| **On-Device ML** | TensorFlow Lite (`detect.tflite`) |
| **Camera** | CameraX |
| **Location** | Google Play Services — Fused Location Provider |
| **Hardware Comms** | Bluetooth Classic SPP (Serial Port Profile) |
| **Microcontroller** | ESP32 (Arduino framework) |
| **Min SDK** | Android 7.0 (API 24) |
| **Build System** | Gradle with Kotlin DSL |

---

## 📁 Project Structure

```
ai-vision-system-v2/
│
├── BT7v2/                          # Android App
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/example/bt7v2/
│   │   │   │   ├── MainActivity.kt       # Core app logic, UI (Jetpack Compose), BT handler
│   │   │   │   └── ObjectDetector.kt     # TFLite real-time object detection wrapper
│   │   │   ├── assets/
│   │   │   │   └── detect.tflite         # ← Place your TFLite model here (not included)
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   ├── gradle/
│   │   ├── libs.versions.toml
│   │   └── wrapper/
│   │       └── gradle-wrapper.properties
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle.properties
│
└── esp32/
    └── BT7_SmartStick.ino              # ESP32 Arduino firmware
```

---

## 🔌 Hardware Setup

### Components Required

| Component | Details |
|-----------|---------|
| **Microcontroller** | ESP32 (any dev board with Bluetooth Classic) |
| **Buttons** | 7× tactile push buttons (momentary, active LOW) |
| **LED** | 1× status LED (built-in or external on GPIO 2) |
| **Power** | LiPo battery or USB power bank |
| **Enclosure** | Mounted on a walking stick |

### GPIO Pin Mapping

| GPIO | Button | Feature |
|------|--------|---------|
| 4 | BTN1 | 👁️ Vision AI |
| 5 | BTN2 | 📝 Text Reader |
| 18 | BTN3 | 💵 Currency |
| 19 | BTN4 | 🚨 Emergency |
| 21 | BTN5 | 🤖 Assistant |
| 22 | BTN6 | 🗺️ Navigation |
| 23 | BTN7 | 🔄 Object Scanner |
| 2 | LED | Status indicator |

> All buttons use **INPUT_PULLUP** — connect one side to GPIO and the other to GND.

---

## 🚀 Getting Started

### Prerequisites

- Android Studio (latest stable)
- Android device running Android 7.0+ (API 24+)
- OpenAI API Key ([Get one here](https://platform.openai.com/api-keys))
- TFLite object detection model (e.g., COCO SSD MobileNet)
- Arduino IDE with ESP32 board support (for firmware)

### 1. Clone the Repository

```bash
git clone https://github.com/Tushar-Barad/ai-vision-system-v2.git
cd ai-vision-system-v2
```

### 2. Add Your TFLite Model

Place your TensorFlow Lite model file inside:

```
BT7v2/app/src/main/assets/detect.tflite
```

> Recommended: [COCO SSD MobileNet v1](https://www.tensorflow.org/lite/models/object_detection/overview) or any compatible detection model.

### 3. Add Your OpenAI API Key

Open `BT7v2/app/src/main/java/com/example/bt7v2/MainActivity.kt` and replace:

```kotlin
const val API_KEY = "YOUR_OPENAI_API_KEY_HERE"
```

> ⚠️ **Security Warning:** Never commit your API key to version control. For production, store it in `local.properties` or use a backend proxy server.

### 4. Build & Run

1. Open the `BT7v2/` folder in **Android Studio**
2. Let Gradle sync complete
3. Connect your Android device via USB (enable USB debugging)
4. Click **Run ▶** or press `Shift + F10`

---

## 📡 ESP32 Firmware

### Flash Instructions

1. Open **Arduino IDE**
2. Install ESP32 board support via Board Manager:
   - URL: `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
3. Open `esp32/BT7_SmartStick.ino`
4. Select your ESP32 board and COM port
5. Click **Upload**

### How It Works

- The ESP32 advertises itself over Bluetooth as **`BT7_SmartStick`**
- When a button is pressed, it sends a plain-text command over Bluetooth SPP
- The Android app listens for these commands and triggers the corresponding feature
- The onboard LED blinks to confirm button presses and connection status

---

## 🎮 Button → Feature Mapping

| Button Press | Command Sent | Android Action |
|-------------|-------------|----------------|
| BTN1 (GPIO 4) | `VISION` | Captures photo → GPT-4o describes surroundings |
| BTN2 (GPIO 5) | `TEXT` | Captures photo → AI reads visible text aloud |
| BTN3 (GPIO 18) | `CURRENCY` | Captures photo → Detects and announces currency |
| BTN4 (GPIO 19) | `EMERGENCY` | Sends GPS SMS + auto-calls emergency contact |
| BTN5 (GPIO 21) | `ASSISTANT` | Activates voice Q&A with GPT-4o |
| BTN6 (GPIO 22) | `NAVIGATE` | Speaks current GPS address |
| BTN7 (GPIO 23) | `SCANNER` | Starts real-time TFLite object detection |

---

## 🌐 Languages Supported

The voice output supports multiple languages:

- 🇬🇧 English
- 🇮🇳 हिंदी (Hindi)
- 🇮🇳 ગુજરાતીઓ (Gujarati)

---

## ⚠️ Important Notes

- **API Key:** Replace the hardcoded `API_KEY` in `MainActivity.kt` before building. Never push it to GitHub.
- **TFLite Model:** `detect.tflite` is **not included** in this repo due to file size. Add your own to `app/src/main/assets/`.
- **Bluetooth:** This project uses **Bluetooth Classic (SPP)**, not BLE. Make sure your ESP32 supports it (most do).
- **Permissions:** The app requires Camera, Location, Microphone, SMS, and Bluetooth permissions at runtime.
- **Minimum SDK:** Android 7.0 (API 24) or higher.

-big notice is objject ditection by TFlite is not work for now i will fix it in future if someone find it so suggest me how to fix it at 7572927317 wp 

## 👨‍💻 Author

**Tushar Barad**  
🏢 BT7.pvt.ltd  
🔗 [GitHub: @Tushar-Barad](https://github.com/Tushar-Barad)

---

<p align="center">Made with ❤️ to empower the visually impaired community</p>
