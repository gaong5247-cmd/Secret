package io.github.gaong.beck;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** AlphaTerr opt-in single-player research UI; a completely separate module. */
public final class AlphaTerrActivity extends Activity {
  private static final int REQUEST_CAPTURE=84;
  private static final String[] CHOICES={
      "OWN TERRITORY", "SLIDER LEFT (0%)", "SLIDER RIGHT (100%)", "SWORD / ATTACK BUTTON"
  };
  private final Handler handler=new Handler(Looper.getMainLooper());
  private TextView diagnostics,calibrationInfo;
  private Spinner selector;
  private ImageView image;
  private CheckBox confirm,direct;
  private Bitmap shown;
  private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+.5f);}
  private Button button(String title,View.OnClickListener click){
    Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setOnClickListener(click);
    return b;
  }
  private TextView text(String body,int size){
    TextView t=new TextView(this);t.setText(body);t.setTextSize(size);
    t.setPadding(dp(8),dp(7),dp(8),dp(7));return t;
  }
  private LinearLayout row(){
    LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);return r;
  }
  private void flex(LinearLayout row,View view){
    row.addView(view,new LinearLayout.LayoutParams(0,dp(51),1f));
  }
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    ATSettings.armed(this,false); // Never silently resume automation
    draw();
  }
  private void draw(){
    ScrollView scroll=new ScrollView(this);
    LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);
    main.setPadding(dp(12),dp(9),dp(12),dp(25));scroll.addView(main);
    main.addView(text("ALPHATERR · NNUE + CNN research lab",23));
    main.addView(text("Single-player AI ONLY. Current Android decisions use a cautious classical "
      +"heuristic. The full CNN/NNUE trainer and beam search live under alphaterr/ "
      +"and are NOT deployed to this APK. No online multiplayer automation.",13));
    LinearLayout access=row();
    flex(access,button("1. Accessibility",v->
      startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
    flex(access,button("2. Screen capture",v->startCapture()));
    flex(access,button("STOP ALL",v->stopAll()));
    main.addView(access);
    confirm=new CheckBox(this);
    confirm.setText("I confirm this is SINGLE-PLAYER vs computer bots (no other players)");
    confirm.setChecked(ATSettings.confirmed(this));
    confirm.setOnCheckedChangeListener((b,checked)->{
      ATSettings.confirmed(this,checked);
      if(!checked)ATSettings.armed(this,false);
    });
    main.addView(confirm);
    direct=new CheckBox(this);
    direct.setText("Calibrated attack uses 2 taps (slider + target), no sword confirmation");
    direct.setChecked(ATSettings.direct(this));
    direct.setOnCheckedChangeListener((b,checked)->{
      ATSettings.direct(this,checked);
      ATSettings.armed(this,false);
    });
    main.addView(direct);
    main.addView(text("OBSERVE FIRST: start screen capture, switch to Territorial.io "
      +"single-player, then return here and refresh the captured frame. "
      +"Tap your own land colour, both slider endpoints, and sword button if needed.",12));
    diagnostics=text("Waiting for screen capture",13);main.addView(diagnostics);
    LinearLayout tools=row();
    selector=new Spinner(this);
    selector.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,CHOICES));
    tools.addView(selector,new LinearLayout.LayoutParams(0,dp(50),1f));
    flex(tools,button("Refresh screenshot",v->refresh()));
    main.addView(tools);
    calibrationInfo=text("No calibration yet",12);main.addView(calibrationInfo);
    image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackgroundColor(0xff12242b);
    main.addView(image,new LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,dp(250)));
    image.setOnTouchListener((v,event)->{
      if(event.getActionMasked()!=MotionEvent.ACTION_UP || shown==null)return true;
      float scale=Math.min(image.getWidth()/(float)shown.getWidth(),
                           image.getHeight()/(float)shown.getHeight());
      if(scale<=0)return true;
      float left=(image.getWidth()-shown.getWidth()*scale)*.5f;
      float top=(image.getHeight()-shown.getHeight()*scale)*.5f;
      float px=(event.getX()-left)/scale,py=(event.getY()-top)/scale;
      float x=px/shown.getWidth(),y=py/shown.getHeight();
      if(x<0||x>=1||y<0||y>=1){
        Toast.makeText(this,"Tap inside the screenshot",Toast.LENGTH_SHORT).show();return true;
      }
      String key;
      switch(selector.getSelectedItemPosition()){
        case 0:
          int sx=Math.max(0,Math.min(shown.getWidth()-1,(int)px));
          int sy=Math.max(0,Math.min(shown.getHeight()-1,(int)py));
          // Average local patch for solid fill, not a text-label pixel.
          int r=0,g=0,b=0,count=0;
          for(int yy=Math.max(0,sy-2);yy<=Math.min(shown.getHeight()-1,sy+2);yy++)
            for(int xx=Math.max(0,sx-2);xx<=Math.min(shown.getWidth()-1,sx+2);xx++){
              int p=shown.getPixel(xx,yy);r+=(p>>16)&255;g+=(p>>8)&255;b+=p&255;count++;
            }
          int rgb=0xff000000|((r/count)<<16)|((g/count)<<8)|(b/count);
          ATSettings.own(this,x,y,rgb);key="OWN #" + Integer.toHexString(rgb);
          break;
        case 1:ATSettings.point(this,"slideL",x,y);key="SLIDER LEFT";break;
        case 2:ATSettings.point(this,"slideR",x,y);key="SLIDER RIGHT";break;
        default:ATSettings.point(this,"sword",x,y);key="SWORD";break;
      }
      ATSettings.armed(this,false);
      calibrationInfo.setText(String.format(Locale.US,
        "%s calibrated at (%.3f, %.3f). Ready: %s",
        key,x,y,ATSettings.ready(this)?"YES":"NO"));
      return true;
    });
    LinearLayout actions=row();
    flex(actions,button("ARM (bots only)",v->arm()));
    flex(actions,button("DISARM",v->{
      ATSettings.armed(this,false);
      AlphaTerrTouchService.update(AlphaTerrBrain.Advice.hold("DISARMED"));
      refreshStatus();
    }));
    main.addView(actions);
    main.addView(text("SAFETY: automation begins OFF, requires your explicit consent, "
      +"stops on capture interruption, and touches only the official game's foreground "
      +"package. It must NOT be used in live multiplayer. Not yet validated against "
      +"actual game versions or map themes; do not assume a successful attack.",12));
    setContentView(scroll);
    refresh();
  }
  private void arm(){
    if(!confirm.isChecked()||!ATSettings.ready(this)||
        !AlphaTerrTouchService.connected()||!AlphaTerrCaptureService.running()){
      Toast.makeText(this,"Need single-player confirmation, capture, accessibility "
        +"and all calibrations",Toast.LENGTH_LONG).show();
      return;
    }
    ATSettings.armed(this,true);
    Toast.makeText(this,"Armed for single-player only; return to the game",
      Toast.LENGTH_LONG).show();
    refreshStatus();
  }
  private void refresh(){
    shown=AlphaTerrCaptureService.preview;
    if(shown!=null&&image!=null)image.setImageBitmap(shown);
    if(calibrationInfo!=null)calibrationInfo.setText(
      "Own calibrated="+ATSettings.hasOwn(this)
      +"  Slider="+(ATSettings.hasPoint(this,"slideL")&&ATSettings.hasPoint(this,"slideR"))
      +"  Sword="+ATSettings.hasPoint(this,"sword")
      +"  Ready="+ATSettings.ready(this));
    refreshStatus();
  }
  private void refreshStatus(){
    if(diagnostics!=null)
      diagnostics.setText(AlphaTerrCaptureService.status
        +"\nAccessibility="+AlphaTerrTouchService.connected()
        +"  Single-player="+ATSettings.confirmed(this)
        +"  Armed="+ATSettings.armed(this));
  }
  private final Runnable updateStatus=new Runnable(){
    @Override public void run(){refreshStatus();handler.postDelayed(this,900);}
  };
  private void startCapture(){
    try{
      MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      startActivityForResult(mgr.createScreenCaptureIntent(),REQUEST_CAPTURE);
    }catch(RuntimeException ex){Toast.makeText(this,"Cannot capture: "+ex.getMessage(),
      Toast.LENGTH_LONG).show();}
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==REQUEST_CAPTURE&&result==RESULT_OK&&data!=null){
      Intent i=new Intent(this,AlphaTerrCaptureService.class)
        .setAction(AlphaTerrCaptureService.START)
        .putExtra(AlphaTerrCaptureService.EXTRA_CODE,result)
        .putExtra(AlphaTerrCaptureService.EXTRA_DATA,data);
      startForegroundService(i);
    }
  }
  private void stopAll(){
    ATSettings.armed(this,false);
    AlphaTerrTouchService.update(AlphaTerrBrain.Advice.hold("STOPPED"));
    startService(new Intent(this,AlphaTerrCaptureService.class)
      .setAction(AlphaTerrCaptureService.STOP));
    refreshStatus();
  }
  @Override protected void onResume(){
    super.onResume();handler.removeCallbacks(updateStatus);handler.post(updateStatus);
  }
  @Override protected void onPause(){
    handler.removeCallbacks(updateStatus);super.onPause();
  }
}
