package io.github.gaong.beck;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** RGB flood-fill ownership and border map; explicit own-color seed required. */
public final class AlphaTerrVision {
  private static final int SIZE_LIMIT=25;
  static final class Token {
    final String text;final float x,y;
    Token(String t,float xx,float yy){text=t;x=xx;y=yy;}
  }
  static final class Region {
    final float x,y;
    final int area,border,color;
    final boolean neutral,water;
    int troops=-1;
    Region(float xx,float yy,int a,int b,int c,boolean n,boolean sea){
      x=xx;y=yy;area=a;border=b;color=c;neutral=n;water=sea;
    }
  }
  static final class Snapshot {
    final int area;
    final float ownX,ownY;
    final double confidence;
    final List<Region> targets;
    int troops=-1;
    Snapshot(int pixels,float x,float y,double c,List<Region> list){
      area=pixels;ownX=x;ownY=y;confidence=c;targets=list;
    }
  }
  private static int red(int c){return (c>>16)&255;}
  private static int green(int c){return (c>>8)&255;}
  private static int blue(int c){return c&255;}
  static int distance(int a,int b){
    int r=red(a)-red(b),g=green(a)-green(b),bl=blue(a)-blue(b);
    return r*r+g*g+bl*bl;
  }
  static int units(String source){
    if(source==null)return -1;
    String s=source.toUpperCase(Locale.US).replace(" ","").replace(",","")
      .replace("O","0").replace("I","1");
    if(!s.matches("[0-9]+(\\.[0-9]+)?[KMB]?"))return -1;
    int factor=1;
    char last=s.charAt(s.length()-1);
    if(last=='K'){factor=1000;s=s.substring(0,s.length()-1);}
    else if(last=='M'){factor=1000000;s=s.substring(0,s.length()-1);}
    else if(last=='B'){factor=1000000000;s=s.substring(0,s.length()-1);}
    try{return (int)Math.min(Integer.MAX_VALUE,Double.parseDouble(s)*factor);}
    catch(NumberFormatException e){return -1;}
  }
  /** State comes solely from screenshot and on-device OCR, not a hidden API. */
  static Snapshot detect(Bitmap screenshot,int selectedColor,float ownX,float ownY,List<Token> tokens){
    final int w=192,h=108,total=w*h;
    Bitmap small=Bitmap.createScaledBitmap(screenshot,w,h,false);
    int[] image=new int[total];small.getPixels(image,0,w,0,0,w,h);
    if(small!=screenshot)small.recycle();
    boolean[] same=new boolean[total];
    for(int k=0;k<total;k++)same[k]=distance(image[k],selectedColor)<SIZE_LIMIT*SIZE_LIMIT*3;
    int[] regions=new int[total]; // -1: cleared, 1: own, >=2: neighbouring
    int[] q=new int[total];
    int sx=Math.min(w-1,Math.max(0,Math.round(ownX*w)));
    int sy=Math.min(h-1,Math.max(0,Math.round(ownY*h)));
    int start=sy*w+sx;
    if(!same[start]){
      int closest=-1,best=1000;
      for(int dy=-7;dy<=7;dy++)for(int dx=-7;dx<=7;dx++){
        int xx=sx+dx,yy=sy+dy;if(xx<0||yy<0||xx>=w||yy>=h)continue;
        int v=yy*w+xx,metric=dx*dx+dy*dy;
        if(same[v]&&metric<best){best=metric;closest=v;}
      }
      if(closest<0)return new Snapshot(0,ownX,ownY,0,Collections.emptyList());
      start=closest;
    }
    int head=0,tail=0,xsum=0,ysum=0;
    q[tail++]=start;regions[start]=1;
    while(head<tail){
      int v=q[head++],x=v%w,y=v/w;xsum+=x;ysum+=y;
      int[] next={x>0?v-1:-1,x<w-1?v+1:-1,y>0?v-w:-1,y<h-1?v+w:-1};
      for(int j:next)if(j>=0&&regions[j]==0&&same[j]){
        regions[j]=1;q[tail++]=j;
      }
    }
    if(tail<20)return new Snapshot(tail,ownX,ownY,0,Collections.emptyList());
    float cx=xsum/(float)(tail*w),cy=ysum/(float)(tail*h);
    List<Region> found=new ArrayList<>();
    int nextId=2;
    for(int v=0;v<total;v++){
      if(regions[v]!=1)continue;
      int x=v%w,y=v/w;
      int[] near={x>0?v-1:-1,x<w-1?v+1:-1,y>0?v-w:-1,y<h-1?v+w:-1};
      for(int u:near){
        if(u<0||regions[u]!=0||same[u])continue;
        int ref=image[u],r=red(ref),g=green(ref),b=blue(ref);
        if(r<35&&g<35&&b<35)continue;
        head=0;int count=0,border=0;long sumX=0,sumY=0;
        q[count++]=u;regions[u]=nextId;
        while(head<count){
          int z=q[head++],xx=z%w,yy=z/w;
          sumX+=xx;sumY+=yy;
          int[] adj={xx>0?z-1:-1,xx<w-1?z+1:-1,yy>0?z-w:-1,yy<h-1?z+w:-1};
          for(int p:adj){
            if(p<0)continue;
            if(regions[p]==1){border++;continue;}
            if(regions[p]==0 && !same[p] && distance(image[p],ref)<3000){
              regions[p]=nextId;q[count++]=p;
            }
          }
        }
        if(count>=26&&border>=2){
          boolean water=b>r*1.25&&b>g*1.08;
          boolean neutral=(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<36
            ||(r>g*1.07&&g>b*1.05&&r>100));
          found.add(new Region(sumX/(float)(count*w),sumY/(float)(count*h),
            count,border,ref,neutral,water));
        }
        nextId++;
      }
    }
    found.sort((a,b)->Integer.compare(b.border,a.border));
    if(found.size()>20)found=new ArrayList<>(found.subList(0,20));
    Snapshot result=new Snapshot(tail,cx,cy,Math.min(1.0,tail/120.),found);
    if(tokens!=null){
      for(Token t:tokens){
        int number=units(t.text);
        if(number<0)continue;
        Region best=null;
        double distOwn=Math.hypot(t.x-cx,t.y-cy);
        double minDist=distOwn;
        for(Region reg:found){
          double d=Math.hypot(t.x-reg.x,t.y-reg.y);
          if(d<minDist){minDist=d;best=reg;}
        }
        if(minDist>.17)continue;
        if(best==null)result.troops=number;
        else if(!best.water)best.troops=number;
      }
    }
    return result;
  }
}
