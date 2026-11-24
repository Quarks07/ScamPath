# ScamPath

ScamPath checks a conversation supplied by the user. It shows where risk signals first appeared and can ask Llama 3.2 for an explanation based on those signals. The detector owns the verdict.

## Build and install

Use JDK 17 or later, Android SDK platform 36 and Build Tools 36.0.0. The checked build used JDK 25.0.2, the Gradle 9.5.0 wrapper and Android Gradle Plugin 9.2.1. The minimum Android version is Android 11, API 30. The APK targets ARM64 phones and also includes x86-64 libraries for emulator checks.

On Windows with the Android SDK configured in `ANDROID_HOME`:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices -l
& $adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `-s SERIAL` before `install` if multiple devices are attached.

## Install the local advisory model

Read the [Llama 3.2 Community License and model card](https://huggingface.co/experimentalmachines/Llama-3.2-1B-Instruct-ExecuTorch) before downloading. Allow about 2 GB of free device storage for transfer and installation. The Windows setup script downloads a pinned XNNPACK CPU export and tokenizer, verifies SHA-256 on the PC and device, and installs them in the debug app's private directory. Run with the app closed.

```powershell
powershell -ExecutionPolicy Bypass -File .\setup-model.ps1 -Adb $adb
```

Pass `-Serial SERIAL` if multiple devices are attached. Installing the model needs a one-time download. The app has no download step at runtime. Uninstalling the app deletes its model files.

| Component | Version or format |
| --- | --- |
| Runtime | ExecuTorch Android 1.5.1 |
| Advisory | Llama 3.2 1B Instruct, XNNPACK 8da4w GPTQ, 2,048-token context |
| Export | Hugging Face revision `08c5ee382572104634ed2e7c4d51e057c7184dd8` |
| Screenshot OCR | Bundled ML Kit Latin text recognition 16.0.1 |
| Tests | JUnit 4.13.2 |

## Run the same check

1. Open ScamPath. Paste a chat with one message per line, share selected text from another Android app, or choose a screenshot. Review extracted text before checking it.
2. Paste `You are shortlisted.` and check it. Then append `Pay the refundable registration fee.` on line 2 and check again. The second run should surface the recruitment claim and fee request, with concern rising on line 2.
3. Tap **Get a local explanation**. If the model is absent, the detector evidence and default next step remain available.
4. Repeat with airplane mode on and Wi-Fi and Bluetooth off. In the debug build, `adb logcat -d -s ScamPath:I` reports timings with no conversation text. Record generated advice separately from the detector result. For a real device, also record the phone model, SoC, OS and ABI.

The prototype uses rules instead of the concept's planned trained classifier. Link checks inspect address syntax inside the supplied chat. The app neither opens the link nor monitors network traffic. The bundled OCR supports Latin script and reads one screenshot at a time. A user may need to correct the extracted text or line order.

The JVM suite exercises synthetic legitimate and suspicious inputs, escalation ordering, rejection of invalid input and the boundary between chat text and the advisory prompt. It is a smoke check. No real-world accuracy claim follows from it.

## Phase 1 report

The [final project report](docs/ScamPath-PS3-Final-Report.pdf) describes the application, model setup, offline test and measurements. The demo and measurements use application revision `05df0b0` on an Android 16 x86-64 emulator. The APK includes ARM64 support. Physical Arm results are pending.
