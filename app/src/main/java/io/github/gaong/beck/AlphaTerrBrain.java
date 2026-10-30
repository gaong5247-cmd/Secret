package io.github.gaong.beck;

/** One-ply risk-aware real-screen adviser. Deep beam/NNUE/CNN train off-device. */
public final class AlphaTerrBrain {
  static final class Advice {
    final float x,y,percent;
    final String explanation;
    final boolean safe;
    Advice(float a,float b,float p,String info,boolean ok){
      x=a;y=b;percent=p;explanation=info;safe=ok;
    }
    static Advice hold(String why){return new Advice(0,0,0,why,false);}
  }
  private double[] weights={1.8,.4,-2.6,-.8};
  private double previousLand=-1;
  private long lastRewardAt=0;
  private double lastValue=0;
  /** Automatic reward signal derived from measured territorial delta, not labels. */
  void feedback(AlphaTerrVision.Snapshot seen,long now){
    if(seen.confidence<.7||seen.troops<0)return;
    double land=seen.area,army=seen.troops;
    if(previousLand>0 && now-lastRewardAt>800){
      double reward=(land-previousLand)/Math.max(1,previousLand)
                    +.1*Math.tanh((army-lastValue)/Math.max(100,army));
      // Local bounded TD reward shaping; this is NOT neural-network training.
      weights[0]=Math.max(1.,Math.min(3.,weights[0]+.02*reward));
      weights[2]=Math.max(-5.,Math.min(-1.,weights[2]-.005*reward));
    }
    previousLand=land;lastValue=army;lastRewardAt=now;
  }
  Advice choose(AlphaTerrVision.Snapshot seen){
    if(seen.confidence<.7||seen.area<30)return Advice.hold("OWN TERRITORY UNCERTAIN");
    if(seen.troops<200)return Advice.hold("OCR: OWN BALANCE UNKNOWN");
    AlphaTerrVision.Region selected=null;
    double best=0;
    float fraction=0;
    for(AlphaTerrVision.Region e:seen.targets){
      if(e.water||e.area<20||e.border<2)continue;
      if(e.neutral){ // conservative early land capture if economy is stable
        if(seen.troops<1500)continue;
        double score=e.area*.6+e.border*.6;
        if(score>best){best=score;selected=e;fraction=.10f;}
      }else{
        if(e.troops<0||e.troops<50||seen.troops<=3.0*e.troops)continue;
        double needed=2.4*e.troops/(double)seen.troops;
        float pct=0;
        for(float p:new float[]{.06f,.10f,.18f,.30f,.45f})
          if(p>=needed){pct=p;break;}
        if(pct==0||seen.troops*(1-pct)<1.6*e.troops)continue;
        double attackScore=weights[0]*Math.sqrt(e.area)
          +weights[1]*e.border+weights[2]*(e.troops/(double)seen.troops)*20
          +weights[3]*pct*12;
        if(attackScore>best){best=attackScore;selected=e;fraction=pct;}
      }
    }
    if(selected==null)return Advice.hold("WAIT / INSUFFICIENT SAFE TARGETS");
    return new Advice(selected.x,selected.y,fraction,
       selected.neutral?"EXPAND NEUTRAL":"ATTACK WEAK BORDER",true);
  }
}
