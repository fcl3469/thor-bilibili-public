package local.thor.bilichannels;

/** One-way per-video decision after startup; avoids oscillating between profiles. */
final class RifeFrameBudget {
 private int warmup,samples,consecutiveLate;private long work,budget,firstGenerated;boolean fast;
 void reset(){warmup=samples=consecutiveLate=0;work=budget=firstGenerated=0;fast=false;}
 boolean record(long workUs,long budgetUs,long generated){
  if(fast)return true;if(workUs<=0||budgetUs<=0)return false;
  consecutiveLate=workUs>budgetUs?consecutiveLate+1:0;
  if(consecutiveLate>=8){fast=true;return true;}
  if(warmup++<12)return false;
  if(samples==0)firstGenerated=generated;
  samples++;work+=workUs;budget+=budgetUs;
  if(samples<48)return false;
  // Include one pending middle frame. Leave headroom for decoder/display contention.
  long produced=Math.max(0,generated-firstGenerated)+1;
  fast=work*10>budget*9||produced*100<samples*90;
  samples=0;work=budget=0;return fast;
 }
}
