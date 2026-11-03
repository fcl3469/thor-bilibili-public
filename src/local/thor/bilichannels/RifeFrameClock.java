package local.thor.bilichannels;

/** Associates a decoder Surface timestamp with its media PTS without unbounded queuing. */
final class RifeFrameClock {
 private final long[] release=new long[64],pts=new long[64];
 private final int[] revision=new int[64];
 private int start,size;
 synchronized void record(long releaseNs,long ptsUs,int epoch){
  if(size==release.length){start=(start+1)%release.length;size--;}
  int i=(start+size++)%release.length;release[i]=releaseNs;pts[i]=ptsUs;revision[i]=epoch;
 }
 synchronized long take(long timestampNs,int epoch){
  for(int n=0;n<size;n++){
   int i=(start+n)%release.length;
   if(release[i]!=timestampNs||revision[i]!=epoch)continue;
   long result=pts[i];start=(i+1)%release.length;size-=n+1;return result;
  }
  return Long.MIN_VALUE;
 }
}
