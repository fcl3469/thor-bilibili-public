#!/usr/bin/env python3
"""Host checks for cloud account isolation, resume persistence, and URL exclusion."""
from pathlib import Path
import os,subprocess,tempfile,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build import ROOT,JDK,ENV
source=r'''package local.thor.bilichannels;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import org.json.*;
public final class CloudLibraryChecks {
 static int checks;static void check(boolean yes){if(!yes)throw new AssertionError("check "+checks);checks++;}
 public static void main(String[] args)throws Exception{
  File file=new File(args[0],"history.json");XunleiLibrary store=new XunleiLibrary(file);long now=System.currentTimeMillis();String a=XunleiLibrary.accountKey("user-a"),b=XunleiLibrary.accountKey("user-b");
  XunleiLibrary.Item item=new XunleiLibrary.Item(new JSONObject().put("id","same-file").put("name","测试视频.mp4").put("mime","video/mp4").put("url","https://fixture.invalid/video?token=DO_NOT_SAVE").put("thumbnail","https://fixture.invalid/thumb?token=DO_NOT_SAVE"));
  check(!a.equals(b)&&a.length()==64);XunleiLibrary.Record r=store.played(a,item,"原文件",now);store.progress(a,item.id,item.space,42123,100000,now+1);
  check(store.recent(a,now+2).size()==1&&store.recent(b,now+2).isEmpty());String key=r.key;check(key.equals(XunleiLibrary.key(a,item.id,item.space)));
  XunleiLibrary recovered=new XunleiLibrary(file);XunleiLibrary.Record loaded=recovered.get(a,item.id,item.space);check(loaded.position==42123&&loaded.duration==100000);check(loaded.quality.equals("原文件"));
  recovered.played(a,item,"1080P",now+3);check(recovered.get(a,item.id,item.space).key.equals(key)&&recovered.get(a,item.id,item.space).position==42123);
  recovered.played(b,item,"原文件",now+4);check(recovered.recent(a,now+5).size()==1&&recovered.recent(b,now+5).size()==1);check(!recovered.get(b,item.id,item.space).key.equals(key));
  String disk=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);check(!disk.contains("DO_NOT_SAVE")&&!disk.contains("https://")&&!disk.contains("user-a"));
  XunleiLibrary.Item otherSpace=new XunleiLibrary.Item(new JSONObject().put("id","same-file").put("name","video.mp4").put("space","SPACE_OTHER"));recovered.played(a,otherSpace,"原文件",now+6);check(recovered.recent(a,now+7).size()==2);
  check(recovered.recent(a,now+XunleiLibrary.RECENT_MS+100).isEmpty());recovered.remove(key,now+8);check(recovered.get(a,item.id,item.space)==null&&recovered.get(b,item.id,item.space)!=null);
  XunleiLibrary.Item typed=new XunleiLibrary.Item(new JSONObject().put("id","no-extension").put("category","VIDEO"));check(typed.video());check(!new XunleiLibrary.Item(new JSONObject().put("folder",true)).video());
  JSONArray first=new JSONArray().put(new JSONObject().put("id","folder-old").put("folder",true).put("modified_ms",100)).put(new JSONObject().put("id","mkv-new").put("name","new.mkv").put("modified_ms",400)).put(new JSONObject().put("id","mov").put("name","clip.MOV").put("modified_ms",250)).put(new JSONObject().put("id","unknown").put("name","clip.mp4").put("modified_ms",-1));
  java.util.List<XunleiLibrary.Item> catalog=XunleiLibrary.mergeCatalog(java.util.Collections.emptyList(),first);
  check(catalog.get(0).id.equals("mkv-new")&&catalog.get(1).id.equals("mov")&&catalog.get(2).folder);check(catalog.get(3).date()==0);
  String anchor=catalog.get(0).catalogKey();
  JSONArray next=new JSONArray().put(new JSONObject().put("id","folder-old").put("folder",true).put("modified_ms",500)).put(new JSONObject().put("id","created-only").put("name","video.ts").put("created_ms",300)).put(new JSONObject().put("id","not-a-video").put("name","notes.txt"));
  catalog=XunleiLibrary.mergeCatalog(catalog,next);check(catalog.size()==5);check(catalog.get(0).id.equals("folder-old")&&catalog.get(1).id.equals("mkv-new")&&catalog.get(2).id.equals("created-only"));check(XunleiLibrary.catalogAnchor(catalog,anchor,0)==1);check(XunleiLibrary.catalogAnchor(catalog,"missing",999)==4);
  check(new XunleiLibrary.Item(catalog.get(2).json()).created==300);check(new XunleiLibrary.Item(new JSONObject().put("modified_ms",200).put("created_ms",900)).date()==200);
  JSONArray tied=new JSONArray().put(new JSONObject().put("id","same-time-video").put("name","same.mp4").put("modified_ms",500));catalog=XunleiLibrary.mergeCatalog(catalog,tied);check(catalog.get(0).id.equals("folder-old")&&catalog.get(1).id.equals("same-time-video"));
  check(XunleiLibrary.catalogAnchor(java.util.Collections.emptyList(),"none",4)==0);
  System.out.println("{\"passed\":true,\"checks\":"+checks+",\"account_writes\":false}");
 }
}'''
with tempfile.TemporaryDirectory(prefix='thor-cloud-history-') as tmp:
    base=Path(tmp);test=base/'CloudLibraryChecks.java';test.write_text(source)
    cp=ROOT/'test-deps/json-20240303.jar'
    subprocess.run([str(JDK/'bin/javac'),'-encoding','UTF-8','-cp',str(cp),'-d',str(base),str(ROOT/'src/local/thor/bilichannels/TorrentFiles.java'),str(ROOT/'src/local/thor/bilichannels/XunleiLibrary.java'),str(test)],env=ENV,check=True)
    subprocess.run([str(JDK/'bin/java'),'-cp',str(base)+os.pathsep+str(cp),'local.thor.bilichannels.CloudLibraryChecks',str(base)],env=ENV,check=True)
