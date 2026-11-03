#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>
namespace thor {
struct Plane {const uint8_t* data;size_t size;int row,pixel;};
inline void checkPlane(const Plane& p,int lastX,int lastY){if(!p.data||p.row<=0||p.pixel<=0||lastX<0||lastY<0||size_t(lastY)*p.row+size_t(lastX)*p.pixel>=p.size)throw std::runtime_error("Invalid decoder YUV plane/stride");}
inline void yuvToRgb(int iw,int ih,int left,int top,int w,int h,int dataspace,const Plane& y,const Plane& u,const Plane& v,std::vector<uint8_t>& rgb){
 if(w<=0||h<=0||iw<=0||ih<=0||left<0||top<0||int64_t(left)+w>iw||int64_t(top)+h>ih||int64_t(w)*h>3840LL*2160)throw std::runtime_error("Invalid or larger-than-4K decoder frame");
 checkPlane(y,left+w-1,top+h-1);checkPlane(u,(left+w-1)/2,(top+h-1)/2);checkPlane(v,(left+w-1)/2,(top+h-1)/2);
 int transfer=(dataspace>>22)&31;if(transfer==6||transfer==7)throw std::runtime_error("HDR requires native playback");
 int standard=(dataspace>>16)&63;bool full=((dataspace>>27)&7)==1;
 // Android <33/unspecified buffers use the conventional SD/HD matrix until metadata supplies it.
 if(!standard)standard=h>=720?1:2;
 if(standard<1||standard>6)throw std::runtime_error("Unsupported YUV color standard");
 double kr=standard==1?.2126:standard==6?.2627:.299,kb=standard==1?.0722:standard==6?.0593:.114,kg=1-kr-kb;
 double ys=full?1:255.0/219,cs=full?1:255.0/224;
 int cy=int(ys*16384+.5),rv=int(2*(1-kr)*cs*16384+.5),bu=int(2*(1-kb)*cs*16384+.5),gu=int(2*kb*(1-kb)/kg*cs*16384+.5),gv=int(2*kr*(1-kr)/kg*cs*16384+.5);
 rgb.resize(size_t(w)*h*3);for(int row=0;row<h;row++)for(int col=0;col<w;col++){
  int px=left+col,py=top+row;int yy=int(y.data[size_t(py)*y.row+size_t(px)*y.pixel])-(full?0:16);int uu=int(u.data[size_t(py/2)*u.row+size_t(px/2)*u.pixel])-128;int vv=int(v.data[size_t(py/2)*v.row+size_t(px/2)*v.pixel])-128;
  size_t dst=(size_t(row)*w+col)*3;rgb[dst]=uint8_t(std::clamp((cy*yy+rv*vv+8192)>>14,0,255));rgb[dst+1]=uint8_t(std::clamp((cy*yy-gu*uu-gv*vv+8192)>>14,0,255));rgb[dst+2]=uint8_t(std::clamp((cy*yy+bu*uu+8192)>>14,0,255));
 }
}
}
