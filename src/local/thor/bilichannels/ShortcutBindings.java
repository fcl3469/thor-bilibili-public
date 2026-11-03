package local.thor.bilichannels;

/** Key and analog events from one trigger share an edge, so they cannot toggle twice. */
public final class ShortcutBindings {
 public static final String[] IDS={"danmaku","mute","pause","back10","forward10","none"};
 public static final String[] LABELS={"显示 / 关闭弹幕","应用内静音 / 恢复声音","播放 / 暂停","后退 10 秒","前进 10 秒","不执行操作"};
 private final boolean[] keys=new boolean[2],axes=new boolean[2];
 public static String valid(String value,int side){for(String id:IDS)if(id.equals(value))return id;return side==0?"danmaku":"mute";}
 public static String label(String value){for(int i=0;i<IDS.length;i++)if(IDS[i].equals(value))return LABELS[i];return "未设置";}
 public boolean edge(int side,boolean analog,boolean down){boolean before=keys[side]||axes[side];(analog?axes:keys)[side]=down;return !before&&(keys[side]||axes[side]);}
 public void reset(){for(int i=0;i<2;i++)keys[i]=axes[i]=false;}
}
