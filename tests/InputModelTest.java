import local.thor.bilichannels.*;import java.util.*;import java.io.*;
public class InputModelTest{
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  PinyinInput p=new PinyinInput(new FileInputStream(args[0]));p.phrases(new FileInputStream(args[1]));
  for(char c:"zhangji".toCharArray())p.type(c);check(p.options().contains("掌机"),"phrase candidate");int i=p.options().indexOf("掌机");p.page=i/10;p.choose(i%10);check(p.value().equals("掌机"),"whole phrase commit");
  p.setText("");for(char c:"nihao".toCharArray())p.type(c);check(p.options().get(0).equals("你好"),"frequency-sorted phrase");p.type(' ');check(p.value().equals("你好"),"space selects candidate");
  p.setText("你好世界");p.selection(2,2);p.insert("，");check(p.value().equals("你好，世界"),"insert at cursor");p.selection(0,2);p.insert("Thor");check(p.value().equals("Thor，世界"),"replace selection");
  p.setText("A😀B");p.selection(3,3);p.backspace();check(p.text.equals("AB"),"surrogate deletion");p.moveCursor(-1);p.forwardDelete();check(p.text.equals("B"),"forward deletion");p.selection(0,1);p.insert("");check(p.text.isEmpty(),"clear selected text");
  for(float h:new float[]{235,298,360,510})for(boolean symbols:new boolean[]{false,true}){
   List<SearchKeyboard.Key> keys=SearchKeyboard.make(h,symbols,false,true,Arrays.asList("你好","掌机"),0);
   StringBuilder row=new StringBuilder();for(SearchKeyboard.Key k:keys){check(k.x>=0&&k.y>=0&&k.w>0&&k.h>0&&k.x+k.w<=480.01&&k.y+k.h<=h+.01,"keyboard bounds");if(k.row==2&&k.id.startsWith("text:"))row.append(k.label);}
   check(row.toString().equals(symbols?"1234567890":"qwertyuiop"),"standard row");
   int candidate=SearchKeyboard.move(keys,10,0,-1);check(keys.get(candidate).id.startsWith("candidate:")&&!keys.get(candidate).label.isEmpty(),"controller skips empty candidate row");
   for(int f=0;f<keys.size();f++)for(int dir:new int[]{-1,1}){int next=SearchKeyboard.move(keys,f,0,dir);check(next>=0&&next<keys.size(),"controller navigation bounds");}
  }
  long begin=System.nanoTime();p.indexedPhrases(new FileInputStream(args[2]));for(String pair:new String[]{"qiche:汽车","juequling:绝区零","zhangji:掌机","benghuaixingqiongtiedao:崩坏星穹铁道","ribenav:日本AV","chengrenyingshi:成人影视","dianzizhacai:电子榨菜"}){String[] parts=pair.split(":");p.setText("");for(char c:parts[0].toCharArray())p.type(c);check(p.options().contains(parts[1]),"expanded phrase: "+parts[1]);}System.out.println("Index load + domain phrases ms="+(System.nanoTime()-begin)/1000000);
  System.out.println("PASS: phrase frequency, commit, cursor/selection, Unicode editing, QWERTY and 8 keyboard layouts");
 }
}
