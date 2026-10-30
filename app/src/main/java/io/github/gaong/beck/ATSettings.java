package io.github.gaong.beck;

import android.content.Context;
import android.content.SharedPreferences;

/** No background auto-start, credentials, raw frames or gameplay packets stored. */
final class ATSettings {
  private static final String NAME="alphaterr_v1";
  static SharedPreferences p(Context c){return c.getSharedPreferences(NAME,Context.MODE_PRIVATE);}
  static void armed(Context c,boolean yes){p(c).edit().putBoolean("armed",yes).apply();}
  static boolean armed(Context c){return p(c).getBoolean("armed",false);}
  static boolean confirmed(Context c){return p(c).getBoolean("singleplayer",false);}
  static void confirmed(Context c,boolean yes){p(c).edit().putBoolean("singleplayer",yes).apply();}
  static boolean hasOwn(Context c){return p(c).contains("color") && p(c).contains("ownx");}
  static int color(Context c){return p(c).getInt("color",0);}
  static float ownX(Context c){return p(c).getFloat("ownx",-1);}
  static float ownY(Context c){return p(c).getFloat("owny",-1);}
  static void own(Context c,float x,float y,int color){
    p(c).edit().putFloat("ownx",x).putFloat("owny",y).putInt("color",color).apply();
  }
  static void point(Context c,String key,float x,float y){
    p(c).edit().putFloat(key+"x",x).putFloat(key+"y",y).apply();
  }
  static boolean hasPoint(Context c,String key){return p(c).contains(key+"x")&&p(c).contains(key+"y");}
  static float x(Context c,String key){return p(c).getFloat(key+"x",-1);}
  static float y(Context c,String key){return p(c).getFloat(key+"y",-1);}
  static boolean direct(Context c){return p(c).getBoolean("direct",false);}
  static void direct(Context c,boolean yes){p(c).edit().putBoolean("direct",yes).apply();}
  static boolean ready(Context c){
    return hasOwn(c)&&confirmed(c)&&hasPoint(c,"slideL")&&hasPoint(c,"slideR")
        &&(direct(c)||hasPoint(c,"sword"));
  }
}
