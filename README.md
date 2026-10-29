# Beckenbauer AI Lab — Android (experimental)

An **opt-in football vision & control research app**, designed for **operator-approved test sessions** and a local imitation of a defending libero. This replaces the old RIVALS prototype. Prior sources are preserved on the branch `backup-raca-2026-10-29`.

## What is actually implemented
* Android MediaProjection foreground capture with no saved screenshots.
* Offline YOLOv8n TFLite COCO **sports ball** (class 32) inference (model downloaded and hash-checked by CI), plus a constant-velocity 2D Kalman filter.
* Deterministic, conservative screen-space defending/interception heuristic with a local reward-tuned aggression setting.
* Opt-in accessibility joystick/camera gestures and calibrated tackle/pass/shoot/dribble button taps **only while Roblox is verified foreground**.
* Screen snapshot calibration UI, live confidence/latency diagnostics, manual possession mode, input emergency stop, and pure Java unit tests.

## IMPORTANT: Limits
This is not a verified high-skill League Football player. The detector was trained on real-world COCO imagery and **may fail completely on a tiny Roblox football**. Screen coordinates are **not game-world coordinates**. Ball possession, offside, team affiliation, goal direction, collision and server latency cannot be reliably reconstructed from this prototype. Buttons, control directions and control timing must be calibrated and tested. Its predictive loop cannot obtain new game observations every 1 ms. No explicit operator permission for League Football has been verified; a private server alone is not permission. **Use automatic control only where the experience operator explicitly authorizes it.** No Roblox client modification, anti-cheat bypass or memory inspection.

## Project UAI caveat
Project UAI is an independent Roblox Lua/client + bridge/Android Relay ecosystem, **not a drop-in lightweight offline Android model**. Its relay protocol has not been verified as a public control API for this APK. This app does not ship or claim Project UAI integration. It instead provides the local heuristic tactical layer without an API key; an optional verified advisory integration can be added later. See https://github.com/Project-Ptolemy/ProjectUAI

## Install and use
1. Actions → Beckenbauer Android APK → successful run → `BeckenbauerAI-debug-apk` artifact → extract `app-debug.apk`.
2. Install APK, open in landscape; turn on the app's accessibility service and grant screen-capture permission.
3. **Observe only** first. Detection should show **SPORTS_BALL** with confidence and coordinates. If not, do not activate automatic control.
4. Capture a game frame, open the app and calibrate JOYSTICK/CAMERA/TACKLE/PASS/SHOOT/DRIBBLE controls by tapping their screen positions on the preview. The defaults are placeholders, NOT actual League Football controls.
5. Confirm explicit operator authorization, then enable test input. Auto mode is off after every app launch; a stop button disables it. Tackle/pass/shoot behavior is conservative and not necessarily reliable.

## Build locally
JDK 17, Android SDK 35, Gradle 8.10.2. Obtain `app/src/main/assets/yolov8n.tflite` from the documented source (SHA256 below) before assembling.
`gradle :app:testDebugUnitTest :app:assembleDebug`.

## Model and attribution
YOLOv8n TFLite from https://huggingface.co/opencv/opencv_contribution/blob/refs%2Fpr%2F14/yolov8n/yolov8n.tflite
SHA256: `a2dde486ad6ec930ec3be2f9248351baef0ac4b1ad93a083fc80bf4884ed74ae`.
YOLOv8n model weights and Ultralytics exporter are AGPL-3.0; review redistribution obligations: https://github.com/ultralytics/ultralytics/blob/main/LICENSE.

This repository does not require an external API key for inference.
