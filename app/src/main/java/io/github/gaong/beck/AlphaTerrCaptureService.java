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
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.Text;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Permission-granted MediaProjection; local ML Kit OCR; conservative advice. */
public final class AlphaTerrCaptureService extends Service {
  static final String START="alphaterr.START",STOP="alphaterr.STOP";
  static final String EXTRA_CODE="code", EXTRA_DATA="data";
  private static volatile AlphaTerrCaptureService active;
  static volatile Bitmap preview;
  static volatile String status="Capture is off";
  private boolean started=false,ocrBusy=false;
  private HandlerThread thread;
  private Handler worker;
  private ImageReader reader;
  private VirtualDisplay display;
  private MediaProjection projection;
  private MediaProjection.Callback callback;
  private TextRecognizer recognizer;
  private List<AlphaTerrVision.Token> tokens=Collections.emptyList();
  private long ocrAt=0,lastFrame=0,lastOcr=0;
  private final AlphaTerrBrain brain=new AlphaTerrBrain();
  private int width,height,dpi;
  static boolean running(){return active!=null&&active.started;}

  @Override public void onCreate(){
    super.onCreate();active=this;
    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
      .createNotificationChannel(new NotificationChannel("alpha_territory",
        "AlphaTerr screen capture",NotificationManager.IMPORTANCE_LOW));
  }
  private Notification notification(){
    Intent stop=new Intent(this,AlphaTerrCaptureService.class).setAction(STOP);
    PendingIntent pi=PendingIntent.getService(this,4,stop,
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this,"alpha_territory")
      .setSmallIcon(android.R.drawable.ic_menu_crop)
      .setContentTitle("AlphaTerr · local screen analysis")
      .setContentText("Offline bot test only · tap STOP to disarm")
      .addAction(new Notification.Action.Builder(
          android.R.drawable.ic_menu_close_clear_cancel,"STOP",pi).build())
      .setOngoing(true).build();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(intent==null)return START_NOT_STICKY;
    if(STOP.equals(intent.getAction())){
      ATSettings.armed(this,false);AlphaTerrTouchService.update(AlphaTerrBrain.Advice.hold("STOP"));
      stopSelf();return START_NOT_STICKY;
    }
    if(!START.equals(intent.getAction())||started)return START_NOT_STICKY;
    Intent token=Build.VERSION.SDK_INT>=33
      ? intent.getParcelableExtra(EXTRA_DATA,Intent.class)
      : intent.getParcelableExtra(EXTRA_DATA);
    int result=intent.getIntExtra(EXTRA_CODE,0);
    if(token==null||result==0){status="No projection consent";stopSelf();return START_NOT_STICKY;}
    try{
      startForeground(23,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
      MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
      projection=mgr.getMediaProjection(result,token);
      if(projection==null)throw new IllegalStateException("No projection");
      DisplayMetrics metrics=new DisplayMetrics();
      ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
      width=metrics.widthPixels;height=metrics.heightPixels;dpi=metrics.densityDpi;
      thread=new HandlerThread("AlphaTerr-screen-worker");thread.start();
      worker=new Handler(thread.getLooper());
      callback=new MediaProjection.Callback(){
        @Override public void onStop(){status="Capture permission revoked";stopSelf();}
        @Override public void onCapturedContentResize(int w,int h){
          if(Build.VERSION.SDK_INT>=34&&worker!=null&&w>100&&h>100)
            worker.post(()->resize(w,h));
        }
      };
      projection.registerCallback(callback,worker);
      recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
      started=true;
      createReader(width,height);
      display=projection.createVirtualDisplay("AlphaTerr observer",width,height,dpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,worker);
      status="OBSERVING only. Calibrate owner pixel and controls.";
    }catch(RuntimeException ex){
      status="Capture failed: "+ex.getClass().getSimpleName()+" "+ex.getMessage();
      stopSelf();
    }
    return START_NOT_STICKY;
  }
  private void createReader(int w,int h){
    reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
    reader.setOnImageAvailableListener(source->{
      Image image=null;
      try{
        image=source.acquireLatestImage();
        if(image==null||!started)return;
        long now=SystemClock.uptimeMillis();
        if(now-lastFrame<280)return;
        lastFrame=now;
        Bitmap frame=sample(image,512,288);
        if(frame==null)return;
        preview=frame;
        if(now-lastOcr>700&&!ocrBusy){
          ocrBusy=true;lastOcr=now;
          recognizer.process(InputImage.fromBitmap(frame,0))
            .addOnSuccessListener(parsed->{
              List<AlphaTerrVision.Token> found=new ArrayList<>();
              for(Text.TextBlock block:parsed.getTextBlocks()){
                for(Text.Line line:block.getLines()){
                  if(line.getBoundingBox()==null)continue;
                  android.graphics.Rect bounds=line.getBoundingBox();
                  found.add(new AlphaTerrVision.Token(line.getText(),
                    bounds.exactCenterX()/frame.getWidth(),bounds.exactCenterY()/frame.getHeight()));
                  for(Text.Element element:line.getElements()){
                    if(element.getBoundingBox()==null)continue;
                    bounds=element.getBoundingBox();
                    found.add(new AlphaTerrVision.Token(element.getText(),
                      bounds.exactCenterX()/frame.getWidth(),bounds.exactCenterY()/frame.getHeight()));
                  }
                }
              }
              tokens=found;ocrAt=SystemClock.uptimeMillis();
            }).addOnFailureListener(e->{tokens=Collections.emptyList();})
            .addOnCompleteListener(done->ocrBusy=false);
        }
        if(!ATSettings.hasOwn(this)){
          status="Frame received; tap YOUR land in AlphaTerr calibration";return;
        }
        List<AlphaTerrVision.Token> recognized=now-ocrAt<1100?tokens:Collections.emptyList();
        AlphaTerrVision.Snapshot scene=AlphaTerrVision.detect(frame,ATSettings.color(this),
          ATSettings.ownX(this),ATSettings.ownY(this),recognized);
        brain.feedback(scene,now);
        AlphaTerrBrain.Advice advice=brain.choose(scene);
        AlphaTerrTouchService.update(advice);
        status=String.format(Locale.US,
          "OWN: %d sampled pixels; own troops: %s; neighbours: %d; vision: %.2f\n" +
          "Plan: %s @ %.1f%%; OCR fresh: %s; input: %s; package: territorial.io ONLY",
          scene.area,scene.troops<0?"UNKNOWN":Integer.toString(scene.troops),
          scene.targets.size(),scene.confidence,advice.explanation,advice.percent*100,
          now-ocrAt<1100?"YES":"NO",ATSettings.armed(this)?"ARMED":"OFF");
      }catch(RuntimeException err){
        AlphaTerrTouchService.update(AlphaTerrBrain.Advice.hold("VISION ERROR"));
        status="Vision error: "+err.getClass().getSimpleName()+": "+err.getMessage();
      }finally{if(image!=null)image.close();}
    },worker);
  }
  private Bitmap sample(Image img,int w,int h){
    try{
      Image.Plane p=img.getPlanes()[0];ByteBuffer buffer=p.getBuffer();
      int stride=p.getRowStride(),pixel=p.getPixelStride(),limit=buffer.limit();
      int[] out=new int[w*h];
      for(int y=0;y<h;y++)for(int x=0;x<w;x++){
        int sx=(int)((x+.5)*img.getWidth()/w),sy=(int)((y+.5)*img.getHeight()/h);
        int pos=sy*stride+sx*pixel;
        if(pos+2<limit){
          int r=buffer.get(pos)&255,g=buffer.get(pos+1)&255,b=buffer.get(pos+2)&255;
          out[y*w+x]=0xff000000|(r<<16)|(g<<8)|b;
        }
      }
      return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888);
    }catch(RuntimeException e){return null;}
  }
  private void resize(int w,int h){
    if(!started||display==null||w==width&&h==height)return;
    ImageReader old=reader;
    width=w;height=h;createReader(w,h);
    display.resize(w,h,dpi);display.setSurface(reader.getSurface());
    if(old!=null)old.close();
  }
  @Override public void onDestroy(){
    ATSettings.armed(this,false);started=false;
    AlphaTerrTouchService.update(AlphaTerrBrain.Advice.hold("CAPTURE STOPPED"));
    if(reader!=null){reader.setOnImageAvailableListener(null,null);reader.close();reader=null;}
    if(display!=null){display.release();display=null;}
    if(projection!=null){
      if(callback!=null)projection.unregisterCallback(callback);
      projection.stop();projection=null;
    }
    if(recognizer!=null){recognizer.close();recognizer=null;}
    if(thread!=null){thread.quitSafely();thread=null;}
    active=null;status="Capture stopped";
    super.onDestroy();
  }
  @Override public IBinder onBind(Intent intent){return null;}
}
