package io.github.gaong.beck;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SoccerBrainTest {
  @Test public void missingBallCannotAct() {
    SoccerBrain b=new SoccerBrain();
    assertFalse(b.decide(new BallKalman.Estimate(false,.5,.5,0,0,0),false,1,100).active);
  }
  @Test public void buildsUpOnlyWhenPossessionManuallySet() {
    SoccerBrain b=new SoccerBrain();
    BallKalman.Estimate e=new BallKalman.Estimate(true,.8,.5,0,0,10);
    assertEquals(SoccerBrain.Tactic.BLOCK_LANE,b.decide(e,false,.5,1000).tactic);
    SoccerBrain.Decision d=b.decide(e,true,.5,3000);
    assertEquals(SoccerBrain.Tactic.BUILD_UP,d.tactic);
    assertFalse(d.tackle);
  }
  @Test public void willNotBlindTackleWithLowAggression() {
    SoccerBrain b=new SoccerBrain();
    SoccerBrain.Decision d=b.decide(new BallKalman.Estimate(true,.51,.85,0,0,10),
      false,.25,3000);
    assertEquals(SoccerBrain.Tactic.INTERCEPT,d.tactic);assertFalse(d.tackle);
  }
}
