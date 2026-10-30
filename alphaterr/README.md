# AlphaTerr — experimental territory AI

An independent **Territorial.io-style** research sandbox: grid territory simulator, time-bounded classical lookahead, CNN + sparse NNUE-like map encoder, automatic simulated rewards, and an opt-in Android screen-reading prototype.

## Reality check
- **Not a proven Territorial.io bot**. The simulation is an approximation, not the game's original rules.
- The Android app uses conservative **classical heuristics**, not an embedded trained CNN/NNUE model.
- Real terrain-colour recognition and control calibration need on-device verification.
- Supports research and operator-approved **single-player computer opponents only**; input defaults OFF.
- Android compilation passed GitHub Actions on the development branch; gameplay strength is not established.

## Python research quickstart

From the repository root:

```bash
python -m pip install -e './alphaterr[test,train,vision]'
python -m pytest alphaterr/tests -q
python -m alphaterr.demo --seed 7 --ticks 80
python -m alphaterr.train --episodes 80 --seed 7 --steps 120 --out alphaterr.pt
```

To omit the large Torch dependency, use `python -m pip install -e './alphaterr[test]'` and run non-neural unit tests and demo.

### Algorithm
- `alphaterr/sim.py` is a deterministic approximate opponent environment.
- `alphaterr/planner.py` provides time-bounded classical beam search.
- `alphaterr/model.py` defines separate policy/value outputs plus a CNN tower and sparse NNUE-inspired accumulator.
- `alphaterr/train.py` trains with automatic simulator reward and tests model-vs-baseline on hold-out seeds.
- `alphaterr/vision.py` and `alphaterr/live.py` are optional image-only desktop observers and controllers.

## Android prototype

The existing Beckenbauer AI remains in place. In the installed debug APK:
1. Open **Beckenbauer AI Lab** and select **OPEN ALPHATERR**.
2. Turn on the AlphaTerr accessibility service, authorize screen capture, then switch to a confirmed single-player AI match.
3. Refresh the captured game screenshot and calibrate a solid point inside **your** territory and the slider/button coordinates.
4. Observe the territory and OCR status before arming any input.
5. **STOP ALL** disables control. Do not use in online/multiplayer matches.

The Android build is triggered by a pull request against `main`. This branch is intentionally a **draft PR**, not merged into the default branch.
