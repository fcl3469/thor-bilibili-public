package local.thor.bilichannels;

import java.util.Arrays;

/** Repairs balanced coincident/backward/double-gap AVC timestamps after observing a full window. */
final class FrameCadence {
 private final long[] gaps=new long[64];private int count,index;private long raw=Long.MIN_VALUE,mapped,period;
 void reset(){count=index=0;raw=Long.MIN_VALUE;mapped=period=0;}
 boolean active(){return period>0;}
 float fps(){return period>0?1000000f/period:0;}
 long map(long pts,float nominal){
  if(raw==Long.MIN_VALUE){raw=mapped=pts;return pts;}
  long gap=pts-raw;
  if(gap< -100000||gap>200000||!Float.isFinite(nominal)||nominal<20||nominal>35){reset();raw=mapped=pts;return pts;}
  gaps[index++%gaps.length]=gap;count=Math.min(gaps.length,count+1);
  if(period==0&&count==gaps.length){long candidate=detect();if(candidate>0){long aligned=aligned(pts,candidate);if(Math.abs(aligned-pts)<=candidate+5000){period=candidate;mapped=aligned-period;}}}
  raw=pts;
  if(period>0){
   if(index%32==0&&detect()==0){reset();raw=mapped=pts;return pts;}
   long next=mapped+period,limit=period+5000;next=Math.max(pts-limit,Math.min(pts+limit,next));
   if(next>mapped){mapped=next;return mapped;}reset();raw=mapped=pts;return pts;
  }
  mapped=pts;return pts;
 }
 private long detect(){
  long[] middle=new long[gaps.length];int n=0;long sum=0;
  for(long gap:gaps){sum+=gap;if(gap>=20000&&gap<=50000)middle[n++]=gap;}
  if(n<16)return 0;Arrays.sort(middle,0,n);double typical=middle[n/2],best=0,distance=Double.MAX_VALUE;
  for(double fps:new double[]{23.976023976,24,25,29.97002997,30}){double p=1000000/fps,d=Math.abs(p-typical);if(d<distance){distance=d;best=p;}}
  if(distance>2000)return 0;int near=0,backward=0,doubled=0,normal=0;
  for(long gap:gaps){if(Math.abs(gap)<1000)near++;else if(Math.abs(gap+best)<=2500)backward++;else if(Math.abs(gap-best)<=2500)normal++;else if(Math.abs(gap-best*2)<=2500)doubled++;}
  if(near+backward<6||doubled<6||Math.abs(doubled-near-backward*2)>3||near+backward+doubled+normal<62||Math.abs(sum/64.0-best)>best*.08)return 0;
  return Math.round(best);
 }
 private long aligned(long pts,long step){
  long sum=0;for(long gap:gaps)sum+=gap;long value=pts-sum;long[] phase=new long[gaps.length+1];phase[0]=value;
  for(int i=0;i<gaps.length;i++){value+=gaps[(index+i)%gaps.length];phase[i+1]=value-(i+1)*step;}
  Arrays.sort(phase);return phase[phase.length/2]+gaps.length*step;
 }
}
