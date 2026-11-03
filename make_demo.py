#!/usr/bin/env python3
"""Generate four original, silent 10-second decoder test clips using ffmpeg."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).parent
colors=['8dadbf','768a7f','888eb8','dbad7d']
for i,color in enumerate(colors):
    out=ROOT/'assets'/f'demo-{i}.mp4'
    width,height=(640,360) if i<2 else (360,640)
    filters=("drawgrid=w=64:h=60:t=1:c=white@0.1,"
       f"drawbox=x=32:y=48:w={width-64}:h={height-96}:t=2:c=white@0.45,"
       f"drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='THOR CHANNEL {i+1:02}':fontcolor=white:fontsize=26:x=(w-tw)/2:y=h*.42+7*sin(t*0.628318),"
       f"drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='{width} x {height}':fontcolor=white@0.75:fontsize=15:x=(w-tw)/2:y=h*.6,"
       "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='o':fontcolor=white@0.8:fontsize=27:x=40+(w-80)*t/10:y=h-80")
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i',f'color=c=0x{color}:s={width}x{height}:r=24:d=10','-vf',filters,'-an','-c:v','libx264','-threads','2','-preset','fast','-crf','23','-pix_fmt','yuv420p','-movflags','+faststart',str(out)],check=True)
    print(out.name,out.stat().st_size)
