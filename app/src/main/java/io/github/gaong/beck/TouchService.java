package io.github.gaong.beck;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Continuous multi-stroke input on an explicitly authorized test session only. */
public final class TouchService extends AccessibilityService {
  private static final String ROBLOX="com.roblox.client";
  private static final long DURATION=115;
  private static volatile TouchService instance;
  private static volatile SoccerBrain.Decision latest=SoccerBrain.Decision.idle();
  private static volatile String detail="Accessibility service disconnected";
  private final Handler handler=new Handler(Looper.getMainLooper());
  private GestureDescription.StrokeDescription stickStroke,cameraStroke;
  private float stickX,stickY,cameraX,cameraY;
  private volatile boolean busy=false;
  private String lastPackage="";
  private long lastEvent=0,lastButton=0;

  public static boolean connected(){return instance!=null;}
  public static String detail(){return detail;}
  public static void update(SoccerBrain.Decision value){
    latest=value!=null?value:SoccerBrain.Decision.idle();
  }
  public static boolean robloxVisible() {
    TouchService t=instance;return t!=null&&t.robloxForeground();
  }
  private boolean robloxForeground(){
    try {
      AccessibilityNodeInfo root=getRootInActiveWindow();
      if(root!=null){
        CharSequence pkg=root.getPackageName();root.recycle();
        if(pkg!=null)return ROBLOX.contentEquals(pkg);
      }
    }catch(RuntimeException ignored){}
    return ROBLOX.equals(lastPackage) && SystemClock.uptimeMillis()-lastEvent<2500;
  }
  @Override public void onAccessibilityEvent(AccessibilityEvent event) {
    if(event!=null && event.getPackageName()!=null){
      lastPackage=event.getPackageName().toString();
      lastEvent=SystemClock.uptimeMillis();
    }
  }
  @Override public void onInterrupt(){clear();detail="Gesture interrupted";}
  @Override protected void onServiceConnected(){
    super.onServiceConnected();instance=this;handler.post(tick);
    detail="Connected; auto control OFF by default";
  }
  @Override public boolean onUnbind(android.content.Intent intent){
    handler.removeCallbacks(tick);clear();instance=null;
    detail="Touch service stopped";return super.onUnbind(intent);
  }
  private static float clamp(float a,float lo,float hi){return Math.max(lo,Math.min(hi,a));}
  private Path path(float x,float y,float xx,float yy){
    Path p=new Path();p.moveTo(x,y);p.lineTo(xx,yy);return p;
  }
  private float x(Calibration.Control key,int w){return Calibration.x(this,key)*w;}
  private float y(Calibration.Control key,int h){return Calibration.y(this,key)*h;}
  private void clear(){stickStroke=cameraStroke=null;busy=false;}
  private final Runnable tick=new Runnable(){
    @Override public void run(){
      if(instance!=TouchService.this)return;
      if(busy){handler.postDelayed(this,30);return;}
      boolean allowed=Calibration.auto(TouchService.this)&&robloxForeground();
      SoccerBrain.Decision d=latest;
      if(!allowed && stickStroke==null && cameraStroke==null){
        detail=Calibration.auto(TouchService.this)?"Waiting for Roblox window":"Auto control OFF";
        handler.postDelayed(this,150);return;
      }
      if(!d.active && stickStroke==null && cameraStroke==null){
        detail="No fresh ball track; stopped";handler.postDelayed(this,90);return;
      }
      dispatch(d,allowed);
    }
  };
  private static final class Next {
    GestureDescription.StrokeDescription stick,cam;
    float sx,sy,cx,cy;
  }
  private void dispatch(SoccerBrain.Decision d,boolean allowed){
    int w=getResources().getDisplayMetrics().widthPixels,
        h=getResources().getDisplayMetrics().heightPixels;
    if(w<100||h<100){detail="No valid display dimensions";handler.postDelayed(tick,200);return;}
    final Next next=new Next();
    GestureDescription.Builder builder=new GestureDescription.Builder();
    if(allowed&&d.active){
      float joyX=x(Calibration.Control.JOYSTICK,w),joyY=y(Calibration.Control.JOYSTICK,h);
      float radius=Math.min(w,h)*.078f;
      double len=Math.max(1,Math.hypot(d.moveX,d.moveY));
      next.sx=clamp(joyX+(float)(d.moveX/len)*radius,2,w-2);
      next.sy=clamp(joyY-(float)(d.moveY/len)*radius,2,h-2);
      Path jp=stickStroke==null?path(joyX,joyY,next.sx,next.sy):
                                   path(stickX,stickY,next.sx,next.sy);
      next.stick=stickStroke==null
        ?new GestureDescription.StrokeDescription(jp,0,DURATION,true)
        :stickStroke.continueStroke(jp,0,DURATION,true);
      builder.addStroke(next.stick);
      float originX=x(Calibration.Control.CAMERA,w),originY=y(Calibration.Control.CAMERA,h);
      float fromX=cameraStroke==null?originX:cameraX;
      float fromY=cameraStroke==null?originY:cameraY;
      float toX=fromX+(float)(d.lookX*w*.45);
      float toY=fromY+(float)(d.lookY*h*.45);
      if(toX<w*.38||toX>w*.84||toY<h*.20||toY>h*.82){
        if(cameraStroke!=null)
          builder.addStroke(cameraStroke.continueStroke(path(fromX,fromY,fromX,fromY),0,DURATION,false));
      } else {
        next.cx=clamp(toX,2,w-2);next.cy=clamp(toY,2,h-2);
        Path cp=cameraStroke==null?path(originX,originY,next.cx,next.cy):
                                    path(cameraX,cameraY,next.cx,next.cy);
        next.cam=cameraStroke==null
          ?new GestureDescription.StrokeDescription(cp,0,DURATION,true)
          :cameraStroke.continueStroke(cp,0,DURATION,true);
        builder.addStroke(next.cam);
      }
      // Buttons are brief taps, with a cooldown to avoid accidental duplicate taps.
      long now=SystemClock.uptimeMillis();
      Calibration.Control action=d.tackle?Calibration.Control.TACKLE:
          d.pass?Calibration.Control.PASS:
          d.shoot?Calibration.Control.SHOOT:
          d.dribble?Calibration.Control.DRIBBLE:null;
      if(action!=null && now-lastButton>400){
        float ax=x(action,w),ay=y(action,h);
        builder.addStroke(new GestureDescription.StrokeDescription(path(ax,ay,ax,ay),0,65,false));
        lastButton=now;
      }
    } else {
      if(stickStroke!=null) builder.addStroke(stickStroke.continueStroke(
        path(stickX,stickY,stickX,stickY),0,DURATION,false));
      if(cameraStroke!=null) builder.addStroke(cameraStroke.continueStroke(
        path(cameraX,cameraY,cameraX,cameraY),0,DURATION,false));
    }
    GestureDescription gesture;
    try{gesture=builder.build();}
    catch(RuntimeException ex){clear();detail="Gesture build failed: "+ex.getClass().getSimpleName();
      handler.postDelayed(tick,150);return;}
    busy=true;
    boolean accepted;
    try{
      accepted=dispatchGesture(gesture,new GestureResultCallback(){
        @Override public void onCompleted(GestureDescription g){
          busy=false;stickStroke=next.stick;cameraStroke=next.cam;
          stickX=next.sx;stickY=next.sy;cameraX=next.cx;cameraY=next.cy;
          detail="Touch: "+(next.stick!=null?"move ":"")+(next.cam!=null?"look":"");
          handler.postDelayed(tick,8);
        }
        @Override public void onCancelled(GestureDescription g){
          clear();detail="Gesture cancelled";handler.postDelayed(tick,150);
        }
      },handler);
    }catch(RuntimeException ex){accepted=false;detail="Touch exception: "+ex.getClass().getSimpleName();}
    if(!accepted){clear();handler.postDelayed(tick,200);}
  }
}
