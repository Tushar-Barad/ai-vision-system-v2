# 🦯 BT7 Smart Stick - AI Vision Assistant

An Android app for the **BT7 Smart Stick** — an AI-powered assistive device for visually impaired users.  
Developed by **Tushar Barad** | BT7.pvt.ltd

---

## Features

| Feature | Description |
|---|---|
| 👁️ Vision AI | Describes surroundings using GPT-4o Vision |
| 📝 Text Reader | Reads any visible text (OCR via AI) |
| 💵 Currency | Detects Indian Rupee notes and total |
| 🚨 Emergency | Sends GPS location via SMS + auto-calls contact |
| 🤖 Voice Assistant | Voice Q&A powered by GPT-4o |
| 🗺️ Navigation | Speaks current address via GPS |
| 🔄 Object Scanner | Real-time obstacle detection using TensorFlow Lite |

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **AI:** OpenAI GPT-4o Vision API
- **Object Detection:** TensorFlow Lite (`detect.tflite`)
- **Hardware:** ESP32 via Bluetooth SPP
- **Location:** Google Play Services Fused Location
- **Camera:** CameraX

---

## Project Structure

```
BT7v2/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/bt7v2/
│   │   │   ├── MainActivity.kt       # Main app logic + UI
│   │   │   └── ObjectDetector.kt     # TFLite object detection wrapper
│   │   ├── assets/
│   │   │   └── detect.tflite         # ← Place your TFLite model here
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Setup

1. **Clone the repo**
   ```bash
   git clone https://github.com/YOUR_USERNAME/BT7v2.git
   ```

2. **Add your TFLite model**  
   Place `detect.tflite` in `app/src/main/assets/`  
   (Use COCO SSD MobileNet or any compatible model)

3. **Add your API Key**  
   In `MainActivity.kt`, replace the `API_KEY` value with your OpenAI key.  
   > ⚠️ For production, store this in `local.properties` or use a backend proxy — never commit API keys.

4. **Open in Android Studio** and build.

---

## Hardware

- ESP32 with Bluetooth Classic (SPP)
- Sends commands: `VISION`, `TEXT`, `CURRENCY`, `EMERGENCY`, `ASSISTANT`, `NAVIGATE`, `SCANNER`

---

## Languages Supported

- 🇬🇧 English
- 🇮🇳 हिंदी (Hindi)
- 🇮🇳 ગુજરાતી (Gujarati)

---

## ⚠️ Important Notes

- Replace the hardcoded `API_KEY` before publishing
- `detect.tflite` model is not included — add your own to `app/src/main/assets/`
- Minimum SDK: Android 7.0 (API 24)
