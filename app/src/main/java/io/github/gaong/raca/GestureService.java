package io.github.gaong.raca;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Opt-in multi-pointer touch controller. Does not inject into modified game clients.
 * Only emits gestures if Roblox's window is verifiably foregrounded.
 */
public final class GestureService extends AccessibilityService {
    private static final String ROBLOX = "com.roblox.client";
    private static final int DURATION = 112;
    private static volatile GestureService instance;
    private static volatile ClassicalBrain.Decision latest = ClassicalBrain.Decision.idle();
    private static volatile String status = "Accessibility not connected";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastPackage = "";
    private long lastEventMs = 0;
    private boolean busy = false;

    private GestureDescription.StrokeDescription stickStroke, cameraStroke, fireStroke;
    private float stickX, stickY, cameraX, cameraY, fireX, fireY;
    private long lastJump = 0, lastKnife = 0;

    public static void updateDecision(ClassicalBrain.Decision d) {
        latest = (d == null) ? ClassicalBrain.Decision.idle() : d;
    }
    public static boolean isConnected() { return instance != null; }
    public static boolean isRobloxForeground() {
        GestureService s = instance;
        return s != null && s.robloxForeground();
    }
    public static String status() { return status; }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            lastPackage = event.getPackageName().toString();
            lastEventMs = SystemClock.uptimeMillis();
        }
    }
    @Override public void onInterrupt() {
        clear();
        status = "Interrupted";
    }
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        status = "Touch service connected (auto OFF by default)";
        handler.post(loop);
    }
    @Override public boolean onUnbind(android.content.Intent intent) {
        handler.removeCallbacks(loop);
        clear();
        instance = null;
        status = "Touch service disconnected";
        return super.onUnbind(intent);
    }

    private boolean robloxForeground() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String p = root.getPackageName().toString();
                root.recycle();
                return ROBLOX.equals(p);
            }
            if (root != null) root.recycle();
        } catch (Exception ignored) { }
        return ROBLOX.equals(lastPackage)
                && SystemClock.uptimeMillis() - lastEventMs < 120000;
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (instance != GestureService.this) return;
            if (busy) {
                handler.postDelayed(this, 60);
                return;
            }
            boolean allowed = Calibration.auto(GestureService.this) && robloxForeground();
            ClassicalBrain.Decision d = latest;
            if (!allowed) {
                clear();
                status = Calibration.auto(GestureService.this)
                    ? "Waiting for verified Roblox foreground"
                    : "Auto input OFF";
                handler.postDelayed(this, 180);
                return;
            }
            if (!d.target && stickStroke == null && cameraStroke == null && fireStroke == null) {
                status = "Roblox active; no reliable motion candidate";
                handler.postDelayed(this, 120);
                return;
            }
            performTick(d);
        }
    };

    private float unitX(Calibration.Control c) {
        return Calibration.x(this, c) * getResources().getDisplayMetrics().widthPixels;
    }
    private float unitY(Calibration.Control c) {
        return Calibration.y(this, c) * getResources().getDisplayMetrics().heightPixels;
    }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private Path path(float startX, float startY, float endX, float endY) {
        Path p = new Path();
        p.moveTo(startX, startY);
        p.lineTo(endX, endY);
        return p;
    }

    private static final class NewStrokes {
        GestureDescription.StrokeDescription stick, camera, fire;
        float sx, sy, cx, cy, fx, fy;
    }

    private void performTick(ClassicalBrain.Decision d) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        if (w < 100 || h < 100) {
            status = "Screen dimensions unavailable";
            handler.postDelayed(loop, 250);
            return;
        }
        final NewStrokes next = new NewStrokes();
        GestureDescription.Builder builder = new GestureDescription.Builder();

        if (d.target) {
            float originX = unitX(Calibration.Control.JOYSTICK);
            float originY = unitY(Calibration.Control.JOYSTICK);
            float radius = Math.min(w, h) * .085f;
            double length = Math.max(1, Math.hypot(d.moveX, d.moveY));
            next.sx = clamp(originX + (float) (d.moveX / length) * radius, 2, w - 2);
            next.sy = clamp(originY - (float) (d.moveY / length) * radius, 2, h - 2);
            Path stickPath = stickStroke == null
                    ? path(originX, originY, next.sx, next.sy)
                    : path(stickX, stickY, next.sx, next.sy);
            next.stick = (stickStroke == null)
                    ? new GestureDescription.StrokeDescription(stickPath, 0, DURATION, true)
                    : stickStroke.continueStroke(stickPath, 0, DURATION, true);
            builder.addStroke(next.stick);

            float originCamX = unitX(Calibration.Control.CAMERA);
            float originCamY = unitY(Calibration.Control.CAMERA);
            // The screen-pixel gain is a calibratable approximation.
            float dx = (float) (d.aimX * w * Calibration.sensitivity(this));
            float dy = (float) (d.aimY * h * Calibration.sensitivity(this));
            float cX = cameraStroke == null ? originCamX : cameraX;
            float cY = cameraStroke == null ? originCamY : cameraY;
            if (cX + dx > w * .84 || cX + dx < w * .40
                    || cY + dy > h * .83 || cY + dy < h * .22) {
                // Complete prior stroke first. Next dispatch can re-center it.
                if (cameraStroke != null) {
                    Path finish = path(cX, cY, cX, cY);
                    builder.addStroke(cameraStroke.continueStroke(finish, 0, DURATION, false));
                }
                next.camera = null;
            } else {
                next.cx = clamp(cX + dx, 2, w - 2);
                next.cy = clamp(cY + dy, 2, h - 2);
                Path aimPath = path(cX, cY, next.cx, next.cy);
                next.camera = cameraStroke == null
                        ? new GestureDescription.StrokeDescription(aimPath, 0, DURATION, true)
                        : cameraStroke.continueStroke(aimPath, 0, DURATION, true);
                builder.addStroke(next.camera);
            }

            float fx = unitX(Calibration.Control.FIRE), fy = unitY(Calibration.Control.FIRE);
            if (d.fire) {
                next.fx = fx; next.fy = fy;
                Path p = fireStroke == null ? path(fx, fy, fx, fy)
                        : path(fireX, fireY, fx, fy);
                next.fire = fireStroke == null
                        ? new GestureDescription.StrokeDescription(p, 0, DURATION, true)
                        : fireStroke.continueStroke(p, 0, DURATION, true);
                builder.addStroke(next.fire);
            } else if (fireStroke != null) {
                builder.addStroke(fireStroke.continueStroke(
                        path(fireX, fireY, fireX, fireY), 0, DURATION, false));
            }

            long now = SystemClock.uptimeMillis();
            if (d.jump && now - lastJump > 900) {
                float x = unitX(Calibration.Control.JUMP), y = unitY(Calibration.Control.JUMP);
                builder.addStroke(new GestureDescription.StrokeDescription(path(x, y, x, y),
                        0, 80, false));
                lastJump = now;
            }
            if (d.knife && now - lastKnife > 1250) {
                float x = unitX(Calibration.Control.KNIFE), y = unitY(Calibration.Control.KNIFE);
                builder.addStroke(new GestureDescription.StrokeDescription(path(x, y, x, y),
                        0, 75, false));
                lastKnife = now;
            }
        } else {
            // Release persistent fingers when target disappears.
            if (stickStroke != null)
                builder.addStroke(stickStroke.continueStroke(
                        path(stickX, stickY, stickX, stickY), 0, DURATION, false));
            if (cameraStroke != null)
                builder.addStroke(cameraStroke.continueStroke(
                        path(cameraX, cameraY, cameraX, cameraY), 0, DURATION, false));
            if (fireStroke != null)
                builder.addStroke(fireStroke.continueStroke(
                        path(fireX, fireY, fireX, fireY), 0, DURATION, false));
        }

        final GestureDescription gesture;
        try {
            gesture = builder.build();
        } catch (RuntimeException ex) {
            clear();
            status = "Gesture build error: " + ex.getClass().getSimpleName();
            handler.postDelayed(loop, 200);
            return;
        }
        busy = true;
        try {
            boolean accepted = dispatchGesture(gesture,
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gestureDescription) {
                        busy = false;
                        stickStroke = next.stick; cameraStroke = next.camera; fireStroke = next.fire;
                        stickX = next.sx; stickY = next.sy;
                        cameraX = next.cx; cameraY = next.cy;
                        fireX = next.fx; fireY = next.fy;
                        status = "Roblox gestures: " + (next.stick != null ? "move " : "")
                                + (next.camera != null ? "aim " : "")
                                + (next.fire != null ? "fire" : "");
                        handler.postDelayed(loop, 16);
                    }
                    @Override public void onCancelled(GestureDescription gestureDescription) {
                        busy = false;
                        clear();
                        status = "Gesture cancelled (device/game compatibility)";
                        handler.postDelayed(loop, 160);
                    }
                }, handler);
            if (!accepted) {
                busy = false;
                clear();
                status = "Android rejected gesture";
                handler.postDelayed(loop, 200);
            }
        } catch (RuntimeException ex) {
            busy = false;
            clear();
            status = "Gesture exception: " + ex.getClass().getSimpleName();
            handler.postDelayed(loop, 300);
        }
    }

    private void clear() {
        stickStroke = null; cameraStroke = null; fireStroke = null;
        busy = false;
    }
}
