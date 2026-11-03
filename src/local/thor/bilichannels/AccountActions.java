package local.thor.bilichannels;
/** Account write boundary. Device checks replace this object and never write to Bilibili. */
class AccountActions {
 boolean liked(Video v)throws Exception{return BiliApi.liked(v);}
 void like(Video v,boolean wanted)throws Exception{BiliApi.like(v,wanted);}
 void coin(Video v,int count)throws Exception{BiliApi.coin(v,count);}
 void comment(Video v,long root,long parent,String text)throws Exception{
  if(v.aid==0)BiliApi.hydrate(v);
  BiliApi.post("/x/v2/reply/add",BiliApi.params("oid",String.valueOf(v.aid),"type","1","message",text,"root",String.valueOf(root),"parent",String.valueOf(parent),"plat","1"));
 }
 void danmaku(Video v,int position,String text)throws Exception{BiliApi.sendDanmaku(v,position,text);}
}
