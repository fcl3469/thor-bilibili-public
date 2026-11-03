package local.thor.bilichannels;
import java.util.*;import java.io.*;
/** Small offline pinyin editor. Phrase data is shared; each page owns its draft and cursor. */
public final class PinyinInput {
 private static volatile PhraseIndex sharedIndex;private PhraseIndex index;
 private final Map<String,String> dictionary=new HashMap<>();private static volatile Map<String,List<String>> sharedPhrases;
 private Map<String,List<String>> phrases=Collections.emptyMap();public String text="",composing="";public boolean chinese=true;public int page,cursor=-1,anchor=-1;private String cachedKey="";private List<String> cached=Collections.emptyList();private int consumed;
 public PinyinInput(InputStream in)throws IOException{try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,"UTF-8"))){String line;while((line=reader.readLine())!=null){String[] p=line.split("\t",2);if(p.length==2)dictionary.put(p[0],p[1]);}}}
 public void phrases(InputStream in)throws IOException{try(InputStream source=in){if(sharedPhrases==null){synchronized(PinyinInput.class){if(sharedPhrases==null){Map<String,List<String>> result=new HashMap<>();Map<String,Float> ranks=new HashMap<>();BufferedReader reader=new BufferedReader(new InputStreamReader(source,"UTF-8"));String line;while((line=reader.readLine())!=null){String[] p=line.split("\t");if(p.length<2||p[0].startsWith("#"))continue;String key=p[1].replace(" ","").replace("ü","v");if(!key.matches("[a-z]+"))continue;List<String> words=result.get(key);if(words==null){words=new ArrayList<>();result.put(key,words);}if(!words.contains(p[0]))words.add(p[0]);try{ranks.put(key+" "+p[0],p.length>2?Float.parseFloat(p[2]):0f);}catch(NumberFormatException ignored){}}for(Map.Entry<String,List<String>> entry:result.entrySet()){String key=entry.getKey();entry.getValue().sort((a,b)->Float.compare(ranks.getOrDefault(key+" "+b,0f),ranks.getOrDefault(key+" "+a,0f)));}sharedPhrases=result;}}}phrases=sharedPhrases;}invalidate();}
 public void indexedPhrases(InputStream in)throws IOException{try(InputStream source=in){if(sharedIndex==null)synchronized(PinyinInput.class){if(sharedIndex==null)sharedIndex=new PhraseIndex(source);}index=sharedIndex;}invalidate();}
 static final class PhraseIndex {
  final int[] offsets;final byte[] bytes;
  PhraseIndex(InputStream in)throws IOException{DataInputStream data=new DataInputStream(new BufferedInputStream(in));if(data.readInt()!=0x54485031)throw new IOException("词库格式无效");int count=data.readInt(),size=data.readInt();if(count<0||count>2000000||size<0||size>80000000)throw new IOException("词库大小无效");offsets=new int[count+1];for(int i=0;i<count;i++)offsets[i]=data.readInt();offsets[count]=size;bytes=new byte[size];data.readFully(bytes);for(int i=0;i<count;i++)if(offsets[i]<0||offsets[i]>=offsets[i+1])throw new IOException("词库索引无效");}
  int compare(String key,int offset){int i=0;while(offset+i<bytes.length&&bytes[offset+i]!='\t'){if(i>=key.length())return -1;int difference=key.charAt(i)-(bytes[offset+i]&255);if(difference!=0)return difference;i++;}return key.length()-i;}
  List<String> get(String key){int lo=0,hi=offsets.length-2;while(lo<=hi){int mid=(lo+hi)>>>1,cmp=compare(key,offsets[mid]);if(cmp<0)hi=mid-1;else if(cmp>0)lo=mid+1;else{int start=offsets[mid]+key.length()+1;return Arrays.asList(new String(bytes,start,offsets[mid+1]-start-1,java.nio.charset.StandardCharsets.UTF_8).split("\t"));}}return null;}
 }
 private void invalidate(){cachedKey="\u0000";page=0;}
 public int position(){return cursor<0?text.length():Math.min(cursor,text.length());}
 public void selection(int start,int end){commitLiteral();cursor=boundary(end);anchor=boundary(start);}
 int boundary(int at){at=Math.max(0,Math.min(text.length(),at));if(at>0&&at<text.length()&&Character.isLowSurrogate(text.charAt(at)))at--;return at;}
 public void setText(String value){text=value==null?"":value;composing="";cursor=text.length();anchor=cursor;invalidate();}
 public void insert(String value){int end=position(),start=anchor<0?end:boundary(anchor);int lo=Math.min(start,end),hi=Math.max(start,end);String next=text.substring(0,lo)+value+text.substring(hi);if(next.codePointCount(0,next.length())>1000)return;text=next;cursor=lo+value.length();anchor=cursor;}
 public void type(char c){invalidate();if(chinese&&c>='a'&&c<='z'){if(composing.length()<48)composing+=c;}else{if(c==' '&&!composing.isEmpty()&&!options().isEmpty()){choose(0);return;}commitLiteral();insert(String.valueOf(c));}}
 /** Legacy single-syllable API, also used for fallback characters. */
 public String candidates(){String s=dictionary.get(composing);return s==null?"":s;}
 public List<String> options(){if(cachedKey.equals(composing))return cached;cachedKey=composing;ArrayList<String> list=new ArrayList<>();consumed=composing.length();if(composing.isEmpty()){cached=list;return list;}
  for(int len=composing.length();len>0;len--){String part=composing.substring(0,len);List<String> words=index==null?phrases.get(part):index.get(part);String chars=dictionary.get(part);if(words==null&&chars==null)continue;consumed=len;if(words!=null)list.addAll(words);if(chars!=null)for(int i=0;i<chars.length();){int cp=chars.codePointAt(i);String value=new String(Character.toChars(cp));if(!list.contains(value))list.add(value);i+=Character.charCount(cp);}break;}cached=list;return list;
 }
 public void choose(int slot){List<String> values=options();int index=page*10+slot;if(index<0||index>=values.size())return;insert(values.get(index));composing=composing.substring(consumed);invalidate();}
 public void next(int delta){int last=Math.max(0,(options().size()-1)/10);page=Math.max(0,Math.min(last,page+delta));}
 public void backspace(){if(!composing.isEmpty())composing=composing.substring(0,composing.length()-1);else{int end=position();if(anchor>=0&&anchor!=end)insert("");else if(end>0){anchor=text.offsetByCodePoints(end,-1);insert("");}}invalidate();}
 public void forwardDelete(){commitLiteral();int at=position();if(anchor>=0&&anchor!=at){insert("");return;}if(at<text.length()){anchor=text.offsetByCodePoints(at,1);insert("");}}
 public void moveCursor(int delta){commitLiteral();int at=position();if(delta<0&&at>0)at=text.offsetByCodePoints(at,-1);if(delta>0&&at<text.length())at=text.offsetByCodePoints(at,1);cursor=anchor=at;}
 public void commitLiteral(){if(!composing.isEmpty())insert(composing);composing="";invalidate();}
 public String value(){int at=position();return text.substring(0,at)+composing+text.substring(at);}
}
