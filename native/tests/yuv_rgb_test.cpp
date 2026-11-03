#include "../yuv_rgb.h"
#include <cassert>
#include <iostream>
int main(){
 using namespace thor;
 std::vector<uint8_t> y(24,16),uv(12,128),rgb;
 Plane yp{y.data(),y.size(),6,1},up{uv.data(),uv.size(),6,2},vp{uv.data()+1,uv.size()-1,6,2};
 yuvToRgb(4,4,0,0,4,4,1<<16,yp,up,vp,rgb);for(auto c:rgb)assert(c==0);
 std::fill(y.begin(),y.end(),235);yuvToRgb(4,4,1,1,3,3,1<<16,yp,up,vp,rgb);assert(rgb.size()==27);for(auto c:rgb)assert(c==255);
 std::fill(y.begin(),y.end(),128);yuvToRgb(4,4,0,0,4,4,(1<<16)|(1<<27),yp,up,vp,rgb);for(auto c:rgb)assert(c==128);
 bool caught=false;try{yuvToRgb(4,4,0,0,4,4,0,{y.data(),2,6,1},up,vp,rgb);}catch(const std::runtime_error&){caught=true;}assert(caught);
 caught=false;try{yuvToRgb(4,4,0,0,4,4,6<<22,yp,up,vp,rgb);}catch(const std::runtime_error&){caught=true;}assert(caught);
 caught=false;try{yuvToRgb(4,4,2,2,4,4,0,yp,up,vp,rgb);}catch(const std::runtime_error&){caught=true;}assert(caught);
 // Limited BT.601 red; catches swapped U/V and incorrect video range.
 std::fill(y.begin(),y.end(),81);for(size_t i=0;i<uv.size();i+=2){uv[i]=90;uv[i+1]=240;}
 yuvToRgb(4,4,0,0,4,4,2<<16,yp,up,vp,rgb);assert(rgb[0]>=253&&rgb[1]<=1&&rgb[2]<=1);
 std::cout<<"YUV stride, interleaving, crop, range, matrix, HDR rejection and bounds passed\n";
}
