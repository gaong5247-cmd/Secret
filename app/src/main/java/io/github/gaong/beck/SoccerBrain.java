package io.github.gaong.beck;

/**
 * Deliberately conservative screen-space behavior. No 3D pose, team, goal or possession API.
 * Auto input is externally gated by explicit permission and verified foreground.
 */
public final class SoccerBrain {
  public enum Tactic { HOLD, WATCH, BLOCK_LANE, INTERCEPT, RECOVER, BUILD_UP }
  public static final class Decision {
    public final Tactic tactic;
    public final double moveX,moveY,lookX,lookY;
    public final boolean tackle,pass,shoot,dribble,active;
    Decision(Tactic t,double mx,double my,double lx,double ly,
             boolean tk,boolean pa,boolean sh,boolean dr,boolean a) {
      tactic=t; moveX=mx;moveY=my;lookX=lx;lookY=ly;
      tackle=tk;pass=pa;shoot=sh;dribble=dr;active=a;
    }
    public static Decision idle() {
      return new Decision(Tactic.HOLD,0,0,0,0,false,false,false,false,false);
    }
  }
  private long lastActionMs=0;
  public synchronized Decision decide(BallKalman.Estimate ball,boolean assumePossession,
                                      double aggression,long now) {
    if(ball==null || !ball.valid || ball.ageMs>250) return Decision.idle();
    double ax=BallKalman.clamp(aggression,0,1);
    double predictedX=BallKalman.clamp(ball.x+ball.vx*.12,0,1);
    double predictedY=BallKalman.clamp(ball.y+ball.vy*.12,0,1);
    double errorX=predictedX-.5, errorY=predictedY-.58;
    double lookX=BallKalman.clamp(errorX*.12,-.045,.045);
    double lookY=BallKalman.clamp(errorY*.09,-.030,.030);
    double mx=BallKalman.clamp(errorX*(0.85+ax),-.73,.73);
    double my=BallKalman.clamp((.60-predictedY)*.85,-.50,.50);
    if(assumePossession) {
      boolean pass=now-lastActionMs>1500 && Math.abs(errorX)>.20;
      if(pass)lastActionMs=now;
      return new Decision(Tactic.BUILD_UP,mx,.30,lookX,lookY,false,pass,false,!pass,true);
    }
    if(predictedY>.72 && Math.abs(errorX)<.18) {
      boolean tackle=now-lastActionMs>1200 && ax>.55 && ball.ageMs<130;
      if(tackle)lastActionMs=now;
      return new Decision(Tactic.INTERCEPT,mx,BallKalman.clamp(my+.23,-.35,.55),
                          lookX,lookY,tackle,false,false,false,true);
    }
    if(predictedY<.28) return new Decision(Tactic.RECOVER,mx*.4,-.2,lookX,lookY,
                                            false,false,false,false,true);
    return new Decision(Tactic.BLOCK_LANE,mx*.55,my*.4,lookX,lookY,
                        false,false,false,false,true);
  }
}
