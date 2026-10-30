package io.github.gaong.beck;

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
import java.util.Locale;

/** Human-controlled consent, calibration, diagnostics, observation and emergency stop. */
public final class MainActivity extends Activity {
  private static final int CAPTURE_REQUEST=42;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private TextView status,calibrationLabel,aggressionLabel;
  private Spinner control;
  private ImageView snapshot;
  private CheckBox allow,auto;
  private Bitmap shown;
  private final Runnable updater=new Runnable(){
    @Override public void run(){
      if(status!=null)status.setText(CaptureService.info());
      handler.postDelayed(this,1000);
    }
  };
  private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5);}
  private TextView text(String value,int size){
    TextView t=new TextView(this);t.setText(value);t.setTextSize(size);
    t.setPadding(dp(7),dp(7),dp(7),dp(7));return t;
  }
  private Button button(String label,View.OnClickListener click){
    Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setOnClickListener(click);return b;
  }
  private LinearLayout row(){
    LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);return r;
  }
  private void flex(LinearLayout r,View v){
    r.addView(v,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
  }
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    Calibration.auto(this,false); // Never silently restart automatic input.
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        !=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},71);
    draw();
  }
  private boolean calibrated(){
    // Do not enable automatic control until minimum football inputs are checked visually.
    for(Calibration.Control c:new Calibration.Control[]{
        Calibration.Control.JOYSTICK,Calibration.Control.CAMERA,
        Calibration.Control.TACKLE,Calibration.Control.PASS,Calibration.Control.SHOOT}){
      if(!Calibration.prefs(this).contains(c.name()+"x"))return false;
    }
    return true;
  }
  private void draw(){
    ScrollView scroll=new ScrollView(this);
    LinearLayout main=new LinearLayout(this);
    main.setOrientation(LinearLayout.VERTICAL);
    main.setPadding(dp(12),dp(9),dp(12),dp(20));scroll.addView(main);
    main.addView(text("BECKENBAUER AI LAB · League Football research",22));
    main.addView(text("OFFLINE YOLO sports-ball + Kalman + conservative screen-space libero. "+
      "Not a verified autonomous professional player; Roblox football can be missed by COCO YOLO.",13));
    main.addView(text("Auto input: only if the experience OPERATOR explicitly authorizes third-party bot play. "+
      "A private server alone does not grant permission. No client modification or cheat bypass.",13));
    LinearLayout buttons=row();
    flex(buttons,button("① Accessibility",v->
      startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
    flex(buttons,button("② Start capture",v->beginCapture()));
    flex(buttons,button("■ STOP",v->stopAll()));
    main.addView(buttons);
    main.addView(button("OPEN ALPHATERR · Territory AI Lab", v->startActivity(new Intent(this,AlphaTerrActivity.class))));

    allow=new CheckBox(this);
    allow.setText("운영자가 이 테스트 환경에서 외부 자동 조작을 명시적으로 허가했음을 확인합니다");
    main.addView(allow);
    auto=new CheckBox(this);auto.setText("③ Auto joystick / look / buttons (EXPERIMENTAL)");
    auto.setChecked(false);
    auto.setOnCheckedChangeListener((v,checked)->{
      if(checked && (!allow.isChecked() || !TouchService.connected()
          || !CaptureService.running() || !calibrated())){
        Calibration.auto(this,false);
        v.setChecked(false);
        Toast.makeText(this,"운영자 허가, 캡처, 접근성 연결, 5개 버튼 보정이 필요합니다",
          Toast.LENGTH_LONG).show();return;
      }
      Calibration.auto(this,checked);
    });
    main.addView(auto);

    CheckBox own=new CheckBox(this);
    own.setText("공 소유 가정 (수동 테스트용; 자동 판별 아님)");
    own.setChecked(Calibration.possession(this));
    own.setOnCheckedChangeListener((b,checked)->Calibration.possession(this,checked));
    main.addView(own);

    status=text(CaptureService.info(),13);main.addView(status);
    main.addView(text("전술 보상: 직접 성공/실패를 표시하면 로컬 공격성 수치만 소폭 조정합니다. 강화학습 모델이 아닙니다.",13));
    LinearLayout rewards=row();
    flex(rewards,button("차단 성공 +",v->reward(.025f)));
    flex(rewards,button("파울 / 실점 -",v->reward(-.045f)));
    flex(rewards,button("안전한 전개 +",v->reward(.012f)));
    main.addView(rewards);

    aggressionLabel=text("",13);main.addView(aggressionLabel);
    SeekBar bar=new SeekBar(this);
    bar.setMax(100);bar.setProgress(Math.round(Calibration.aggression(this)*100));
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      @Override public void onProgressChanged(SeekBar b,int value,boolean fromUser){
        if(fromUser)Calibration.aggression(MainActivity.this,value/100f);
        aggressionLabel.setText("수비 적극성 "+String.format(Locale.US,"%.2f",
          Calibration.aggression(MainActivity.this))+" (태클은 0.55 초과부터 고려)");
      }
      @Override public void onStartTrackingTouch(SeekBar b){}
      @Override public void onStopTrackingTouch(SeekBar b){}
    });
    main.addView(bar);
    aggressionLabel.setText("수비 적극성 "+Calibration.aggression(this));

    LinearLayout cal=row();
    control=new Spinner(this);
    control.setAdapter(new ArrayAdapter<>(this,
      android.R.layout.simple_spinner_dropdown_item,Calibration.Control.values()));
    cal.addView(control,new LinearLayout.LayoutParams(0,dp(48),1f));
    flex(cal,button("④ Refresh captured frame",v->refreshSnapshot()));
    main.addView(cal);
    calibrationLabel=text("컨트롤 종류 선택 → 아래 실제 게임 화면에서 버튼 위치 탭. "+
      "기본 좌표는 추정치이므로 반드시 보정해 주세요.",13);
    main.addView(calibrationLabel);
    snapshot=new ImageView(this);snapshot.setScaleType(ImageView.ScaleType.FIT_CENTER);
    snapshot.setBackgroundColor(0xff112027);
    main.addView(snapshot,new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,dp(180)));
    snapshot.setOnTouchListener((v,event)->{
      if(event.getActionMasked()!=MotionEvent.ACTION_UP || shown==null)return true;
      float scale=Math.min(snapshot.getWidth()/(float)shown.getWidth(),
        snapshot.getHeight()/(float)shown.getHeight());
      if(scale<=0)return true;
      float left=(snapshot.getWidth()-shown.getWidth()*scale)/2f;
      float top=(snapshot.getHeight()-shown.getHeight()*scale)/2f;
      float x=(event.getX()-left)/(shown.getWidth()*scale);
      float y=(event.getY()-top)/(shown.getHeight()*scale);
      if(x>=0&&x<=1&&y>=0&&y<=1){
        Calibration.Control c=(Calibration.Control)control.getSelectedItem();
        Calibration.set(this,c,x,y);
        calibrationLabel.setText(String.format(Locale.US,
          "Calibrated %s = %.3f, %.3f. Required controls ready: %s",
          c.label,x,y,calibrated()?"YES":"NO"));
      }
      return true;
    });
    main.addView(text("참고: YOLO는 1ms마다 영상을 새로 관측할 수 없습니다. Kalman이 영상 사이 위치를 예측합니다. "+
      "실전 조작 성공 여부, 팀/골대 인식, Project UAI 연동은 아직 검증되지 않았습니다.",12));
    setContentView(scroll);refreshSnapshot();
  }
  private void reward(float delta){
    Calibration.aggression(this,Calibration.aggression(this)+delta);
    aggressionLabel.setText("수비 적극성 "+String.format(Locale.US,"%.2f",
      Calibration.aggression(this))+" (보상 반영; 슬라이더는 재진입 시 갱신)");
  }
  private void refreshSnapshot(){
    shown=CaptureService.preview;
    if(shown!=null)snapshot.setImageBitmap(shown);
    else if(calibrationLabel!=null)calibrationLabel.setText(
      "캡처 프레임이 아직 없습니다. 접근성 서비스 연결과 게임 화면이 필요합니다.");
  }
  private void beginCapture(){
    try{
      MediaProjectionManager pm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      startActivityForResult(pm.createScreenCaptureIntent(),CAPTURE_REQUEST);
    }catch(RuntimeException err){
      Toast.makeText(this,"Screen capture unavailable: "+err.getMessage(),Toast.LENGTH_LONG).show();
    }
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req==CAPTURE_REQUEST && result==RESULT_OK && data!=null){
      Intent i=new Intent(this,CaptureService.class).setAction(CaptureService.START)
        .putExtra(CaptureService.EXTRA_CODE,result).putExtra(CaptureService.EXTRA_DATA,data);
      startForegroundService(i);
    }
  }
  private void stopAll(){
    Calibration.auto(this,false);Calibration.possession(this,false);
    TouchService.update(SoccerBrain.Decision.idle());
    if(auto!=null)auto.setChecked(false);
    startService(new Intent(this,CaptureService.class).setAction(CaptureService.STOP));
  }
  @Override protected void onResume(){
    super.onResume();handler.removeCallbacks(updater);handler.post(updater);
  }
  @Override protected void onPause(){
    handler.removeCallbacks(updater);super.onPause();
  }
}
