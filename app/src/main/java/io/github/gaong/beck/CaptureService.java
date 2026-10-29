package io.github.gaong.beck;

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
import java.nio.ByteBuffer;
import java.util.Locale;

/** Foreground, user-consented screen capture; no screenshots are written to disk. */
public final class CaptureService extends Service {
  public static final String START="beck.START",STOP="beck.STOP",
      EXTRA_CODE="code",EXTRA_DATA="data";
  private static final String CHANNEL="beck_capture";
  private static final int NOTIFY_ID=22;
  private static volatile CaptureService active;
  public static volatile String stats="Not capturing";
  public static volatile Bitmap preview=null;
  private volatile boolean running=false;
  private HandlerThread worker;
  private Handler handler;
  private ImageReader reader;
  private VirtualDisplay display;
  private MediaProjection projection;
  private MediaProjection.Callback projectionCallback;
  private YoloBallDetector yolo;
  private final BallKalman kalman=new BallKalman();
  private final SoccerBrain brain=new SoccerBrain();
  private long lastInfer=0;
  private int frames=0;
  private int width,height,density;

  public static boolean running(){CaptureService s=active;return s!=null&&s.running;}
  public static String info(){
    return stats+"\n"+TouchService.detail();
  }
  @Override public void onCreate(){
    super.onCreate();active=this;
    NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(new NotificationChannel(
      CHANNEL,"Beckenbauer screen analysis",NotificationManager.IMPORTANCE_LOW));
  }
  private PendingIntent stopAction(){
    Intent i=new Intent(this,CaptureService.class).setAction(STOP);
    return PendingIntent.getService(this,30,i,
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  }
  private Notification notification(){
    Intent open=new Intent(this,MainActivity.class);
    PendingIntent pi=PendingIntent.getActivity(this,31,open,
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this,CHANNEL)
      .setSmallIcon(android.R.drawable.ic_menu_camera)
      .setContentTitle("Beckenbauer AI — vision lab")
      .setContentText("On-device ball analysis. Tap to open; auto input is opt-in.")
      .setContentIntent(pi).setOngoing(true)
      .addAction(new Notification.Action.Builder(
        android.R.drawable.ic_menu_close_clear_cancel,"STOP",stopAction()).build())
      .build();
  }
  @Override public int onStartCommand(Intent intent,int flags,int id){
    if(intent==null)return START_NOT_STICKY;
    if(STOP.equals(intent.getAction())){Calibration.auto(this,false);stopSelf();return START_NOT_STICKY;}
    if(!START.equals(intent.getAction())||running)return START_NOT_STICKY;
    Intent token;
    if(Build.VERSION.SDK_INT>=33)token=intent.getParcelableExtra(EXTRA_DATA,Intent.class);
    else token=intent.getParcelableExtra(EXTRA_DATA);
    int code=intent.getIntExtra(EXTRA_CODE,0);
    if(token==null || code==0){stats="Screen capture permission missing";stopSelf();return START_NOT_STICKY;}
    try{
      startForeground(NOTIFY_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
      MediaProjectionManager pm=(MediaProjectionManager)getSystemService(
        Context.MEDIA_PROJECTION_SERVICE);
      projection=pm.getMediaProjection(code,token);
      if(projection==null)throw new IllegalStateException("MediaProjection unavailable");
      worker=new HandlerThread("beck-yolo-worker");worker.start();
      handler=new Handler(worker.getLooper());
      DisplayMetrics dm=new DisplayMetrics();
      ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
      width=dm.widthPixels;height=dm.heightPixels;density=dm.densityDpi;
      projectionCallback=new MediaProjection.Callback(){
        @Override public void onStop(){stats="Projection permission revoked";stopSelf();}
        @Override public void onCapturedContentResize(int w,int h){
          if(Build.VERSION.SDK_INT>=34 && handler!=null && w>100 && h>100)
            handler.post(()->resize(w,h));
        }
      };
      projection.registerCallback(projectionCallback,handler);
      running=true;
      initReader(width,height);
      display=projection.createVirtualDisplay("Beckenbauer capture",width,height,density,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
      handler.post(()->{
        try{yolo=new YoloBallDetector(getAssets());stats="YOLO ready; observing screen";}
        catch(Exception err){stats="YOLO init failed: "+err.getClass().getSimpleName()+": "+err.getMessage();
          TouchService.update(SoccerBrain.Decision.idle());}
      });
    }catch(Exception err){stats="Capture failed: "+err.getClass().getSimpleName()+": "+err.getMessage();stopSelf();}
    return START_NOT_STICKY;
  }
  private void initReader(int w,int h){
    reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);
    reader.setOnImageAvailableListener(ir->{
      Image img=null;
      try{
        img=ir.acquireLatestImage();
        if(img==null || !running || yolo==null)return;
        long now=SystemClock.uptimeMillis();
        if(now-lastInfer<65)return; // inference cannot run at 1000 FPS on a phone
        lastInfer=now;
        YoloBallDetector.Ball ball=yolo.find(img);
        long observationTime=SystemClock.uptimeMillis();
        if(ball.found)kalman.observe(ball.x,ball.y,ball.confidence,observationTime);
        BallKalman.Estimate prediction=kalman.estimate(observationTime+35);
        SoccerBrain.Decision decision=brain.decide(prediction,
          Calibration.possession(this),Calibration.aggression(this),observationTime);
        TouchService.update(decision);
        frames++;
        if(frames%5==0 && TouchService.robloxVisible())preview=snapshot(img);
        stats=String.format(Locale.US,
          "Model: YOLOv8n sports-ball | detection: %s (%.2f) | x %.3f y %.3f\n"+
          "Kalman: %s, age %dms | tactic: %s | inference %dms | frames %d\n"+
          "Input: %s | Project UAI bridge: NOT CONNECTED",
          ball.found?"YES":"NO",ball.confidence,prediction.x,prediction.y,
          prediction.valid?"TRACKING":"NO FRESH TRACK",prediction.ageMs,
          decision.tactic,ball.inferenceMs,frames,
          Calibration.auto(this)?"ENABLED (authorized tests only)":"OFF");
      }catch(RuntimeException err){
        stats="Inference error: "+err.getClass().getSimpleName()+": "+err.getMessage();
        TouchService.update(SoccerBrain.Decision.idle());
      }finally{if(img!=null)img.close();}
    },handler);
  }
  private Bitmap snapshot(Image img){
    final int w=256,h=144;
    try{
      Image.Plane p=img.getPlanes()[0];
      ByteBuffer src=p.getBuffer();
      int row=p.getRowStride(),step=p.getPixelStride(),max=src.limit();
      int[] pixels=new int[w*h];
      for(int y=0;y<h;y++){
        int sy=(int)((y+.5)*img.getHeight()/h);
        for(int x=0;x<w;x++){
          int sx=(int)((x+.5)*img.getWidth()/w);
          int index=sy*row+sx*step;
          int r=0,g=0,b=0;
          if(index+2<max){r=src.get(index)&255;g=src.get(index+1)&255;b=src.get(index+2)&255;}
          pixels[y*w+x]=0xff000000 | r<<16 | g<<8 | b;
        }
      }
      return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }catch(RuntimeException ex){return null;}
  }
  private void resize(int w,int h){
    if(!running || display==null || (w==width && h==height))return;
    ImageReader old=reader;
    try{
      width=w;height=h;initReader(w,h);
      display.resize(w,h,density);display.setSurface(reader.getSurface());
      if(old!=null)old.close();
    }catch(RuntimeException err){
      stats="Capture resize failed: "+err.getMessage();
      if(old!=null && old!=reader)old.close();
    }
  }
  @Override public void onDestroy(){
    running=false;Calibration.auto(this,false);TouchService.update(SoccerBrain.Decision.idle());
    if(reader!=null){reader.setOnImageAvailableListener(null,null);reader.close();reader=null;}
    if(display!=null){display.release();display=null;}
    if(projection!=null){
      if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);
      projection.stop();projection=null;
    }
    if(yolo!=null){yolo.close();yolo=null;}
    if(worker!=null){worker.quitSafely();worker=null;}
    active=null;stats="Capture stopped";
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent){return null;}
}
