# RACA: RIVALS Adaptive Combat Agent — Experimental Android Prototype

**No pretrained model.** This app experiments with mathematical combat heuristics, motion-region tracking, touchscreen gestures and persistent online rewards.

## Status and important limits
- A **motion region is not a verified human/player detector**. Camera pans, animations, UI elements and shadows can produce false positives.
- The app has no privileged access to RIVALS state. Health, ammo, hitboxes, hit registration, wins and kills are **not automatically observable** in v0.1. Tap notification or app buttons to mark kills/deaths/wins/losses.
- "Classical" scores are heuristics applied to visible features, **not** evidence of professional-player performance. Online learning is a contextual linear advantage learner with temporal credit assignment; it cannot guarantee improvement.
- Input injection is **off by default** and runs only when the active accessibility window belongs to `com.roblox.client`; that check can fail on some devices (in that case the app won't inject).
- A private Roblox server or player consent does not necessarily authorize third-party bot control. Use automatic input **only when the experience operator explicitly authorizes it**. No bypass of anti-cheat, modified clients, memory injection or vulnerability exploitation.
- Android grants MediaProjection separately for each capture session. User must enable accessibility permission explicitly; never share your screen-capture grant.

## Build APK
GitHub Actions > **Android debug APK** > latest successful build > artifact `RACA-debug-apk`. Download artifact ZIP and extract `app-debug.apk`.
Workflow also runs plain-Java unit tests. Locally: JDK 17, Android SDK platform 35 and Gradle 8.10.2; run `gradle :app:testDebugUnitTest :app:assembleDebug`.

## Setup
1. Install debug APK. Enable **RACA Touch Service** in Android Accessibility settings.
2. Open RACA in landscape. Press **Start screen capture** and allow **entire-screen** capture.
3. Switch to a **specifically authorized** test session. Observe detection statistics in RACA first.
4. Tap **Refresh snapshot** for the last in-game frame. Choose a control name and tap its location in the snapshot to calibrate. Layout varies by device; default positions are estimates only.
5. Enable **Auto input (experimental)** only after verifying consent and calibration. There is no anti-cheat bypass.
6. Report kills/deaths using the foreground notification buttons, or all four reward buttons in RACA. Each reward adjusts recent policy choices and persists scores.

## Internals
- `MotionVision`: grayscale, tiny downsample, global camera-translation compensation, connected components, tracked candidate with confidence and normalized motion velocity.
- `ClassicalBrain`: rule-based candidate actions, dozens of geometry/momentum/aim/persistence score contributions and conservative fire gating.
- `OnlineLearner`: per-action contextual linear value with bounded weights, recent decision trace and gamma-discounted sparse reward.
- `GestureService`: limited to Roblox accessibility foreground; 3 continuous touches (joystick, camera, fire) with `StrokeDescription.continueStroke`; jump/knife are brief taps.
- `CaptureService`: explicit Android MediaProjection foreground service, zero saved images, notifications for rewards and stop.
- `Calibration`: normalized touch controls saved locally.

This is a development baseline. Real skill requires validating game detection, input responsiveness and reward attribution on a specific device. It is not a verified fully autonomous high-skill RIVALS agent.
