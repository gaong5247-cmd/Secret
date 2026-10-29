package io.github.gaong.beck;
import org.junit.Test;
import static org.junit.Assert.*;
public final class BallKalmanTest {
  @Test public void needsTwoObservationsAndExpires() {
    BallKalman k=new BallKalman(); assertFalse(k.estimate(100).valid);
    k.observe(.2,.4,.9,100); assertFalse(k.estimate(100).valid);
    k.observe(.22,.41,.9,130); assertTrue(k.estimate(131).valid);
    assertFalse(k.estimate(550).valid);
  }
  @Test public void holdsWithinScreenAndResetsOnTeleport() {
    BallKalman k=new BallKalman();
    k.observe(.2,.3,.9,100); k.observe(.22,.32,.9,130);
    BallKalman.Estimate e=k.estimate(200);
    assertTrue(e.x>=0 && e.x<=1);assertTrue(e.y>=0 && e.y<=1);
    k.observe(.95,.95,.9,240);assertFalse(k.estimate(241).valid);
  }
}
