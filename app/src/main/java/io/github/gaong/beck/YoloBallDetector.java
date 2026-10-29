package io.github.gaong.beck;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.Image;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

/** Offline COCO YOLOv8n TFLite "sports ball" (category index 32). */
public final class YoloBallDetector implements AutoCloseable {
  public static final class Ball {
    public final boolean found;
    public final double x,y,confidence;
    public final long inferenceMs;
    Ball(boolean found,double x,double y,double confidence,long inferenceMs){
      this.found=found;this.x=x;this.y=y;this.confidence=confidence;this.inferenceMs=inferenceMs;
    }
    public static Ball missing(long ms){return new Ball(false,0,0,0,ms);}
  }
  private final Interpreter interpreter;
  private final ByteBuffer input, output;
  private final int side, candidateCount, channelCount;
  private double previousX=.5,previousY=.5;
  public YoloBallDetector(AssetManager assets) throws Exception {
    try(AssetFileDescriptor fd=assets.openFd("yolov8n.tflite");
        FileInputStream stream=new FileInputStream(fd.getFileDescriptor())){
      MappedByteBuffer model=stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
          fd.getStartOffset(), fd.getDeclaredLength());
      Interpreter.Options options=new Interpreter.Options();
      options.setNumThreads(3);
      interpreter=new Interpreter(model,options);
    }
    int[] shape=interpreter.getInputTensor(0).shape();
    int[] out=interpreter.getOutputTensor(0).shape();
    if(shape.length!=4 || shape[0]!=1 || shape[1]!=shape[2] || shape[3]!=3
      || out.length!=3 || out[0]!=1 || out[1]<84 || out[2]<1
      || interpreter.getInputTensor(0).dataType()!=org.tensorflow.lite.DataType.FLOAT32
      || interpreter.getOutputTensor(0).dataType()!=org.tensorflow.lite.DataType.FLOAT32)
      throw new IllegalStateException("Unsupported YOLO TFLite tensor layout");
    side=shape[1];channelCount=out[1];candidateCount=out[2];
    input=ByteBuffer.allocateDirect(side*side*3*4).order(ByteOrder.nativeOrder());
    output=ByteBuffer.allocateDirect(channelCount*candidateCount*4).order(ByteOrder.nativeOrder());
  }
  public synchronized Ball find(Image image) {
    long t0=android.os.SystemClock.elapsedRealtime();
    if(image==null || image.getPlanes().length<1)return Ball.missing(0);
    Image.Plane plane=image.getPlanes()[0];
    ByteBuffer pixels=plane.getBuffer();
    int w=image.getWidth(),h=image.getHeight(), stride=plane.getRowStride(),
        step=plane.getPixelStride();
    if(w<32||h<32||step<3)return Ball.missing(0);
    // Letterbox RGB input using nearest-neighbor samples and 114 padding.
    double scale=Math.min(side/(double)w,side/(double)h);
    double usedW=w*scale,usedH=h*scale;
    double padX=(side-usedW)/2.0,padY=(side-usedH)/2.0;
    input.rewind();
    for(int y=0;y<side;y++){
      double fy=(y+.5-padY)/scale;
      int sy=(int)fy;
      boolean validY=fy>=0 && sy<h;
      for(int x=0;x<side;x++){
        double fx=(x+.5-padX)/scale;
        int sx=(int)fx;
        boolean okay=validY && fx>=0 && sx<w;
        int r=114,g=114,b=114;
        if(okay){
          int ix=sy*stride+sx*step;
          if(ix+2<pixels.limit()){
            r=pixels.get(ix)&255;g=pixels.get(ix+1)&255;b=pixels.get(ix+2)&255;
          }
        }
        input.putFloat(r/255.0f);input.putFloat(g/255.0f);input.putFloat(b/255.0f);
      }
    }
    input.rewind();output.rewind();
    interpreter.run(input,output);
    double best=-1, bx=0,by=0;
    int n=candidateCount;
    final int ballChannel=4+32;
    // Most of the 8400 candidates are not footballs; inspect ball score only.
    for(int j=0;j<n;j++){
      double conf=output.getFloat((ballChannel*n+j)*4);
      if(conf<.22||!Double.isFinite(conf))continue;
      double cx=output.getFloat(j*4),cy=output.getFloat((n+j)*4);
      double bw=output.getFloat((2*n+j)*4),bh=output.getFloat((3*n+j)*4);
      // Some exports use [0,1], others model pixel coordinates.
      if(Math.max(Math.abs(cx),Math.abs(cy))<=2.0){cx*=side;cy*=side;bw*=side;bh*=side;}
      double x=(cx-padX)/usedW,y=(cy-padY)/usedH;
      if(x<0||x>1||y<0||y>1||bw<1||bh<1)continue;
      // Reject very large "sports balls" and favor same tracked object on ties.
      if(bw/side>.25||bh/side>.25)continue;
      double continuity=1.0-.15*Math.min(1,Math.hypot(x-previousX,y-previousY)*2);
      double score=conf*continuity;
      if(score>best){best=score;bx=x;by=y;}
    }
    long ms=android.os.SystemClock.elapsedRealtime()-t0;
    if(best<0)return Ball.missing(ms);
    previousX=bx;previousY=by;
    return new Ball(true,bx,by,Math.min(1,best),ms);
  }
  @Override public synchronized void close(){interpreter.close();}
}
