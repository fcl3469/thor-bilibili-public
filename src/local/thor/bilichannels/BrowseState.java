package local.thor.bilichannels;
public final class BrowseState {
 public int count,selected,revision;
 public int visibleRows=6;
 public void setVisibleRows(int rows){rows=Math.max(4,Math.min(6,rows));if(rows==visibleRows)return;visibleRows=rows;offset=clamp(offset);select(selected);revision++;}
 public float offset;
 public BrowseState(int count){reset(count);}
 public void reset(int n){count=Math.max(0,n);selected=count>1?1:0;offset=0;revision++;}
 public boolean move(int delta){return select(selected+delta);}
 public boolean select(int index){int next=Math.max(0,Math.min(Math.max(0,count-1),index));boolean change=next!=selected;selected=next;if(selected<offset)offset=selected;else if(selected>offset+visibleRows-1)offset=selected-visibleRows+1;offset=clamp(offset);return change;}
 public float clamp(float rows){return Math.max(0,Math.min(Math.max(0,count-visibleRows),rows));}
 public boolean scrollTo(float rows){int oldStart=start(),oldSelected=selected;offset=clamp(rows);if(count>0)selected=Math.max(start(),Math.min(end()-1,selected));return oldStart!=start()||oldSelected!=selected;}
 public int start(){return Math.max(0,Math.min(Math.max(0,count-visibleRows),Math.round(offset)));}
 public int end(){return Math.min(count,start()+visibleRows);}
}
