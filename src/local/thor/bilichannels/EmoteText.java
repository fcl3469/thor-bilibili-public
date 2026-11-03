package local.thor.bilichannels;
import android.graphics.*;import android.text.*;import android.text.style.ReplacementSpan;import android.text.style.ClickableSpan;import android.text.method.LinkMovementMethod;import android.view.*;import android.widget.TextView;import android.os.SystemClock;import android.util.LruCache;import java.lang.ref.WeakReference;import java.util.*;
/** Inline Bilibili images, decoded off the UI thread. Recycled rows retain their own binding. */
final class EmoteText {
 final MainActivity a;
 final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(2*1024*1024){protected int sizeOf(String key,Bitmap b){return b.getByteCount();}};
 final Map<String,List<WeakReference<Binding>>> pending=new HashMap<>();final LruCache<String,Long> failures=new LruCache<>(64);
 final WeakHashMap<TextView,Binding> bindings=new WeakHashMap<>();
 EmoteText(MainActivity a){this.a=a;}
 interface Seek{void to(int millis);}
 static final class Part{final String text;final Map<String,Comment.Emote> emotes;final boolean times;Part(String text,Map<String,Comment.Emote> emotes,boolean times){this.text=text;this.emotes=emotes;this.times=times;}}
 static final class Binding{final WeakReference<TextView> view;final List<Part> parts;final Seek seek;Binding(TextView view,List<Part> parts,Seek seek){this.view=new WeakReference<>(view);this.parts=parts;this.seek=seek;}}
 void bind(TextView view,String text,Map<String,Comment.Emote> emotes){bind(view,text,emotes,null);}
 void bind(TextView view,String text,Map<String,Comment.Emote> emotes,Seek seek){bind(view,Collections.singletonList(new Part(text,emotes,true)),seek);}
 void bindChildren(TextView view,List<Comment> children,Seek seek){List<Part> parts=new ArrayList<>();for(Comment child:children){parts.add(new Part((parts.isEmpty()?"":"\n")+child.name+"：",Collections.emptyMap(),false));parts.add(new Part(child.message.replace('\n',' '),child.emotes,true));}bind(view,parts,seek);}
 void bind(TextView view,List<Part> parts,Seek seek){Binding b=new Binding(view,parts,seek);bindings.put(view,b);view.setMovementMethod(seek==null?null:LinkMovementMethod.getInstance());view.setOnTouchListener(seek==null?null:new LinkTouch());view.setFocusable(false);view.setHighlightColor(0x2234a9d8);render(b,true);}
 void render(Binding b,boolean load){TextView view=b.view.get();if(view==null||bindings.get(view)!=b)return;SpannableStringBuilder text=new SpannableStringBuilder();Set<String> needed=new HashSet<>();
  for(Part part:b.parts){int offset=text.length();text.append(part.text);int at=0;while(at<part.text.length()){Comment.Emote match=null;int from=part.text.length();for(Comment.Emote e:part.emotes.values()){int pos=part.text.indexOf(e.token,at);if(pos>=0&&(pos<from||pos==from&&(match==null||e.token.length()>match.token.length()))){from=pos;match=e;}}if(match==null)break;Bitmap bitmap=cache.get(match.url);if(bitmap!=null){int size=Math.round(view.getTextSize()*(match.size==2?2f:1.3f));text.setSpan(new Picture(bitmap,size),offset+from,offset+from+match.token.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}else needed.add(match.url);at=from+match.token.length();}}
  int offset=0;for(Part part:b.parts){if(b.seek!=null&&part.times)for(CommentTimestamps.Stamp stamp:CommentTimestamps.find(part.text))if(!insideEmote(part,stamp))text.setSpan(new TimeLink(b,stamp.millis),offset+stamp.start,offset+stamp.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);offset+=part.text.length();}
  view.setText(text);if(load)for(String url:needed)load(url,b);
 }
 boolean insideEmote(Part part,CommentTimestamps.Stamp stamp){for(String token:part.emotes.keySet())for(int at=part.text.indexOf(token);at>=0;at=part.text.indexOf(token,at+token.length()))if(at<stamp.end&&at+token.length()>stamp.start)return true;return false;}
 final class TimeLink extends ClickableSpan {
  final Binding binding;final int millis;TimeLink(Binding binding,int millis){this.binding=binding;this.millis=millis;}
  @Override public void onClick(View view){if(binding.view.get()==view&&bindings.get(view)==binding&&binding.seek!=null)binding.seek.to(millis);}
  @Override public void updateDrawState(TextPaint paint){paint.setColor(0xff008ac5);paint.setUnderlineText(false);}
 }
 /** Consume only a link tap; let ListView intercept drags and plain text keep its row action. */
 final class LinkTouch implements View.OnTouchListener {
  TimeLink pressed;float x,y;boolean moved;
  TimeLink hit(TextView view,MotionEvent e){if(!(view.getText() instanceof Spanned)||view.getLayout()==null)return null;Layout layout=view.getLayout();float px=e.getX()-view.getTotalPaddingLeft()+view.getScrollX(),py=e.getY()-view.getTotalPaddingTop()+view.getScrollY();if(py<0||py>=layout.getHeight())return null;int line=layout.getLineForVertical((int)py);if(line>=view.getMaxLines()||px<layout.getLineLeft(line)||px>layout.getLineRight(line))return null;int at=layout.getOffsetForHorizontal(line,px);if(layout.getEllipsisCount(line)>0&&at>=layout.getLineStart(line)+layout.getEllipsisStart(line))return null;Spanned text=(Spanned)view.getText();TimeLink[] spans=text.getSpans(at,at,TimeLink.class);return spans.length==0?null:spans[0];}
  void clear(TextView view){pressed=null;moved=false;if(view.getText() instanceof Spannable)Selection.removeSelection((Spannable)view.getText());}
  @Override public boolean onTouch(View target,MotionEvent e){TextView view=(TextView)target;int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){clear(view);pressed=hit(view,e);if(pressed==null)return false;x=e.getX();y=e.getY();Spannable text=(Spannable)view.getText();Selection.setSelection(text,text.getSpanStart(pressed),text.getSpanEnd(pressed));return true;}if(pressed==null)return false;
   if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_POINTER_DOWN){if(e.getPointerCount()>1||Math.hypot(e.getX()-x,e.getY()-y)>ViewConfiguration.get(view.getContext()).getScaledTouchSlop()){moved=true;Selection.removeSelection((Spannable)view.getText());}return true;}
   if(action==MotionEvent.ACTION_UP){TimeLink link=pressed,up=hit(view,e);boolean click=!moved&&up!=null&&up.binding==link.binding&&up.millis==link.millis;clear(view);if(click)link.onClick(view);return true;}if(action==MotionEvent.ACTION_CANCEL){clear(view);return true;}return true;
  }
 }
 void load(String url,Binding b){Long failed=failures.get(url);if(a.dead||failed!=null&&SystemClock.elapsedRealtime()-failed<60000)return;List<WeakReference<Binding>> waiters=pending.get(url);if(waiters!=null){waiters.removeIf(ref->ref.get()==null);for(WeakReference<Binding> ref:waiters)if(ref.get()==b)return;waiters.add(new WeakReference<>(b));return;}if(pending.size()>=64)return;waiters=new ArrayList<>();waiters.add(new WeakReference<>(b));pending.put(url,waiters);
  a.io.execute(()->{Bitmap result=null;try{byte[] data=BiliApi.get(url,2*1024*1024);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,o);if(o.outWidth>0&&o.outHeight>0&&o.outWidth<=4096&&o.outHeight<=4096){o.inJustDecodeBounds=false;o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>192)o.inSampleSize*=2;result=BitmapFactory.decodeByteArray(data,0,data.length,o);}}catch(Exception ignored){}final Bitmap ready=result;a.ui.post(()->{List<WeakReference<Binding>> list=pending.remove(url);if(a.dead)return;if(ready!=null)cache.put(url,ready);else failures.put(url,SystemClock.elapsedRealtime());if(list!=null)for(WeakReference<Binding> ref:list){Binding binding=ref.get();if(binding!=null)render(binding,false);}});});
 }
 static final class Picture extends ReplacementSpan {
  final Bitmap bitmap;final int height,width;final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  Picture(Bitmap bitmap,int size){this.bitmap=bitmap;float scale=Math.min(size/(float)bitmap.getHeight(),size*3f/bitmap.getWidth());height=Math.max(1,Math.round(bitmap.getHeight()*scale));width=Math.max(1,Math.round(bitmap.getWidth()*scale));}
  public int getSize(Paint p,CharSequence text,int start,int end,Paint.FontMetricsInt fm){if(fm!=null){Paint.FontMetricsInt f=p.getFontMetricsInt();int center=(f.ascent+f.descent)/2;fm.ascent=Math.min(f.ascent,center-height/2);fm.descent=Math.max(f.descent,center+(height+1)/2);fm.top=Math.min(f.top,fm.ascent);fm.bottom=Math.max(f.bottom,fm.descent);}return width;}
  public void draw(Canvas c,CharSequence text,int start,int end,float x,int top,int y,int bottom,Paint p){Paint.FontMetricsInt f=p.getFontMetricsInt();float cy=y+(f.ascent+f.descent)/2f;c.drawBitmap(bitmap,null,new RectF(x,cy-height/2f,x+width,cy+height/2f),imagePaint);}
 }
}
