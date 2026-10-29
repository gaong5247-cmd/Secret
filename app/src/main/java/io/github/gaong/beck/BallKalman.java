package io.github.gaong.beck;

/** 2-D constant-velocity Kalman filter in normalized screen coordinates (NOT world coordinates). */
public final class BallKalman {
  public static final class Estimate {
    public final boolean valid;
    public final double x, y, vx, vy;
    public final long ageMs;
    Estimate(boolean valid, double x, double y, double vx, double vy, long ageMs) {
      this.valid=valid; this.x=x; this.y=y; this.vx=vx; this.vy=vy; this.ageMs=ageMs;
    }
  }
  private final double[] state=new double[4]; // x, y, pixels normalized per second
  private final double[][] covariance=new double[4][4];
  private long lastTime=-1, lastMeasurement=-1;
  private int measurements=0;

  public static double clamp(double v,double lo,double hi) { return Math.min(hi,Math.max(lo,v)); }

  public synchronized void reset() {
    for(int i=0;i<4;i++){ state[i]=0; for(int j=0;j<4;j++)covariance[i][j]=0; }
    lastTime=lastMeasurement=-1; measurements=0;
  }
  private void predictTo(long time) {
    if(lastTime<0) return;
    double dt=clamp((time-lastTime)/1000.0,0,0.5);
    if(dt<=0) return;
    double[][] f={{1,0,dt,0},{0,1,0,dt},{0,0,1,0},{0,0,0,1}};
    double[] nx=new double[4];
    for(int i=0;i<4;i++)for(int j=0;j<4;j++)nx[i]+=f[i][j]*state[j];
    System.arraycopy(nx,0,state,0,4);
    double[][] tmp=new double[4][4], np=new double[4][4];
    for(int i=0;i<4;i++)for(int j=0;j<4;j++)for(int k=0;k<4;k++)tmp[i][j]+=f[i][k]*covariance[k][j];
    for(int i=0;i<4;i++)for(int j=0;j<4;j++)for(int k=0;k<4;k++)np[i][j]+=tmp[i][k]*f[j][k];
    double q=0.003*dt;
    for(int i=0;i<4;i++)for(int j=0;j<4;j++)covariance[i][j]=np[i][j]+(i==j?(i<2?q:q*3):0);
    lastTime=time;
  }
  public synchronized void observe(double x,double y,double confidence,long time) {
    if(!Double.isFinite(x)||!Double.isFinite(y)||confidence<0.10) return;
    if(lastTime<0 || time-lastMeasurement>800) {
      reset();
      state[0]=clamp(x,0,1);state[1]=clamp(y,0,1);
      for(int i=0;i<4;i++)covariance[i][i]=i<2?.04:.4;
      lastMeasurement=lastTime=time; measurements=1;return;
    }
    predictTo(time);
    double r=clamp(0.0035/Math.max(0.2,confidence),0.002,0.03);
    double s00=covariance[0][0]+r,s01=covariance[0][1],
           s10=covariance[1][0],s11=covariance[1][1]+r;
    double determinant=s00*s11-s01*s10;
    if(determinant<=1e-12) return;
    double k[][]=new double[4][2];
    for(int i=0;i<4;i++){
      k[i][0]=(covariance[i][0]*s11-covariance[i][1]*s10)/determinant;
      k[i][1]=(covariance[i][1]*s00-covariance[i][0]*s01)/determinant;
    }
    double ex=clamp(x,0,1)-state[0], ey=clamp(y,0,1)-state[1];
    // Reject large teleports rather than steering toward a likely false detection.
    if(Math.hypot(ex,ey)>0.38){reset(); observe(x,y,confidence,time);return;}
    for(int i=0;i<4;i++)state[i]+=k[i][0]*ex+k[i][1]*ey;
    double[][] old=new double[4][4];
    for(int i=0;i<4;i++)System.arraycopy(covariance[i],0,old[i],0,4);
    for(int i=0;i<4;i++)for(int j=0;j<4;j++)
      covariance[i][j]=old[i][j]-k[i][0]*old[0][j]-k[i][1]*old[1][j];
    state[2]=clamp(state[2],-3,3);state[3]=clamp(state[3],-3,3);
    measurements++; lastMeasurement=time;
  }
  public synchronized Estimate estimate(long at) {
    if(lastTime<0)return new Estimate(false,0.5,0.5,0,0,Long.MAX_VALUE);
    long age=Math.max(0,at-lastMeasurement);
    double dt=clamp((at-lastTime)/1000.0,0,0.5);
    return new Estimate(age<=350 && measurements>=2,
      clamp(state[0]+state[2]*dt,0,1),clamp(state[1]+state[3]*dt,0,1),
      state[2],state[3],age);
  }
}
