package local.thor.bilichannels;
import android.content.Context;import android.graphics.*;import android.view.View;
final class AppIcon extends View {
  final String kind;final Paint p=new Paint(3);final int color;AppIcon(Context context,String kind,int color){super(context);this.kind=kind;this.color=color;}
  @Override protected void onDraw(Canvas canvas){Canvas c=canvas;c.save();c.scale(getWidth()/32f,getHeight()/32f);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();
   if(kind.equals("star")){for(int i=0;i<10;i++){double t=-Math.PI/2+i*Math.PI/5;float radius=i%2==0?13:6,x=16+(float)Math.cos(t)*radius,y=16+(float)Math.sin(t)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();c.drawPath(path,p);}
   else if(kind.equals("download")){c.drawRoundRect(3,8,29,29,4,4,p);c.drawLine(9,4,23,4,p);c.drawLine(16,11,16,23,p);c.drawLine(11,18,16,23,p);c.drawLine(16,23,21,18,p);}
   else if(kind.equals("person")){c.drawCircle(16,9,6,p);c.drawArc(4,18,28,36,180,180,false,p);}
   else if(kind.equals("home")){path.moveTo(3,14);path.lineTo(16,3);path.lineTo(29,14);c.drawPath(path,p);c.drawRoundRect(7,13,25,29,3,3,p);}
   else if(kind.equals("film")){c.drawRoundRect(3,5,29,27,4,4,p);c.drawLine(3,11,29,11,p);for(int i=8;i<29;i+=7)c.drawLine(i,5,i-3,11,p);path.moveTo(13,15);path.lineTo(21,19);path.lineTo(13,23);path.close();c.drawPath(path,p);}
   else if(kind.equals("search")){c.drawCircle(13,13,9,p);c.drawLine(20,20,29,29,p);}
   else if(kind.equals("dynamic")){c.drawRoundRect(3,5,29,27,4,4,p);c.drawLine(8,12,24,12,p);c.drawLine(8,19,19,19,p);}
   else if(kind.equals("hot")){path.moveTo(18,2);path.cubicTo(26,12,7,9,7,21);path.cubicTo(7,33,29,33,27,18);path.lineTo(22,12);c.drawPath(path,p);}
   else{c.drawArc(3,3,29,29,-55,315,false,p);if(kind.equals("later")){path.moveTo(13,10);path.lineTo(22,16);path.lineTo(13,22);path.close();c.drawPath(path,p);}else{c.drawLine(16,7,16,16,p);c.drawLine(16,16,23,16,p);}c.drawLine(3,7,3,14,p);c.drawLine(3,14,9,14,p);}c.restore();}
 }
