package io.github.gaong.beck;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Explicitly armed, screen-only, Android-native taps in the official app. */
public final class AlphaTerrTouchService extends AccessibilityService {
  private static volatile AlphaTerrTouchService instance;
  private static volatile AlphaTerrBrain.Advice latest=AlphaTerrBrain.Advice.hold("STOPPED");
  private static volatile long freshAt=0;
  private final Handler main=new Handler(Looper.getMainLooper());
  private long lastTouch=0,lastEvent=0;
  private String lastPackage="";
  private boolean busy=false;

  static boolean connected(){return instance!=null;}
  static void update(AlphaTerrBrain.Advice d){
    latest=d;freshAt=SystemClock.uptimeMillis();
  }
  private boolean gameVisible(){
    try{
      AccessibilityNodeInfo info=getRootInActiveWindow();
      if(info!=null){
        CharSequence pkg=info.getPackageName();info.recycle();
        if(pkg!=null)return "territorial.io".contentEquals(pkg);
      }
    }catch(RuntimeException ignored){}
    return "territorial.io".equals(lastPackage)
      &&SystemClock.uptimeMillis()-lastEvent<1500;
  }
  @Override protected void onServiceConnected(){
    super.onServiceConnected();instance=this;
    ATSettings.armed(this,false);main.post(loop);
  }
  @Override public void onAccessibilityEvent(AccessibilityEvent event){
    if(event!=null && event.getPackageName()!=null){
      lastPackage=event.getPackageName().toString();lastEvent=SystemClock.uptimeMillis();
    }
  }
  @Override public void onInterrupt(){busy=false;ATSettings.armed(this,false);}
  @Override public boolean onUnbind(android.content.Intent intent){
    main.removeCallbacks(loop);ATSettings.armed(this,false);instance=null;
    return super.onUnbind(intent);
  }
  private static Path at(float x,float y){Path p=new Path();p.moveTo(x,y);p.lineTo(x,y);return p;}
  private final Runnable loop=new Runnable(){
    @Override public void run(){
      if(instance!=AlphaTerrTouchService.this)return;
      long now=SystemClock.uptimeMillis();
      if(!busy && ATSettings.armed(AlphaTerrTouchService.this)
        && ATSettings.ready(AlphaTerrTouchService.this)
        && AlphaTerrCaptureService.running()
        && gameVisible() && now-freshAt<1100 && now-lastTouch>3000
        && latest!=null && latest.safe){
        perform(latest);
      }
      main.postDelayed(this,180);
    }
  };
  private void perform(AlphaTerrBrain.Advice decision){
    int w=getResources().getDisplayMetrics().widthPixels;
    int h=getResources().getDisplayMetrics().heightPixels;
    if(w<100||h<100)return;
    float lx=ATSettings.x(this,"slideL"),rx=ATSettings.x(this,"slideR");
    float ly=ATSettings.y(this,"slideL"),ry=ATSettings.y(this,"slideR");
    if(lx<0||rx<=lx||ly<0||ry<0)return;
    float pct=Math.max(0,Math.min(1,decision.percent));
    float x=(lx+(rx-lx)*pct)*w;
    float y=(ly+(ry-ly)*pct)*h;
    GestureDescription.Builder builder=new GestureDescription.Builder();
    builder.addStroke(new GestureDescription.StrokeDescription(at(x,y),0,80));
    builder.addStroke(new GestureDescription.StrokeDescription(
      at(decision.x*w,decision.y*h),240,85));
    if(!ATSettings.direct(this)){
      float bx=ATSettings.x(this,"sword"),by=ATSettings.y(this,"sword");
      if(bx<0||by<0)return;
      builder.addStroke(new GestureDescription.StrokeDescription(at(bx*w,by*h),510,90));
    }
    busy=true;lastTouch=SystemClock.uptimeMillis();
    try{
      if(!dispatchGesture(builder.build(),new GestureResultCallback(){
        @Override public void onCompleted(GestureDescription g){busy=false;}
        @Override public void onCancelled(GestureDescription g){busy=false;ATSettings.armed(AlphaTerrTouchService.this,false);}
      },main)){busy=false;ATSettings.armed(this,false);}
    }catch(RuntimeException error){busy=false;ATSettings.armed(this,false);}
  }
}
