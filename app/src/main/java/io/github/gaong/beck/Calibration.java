package io.github.gaong.beck;
import android.content.Context;
import android.content.SharedPreferences;
public final class Calibration {
  private static final String PREF="beck_ctrl";
  public enum Control {
    JOYSTICK("Joystick",.17f,.78f), CAMERA("Camera",.70f,.48f),
    TACKLE("Tackle",.89f,.70f), PASS("Pass",.83f,.83f),
    SHOOT("Shoot",.94f,.49f), DRIBBLE("Dribble",.78f,.61f);
    public final String label; public final float defaultX,defaultY;
    Control(String label,float x,float y){this.label=label;defaultX=x;defaultY=y;}
    @Override public String toString(){return label;}
  }
  static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,0);}
  public static float x(Context c,Control b){return prefs(c).getFloat(b.name()+"x",b.defaultX);}
  public static float y(Context c,Control b){return prefs(c).getFloat(b.name()+"y",b.defaultY);}
  public static void set(Context c,Control b,float x,float y){
    prefs(c).edit().putFloat(b.name()+"x",(float)BallKalman.clamp(x,.02,.98))
      .putFloat(b.name()+"y",(float)BallKalman.clamp(y,.02,.98)).apply();
  }
  public static boolean auto(Context c){return prefs(c).getBoolean("auto",false);}
  public static void auto(Context c,boolean on){prefs(c).edit().putBoolean("auto",on).apply();}
  public static boolean possession(Context c){return prefs(c).getBoolean("possession",false);}
  public static void possession(Context c,boolean v){prefs(c).edit().putBoolean("possession",v).apply();}
  public static float aggression(Context c){return prefs(c).getFloat("aggression",.35f);}
  public static void aggression(Context c,float value){
    prefs(c).edit().putFloat("aggression",(float)BallKalman.clamp(value,0,1)).apply();
  }
}
