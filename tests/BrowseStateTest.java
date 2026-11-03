import local.thor.bilichannels.BrowseState;
public class BrowseStateTest {
 static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
 public static void main(String[] args){
  BrowseState s=new BrowseState(40);check(s.visibleRows==6&&s.end()==6,"default six rows");s.setVisibleRows(4);
  check(s.start()==0&&s.end()==4,"initial viewport");
  s.move(1);check(s.selected==2&&s.offset==0,"focus can move without scrolling the list");
  s.move(2);check(s.selected==4&&s.offset==1,"scroll only to reveal next row");
  s.scrollTo(9.25f);check(s.offset==9.25f&&s.start()==9,"drag preserves sub-row displacement");
  check(s.selected>=s.start()&&s.selected<s.end(),"preview selection follows visible window");
  s.select(11);check(s.offset==9.25f&&s.selected==11,"tap visible row keeps list position");
  s.scrollTo(20.8f);check(s.start()==21&&s.end()==25,"long swipe traverses many items");
  s.scrollTo(10.4f);check(s.start()==10,"reverse drag");
  s.scrollTo(10000);check(s.offset==36&&s.end()==40,"bottom clamp");
  s.scrollTo(-100);check(s.offset==0,"top clamp");
  for(int n=0;n<48;n++){s.reset(n);for(int step=0;step<150;step++){
   s.scrollTo((step-15)*.37f);s.move(step%3-1);
   check(s.offset>=0&&s.offset<=Math.max(0,n-4),"scroll range");
   check(s.start()>=0&&s.end()<=n&&s.end()-s.start()<=4,"preview bounds");
   if(n>0)check(s.selected>=s.start()&&s.selected<s.end(),"selected video belongs to preview window");
  }}
  for(int rows=4;rows<=6;rows++){s.setVisibleRows(rows);for(int n=0;n<30;n++){s.reset(n);for(int i=0;i<80;i++){s.scrollTo(i*.43f-2);s.move(i%3-1);check(s.start()>=0&&s.end()<=n&&s.end()-s.start()<=rows,"configurable viewport bounds");if(n>0)check(s.selected>=s.start()&&s.selected<s.end(),"configurable focus in viewport");}}}
  s.reset(24);s.select(23);s.setVisibleRows(4);check(s.end()==24&&s.selected==23,"changing row count preserves focus at bottom");
  int rev=s.revision;s.reset(12);check(s.revision!=rev&&s.offset==0,"feed switch resets viewport");
  System.out.println("PASS: continuous drag, long swipe, viewport selection, controller reveal, sparse and boundary cases");
 }
}
