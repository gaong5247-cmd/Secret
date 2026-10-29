package io.github.gaong.raca;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.util.Locale;

/**
 * On-device MediaProjection + motion vision + policy loop.
 * Frames remain in memory only. Outcome detection is MANUAL in this release.
 */
public final class CaptureService extends Service {
    public static final String ACTION_START = "raca.START";
    public static final String ACTION_STOP = "raca.STOP";
    public static final String ACTION_KILL = "raca.KILL";
    public static final String ACTION_DEATH = "raca.DEATH";
    public static final String ACTION_WIN = "raca.WIN";
    public static final String ACTION_LOSS = "raca.LOSS";
    public static final String EXTRA_CODE = "projection_code";
    public static final String EXTRA_DATA = "projection_data";
    private static final String CHANNEL = "raca_experiment";
    private static final int NOTIF_ID = 11;
    private static final int FPS_INTERVAL_MS = 100;

    private static volatile CaptureService active;
    public static volatile Bitmap lastFrame = null;
    public static volatile String stats = "Capture not started";
    public static volatile int processed = 0;
    public static volatile int lastOutcome = 0;
    private static volatile String lastReward = "none";

    private final MotionVision vision = new MotionVision();
    private final ClassicalBrain brain = new ClassicalBrain();
    private OnlineLearner learner;
    private HandlerThread thread;
    private Handler captureHandler;
    private ImageReader reader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private int width, height, density;
    private boolean running = false;
    private long lastProcessedMs = 0;

    public static boolean isRunning() { return active != null && active.running; }
    public static String info() {
        CaptureService s = active;
        int n = s != null && s.learner != null ? s.learner.outcomes() : 0;
        return "Frames: " + processed + " | outcomes: " + n + " | last: "
                + lastReward + "\n" + stats + "\n" + GestureService.status();
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        learner = new OnlineLearner(ClassicalBrain.Tactic.values().length);
        learner.restore(getSharedPreferences("raca_learning", 0).getString("policy", ""));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "RACA screen experiment", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_KILL.equals(action) || ACTION_DEATH.equals(action)
                || ACTION_WIN.equals(action) || ACTION_LOSS.equals(action)) {
            report(action);
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33)
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        else data = intent.getParcelableExtra(EXTRA_DATA);
        if (code == 0 || data == null) {
            stats = "Projection permission missing";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIF_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("projection null");
            thread = new HandlerThread("raca-screen-vision");
            thread.start();
            captureHandler = new Handler(thread.getLooper());
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE))
                    .getDefaultDisplay().getRealMetrics(metrics);
            density = metrics.densityDpi;
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    stats = "Screen projection stopped by Android";
                    stopSelf();
                }
                @Override public void onCapturedContentResize(int w, int h) {
                    if (Build.VERSION.SDK_INT >= 34 && captureHandler != null
                            && w > 100 && h > 100) {
                        captureHandler.post(() -> resize(w, h));
                    }
                }
            };
            projection.registerCallback(projectionCallback, captureHandler);
            running = true;
            prepareReader(width, height);
            virtualDisplay = projection.createVirtualDisplay(
                    "RACA capture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, captureHandler);
            stats = "Screen capture active. Move to authorized game session.";
        } catch (Exception ex) {
            stats = "Capture failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void prepareReader(int w, int h) {
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(ir -> {
            Image img = null;
            try {
                img = ir.acquireLatestImage();
                if (img == null || !running) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastProcessedMs < FPS_INTERVAL_MS) return;
                lastProcessedMs = now;
                GameState state = vision.analyze(img, now);
                ClassicalBrain.Decision d = brain.decide(state, learner);
                GestureService.updateDecision(d);
                processed++;
                // Keep only an in-Roblox view for calibration when possible.
                if (processed % 8 == 0 && (GestureService.isRobloxForeground()
                        || lastFrame == null)) lastFrame = vision.snapshot();
                if (processed % 4 == 0) {
                    stats = String.format(Locale.US,
                            "Candidate: %s | confidence %.2f | tactic %s | score %.2f\n%s",
                            state.candidate && state.consecutiveFrames >= 2,
                            state.confidence, d.tactic.name(), d.score, vision.debug());
                }
            } catch (RuntimeException ex) {
                stats = "Frame processing error: " + ex.getClass().getSimpleName();
                GestureService.updateDecision(ClassicalBrain.Decision.idle());
            } finally {
                if (img != null) img.close();
            }
        }, captureHandler);
    }

    private void resize(int w, int h) {
        if (!running || virtualDisplay == null || (width == w && height == h)) return;
        ImageReader old = reader;
        try {
            // Android 14+ provides content-resize events without a second projection token.
            width = w; height = h;
            prepareReader(w, h);
            virtualDisplay.resize(w, h, density);
            virtualDisplay.setSurface(reader.getSurface());
            if (old != null) old.close();
        } catch (RuntimeException ex) {
            stats = "Resize failed: " + ex.getClass().getSimpleName();
            if (old != null && old != reader) old.close();
        }
    }

    private void report(String action) {
        double value;
        String label;
        switch (action) {
            case ACTION_KILL: value = 1; label = "kill +1"; break;
            case ACTION_DEATH: value = -1; label = "death -1"; break;
            case ACTION_WIN: value = 2; label = "win +2"; break;
            case ACTION_LOSS: value = -2; label = "loss -2"; break;
            default: return;
        }
        learner.reward(value);
        getSharedPreferences("raca_learning", 0).edit()
                .putString("policy", learner.serialize()).apply();
        lastReward = label;
        lastOutcome++;
    }

    private PendingIntent action(String action, int id) {
        Intent i = new Intent(this, CaptureService.class).setAction(action);
        return PendingIntent.getService(this, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("RACA: experimental screen analysis")
                .setContentText("Reward tags are manual; auto-touch is OFF by default")
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_input_add, "Kill +1", action(ACTION_KILL, 2)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_delete, "Death -1", action(ACTION_DEATH, 3)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP, 4)).build())
                .build();
    }

    @Override public void onDestroy() {
        running = false;
        GestureService.updateDecision(ClassicalBrain.Decision.idle());
        if (learner != null)
            getSharedPreferences("raca_learning", 0).edit()
                    .putString("policy", learner.serialize()).apply();
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
            reader.close();
            reader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        active = null;
        stats = "Capture stopped";
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
