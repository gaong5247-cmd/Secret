package io.github.gaong.raca;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** User-facing permission controls, calibration UI, safety switch and reward tags. */
public final class MainActivity extends Activity {
    private static final int PROJECTION_REQUEST = 21;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, sensitivityLabel, calibrationLabel;
    private ImageView preview;
    private Spinner selectedControl;
    private CheckBox authorized;
    private Bitmap shown;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(CaptureService.info());
            handler.postDelayed(this, 1100);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Fail closed after process restart. Never silently start autoplay.
        Calibration.auto(this, false);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 22);
        draw();
    }

    private int dp(int amount) { return (int) (amount * getResources().getDisplayMetrics().density + .5f); }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout horizontal() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private void addFlex(LinearLayout row, View view) {
        row.addView(view, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void draw() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(6), dp(12), dp(12));
        scroll.addView(column);

        column.addView(label("RACA · Classical Combat Research v0.1", 22));
        column.addView(label("화면의 움직임을 추적하는 실험 앱입니다. 사람을 정확히 식별하거나 실전 승리를 보장하지 않습니다.", 13));
        column.addView(label("자동 입력은 OFF가 기본값입니다. 게임 운영자의 명시적 허가가 있는 환경에서만 사용하세요.", 13));

        LinearLayout controls = horizontal();
        addFlex(controls, button("① 접근성 서비스", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        addFlex(controls, button("② 화면 캡처 시작", v -> beginProjection()));
        addFlex(controls, button("■ 중지", v -> {
            Calibration.auto(this, false);
            if (authorized != null) authorized.setChecked(false);
            sendAction(CaptureService.ACTION_STOP);
        }));
        column.addView(controls);

        authorized = new CheckBox(this);
        authorized.setText("게임 운영자가 외부 자동 조작을 허락한 테스트 환경임을 확인했습니다");
        column.addView(authorized);
        CheckBox auto = new CheckBox(this);
        auto.setText("③ 실험적 멀티터치 자동 조작 활성화");
        auto.setChecked(false);
        auto.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && (!authorized.isChecked() || !GestureService.isConnected())) {
                Calibration.auto(this, false);
                button.setChecked(false);
                Toast.makeText(this, "허가 확인과 접근성 서비스 연결이 필요합니다", Toast.LENGTH_LONG).show();
                return;
            }
            Calibration.auto(this, enabled);
        });
        column.addView(auto);

        status = label(CaptureService.info(), 13);
        column.addView(status);

        column.addView(label("보상 — 처치 +1 / 사망 -1 / 승리 +2 / 패배 -2 (직접 태그)", 15));
        LinearLayout rewards = horizontal();
        addFlex(rewards, button("Kill +1", v -> sendAction(CaptureService.ACTION_KILL)));
        addFlex(rewards, button("Death -1", v -> sendAction(CaptureService.ACTION_DEATH)));
        addFlex(rewards, button("Win +2", v -> sendAction(CaptureService.ACTION_WIN)));
        addFlex(rewards, button("Loss -2", v -> sendAction(CaptureService.ACTION_LOSS)));
        column.addView(rewards);

        LinearLayout calibrationRow = horizontal();
        selectedControl = new Spinner(this);
        ArrayAdapter<Calibration.Control> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                        Calibration.Control.values());
        selectedControl.setAdapter(adapter);
        calibrationRow.addView(selectedControl,
                new LinearLayout.LayoutParams(0, dp(48), 1f));
        addFlex(calibrationRow, button("마지막 게임 화면 갱신", v -> updateSnapshot()));
        column.addView(calibrationRow);
        calibrationLabel = label("게임 화면의 해당 버튼을 탭해 좌표를 보정하세요 (캡처 데이터는 메모리에만 저장).", 13);
        column.addView(calibrationLabel);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(false);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xff0b1720);
        column.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
        preview.setOnTouchListener((View v, MotionEvent event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP || shown == null) return true;
            float scale = Math.min(preview.getWidth() / (float) shown.getWidth(),
                    preview.getHeight() / (float) shown.getHeight());
            float left = (preview.getWidth() - shown.getWidth() * scale) / 2f;
            float top = (preview.getHeight() - shown.getHeight() * scale) / 2f;
            float x = (event.getX() - left) / (shown.getWidth() * scale);
            float y = (event.getY() - top) / (shown.getHeight() * scale);
            if (x < 0 || x > 1 || y < 0 || y > 1) return true;
            Calibration.Control c = (Calibration.Control) selectedControl.getSelectedItem();
            Calibration.set(this, c, x, y);
            calibrationLabel.setText(c.label + " = "
                    + String.format(java.util.Locale.US, "%.3f, %.3f", x, y));
            return true;
        });

        sensitivityLabel = label("", 13);
        column.addView(sensitivityLabel);
        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(140);
        sensitivity.setProgress(Math.round(Calibration.sensitivity(this) * 100) - 10);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                float value = .1f + progress / 100f;
                if (user) Calibration.sensitivity(MainActivity.this, value);
                sensitivityLabel.setText("Camera drag gain: " + String.format(
                        java.util.Locale.US, "%.2f", value));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sensitivityLabel.setText("Camera drag gain: " + Calibration.sensitivity(this));
        column.addView(sensitivity);
        column.addView(label("보정 정보: 캡처 중 Roblox로 전환한 뒤 게임 화면을 확보하고 앱으로 돌아와 갱신하세요. "
                + "기본 조이스틱·발사·점프·칼 위치는 임시 추정치입니다.", 13));
        setContentView(scroll);
        updateSnapshot();
    }

    private void updateSnapshot() {
        shown = CaptureService.lastFrame;
        if (shown != null) {
            preview.setImageBitmap(shown);
        } else if (calibrationLabel != null) {
            calibrationLabel.setText("게임 프레임이 없습니다. 화면 캡처를 시작한 뒤 다시 시도하세요.");
        }
    }

    private void beginProjection() {
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION_REQUEST);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Screen capture unavailable: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PROJECTION_REQUEST && result == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_CODE, result)
                    .putExtra(CaptureService.EXTRA_DATA, data);
            startForegroundService(service);
        }
    }

    private void sendAction(String action) {
        startService(new Intent(this, CaptureService.class).setAction(action));
        handler.postDelayed(() -> status.setText(CaptureService.info()), 100);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
