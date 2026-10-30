package io.github.gaong.beck;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AlphaTerrVisionTest {
  @Test public void parseTroopSuffixes(){
    assertEquals(5800,AlphaTerrVision.units("5.8k"));
    assertEquals(12340,AlphaTerrVision.units("12.34K"));
    assertEquals(1000000,AlphaTerrVision.units("1M"));
    assertEquals(900,AlphaTerrVision.units("900"));
    assertEquals(-1,AlphaTerrVision.units("ABC"));
    assertEquals(-1,AlphaTerrVision.units(""));
  }
  @Test public void colorDistanceSymmetry(){
    assertEquals(0,AlphaTerrVision.distance(0xff112233,0xff112233));
    assertEquals(AlphaTerrVision.distance(0xff113355,0xff99aabb),
      AlphaTerrVision.distance(0xff99aabb,0xff113355));
  }
}
