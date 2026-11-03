package local.thor.bilichannels;
import android.content.Context;import android.security.keystore.*;import android.util.AtomicFile;
import java.io.*;import java.security.KeyStore;import javax.crypto.*;import javax.crypto.spec.GCMParameterSpec;
/** Session saved only in app-private storage, encrypted by Android Keystore. */
public final class AuthSession {
 final AtomicFile file;final SessionCookies cookies=new SessionCookies();private int epoch;
 volatile int vipStatus=-1;volatile String name="未登录";volatile long mid;volatile String storageError="";
 public AuthSession(Context c){file=new AtomicFile(new File(c.getFilesDir(),"account.enc"));}
 private SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);String alias="thor-bili-session-v1";if(!ks.containsAlias(alias)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}return (SecretKey)ks.getKey(alias,null);}
 public synchronized int epoch(){return epoch;}
 public synchronized void load(){try(DataInputStream in=new DataInputStream(file.openRead())){int n=in.readInt();if(n!=12)throw new IOException();byte[] iv=new byte[n];in.readFully(iv);byte[] bytes=BiliApi.read(in,32768);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));cookies.merge("https://api.bilibili.com",new String(cipher.doFinal(bytes),"UTF-8"));}catch(FileNotFoundException ignored){}catch(Exception e){cookies.clear();storageError="登录信息无法读取，请重新登录";}}
 public synchronized void save(){FileOutputStream out=null;try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());out=file.startWrite();DataOutputStream d=new DataOutputStream(out);d.writeInt(c.getIV().length);d.write(c.getIV());d.write(c.doFinal(cookies.header("https://api.bilibili.com").getBytes("UTF-8")));d.flush();file.finishWrite(out);storageError="";}catch(Exception e){if(out!=null)file.failWrite(out);storageError="本次已登录，但登录信息未能安全保存";}}
 public synchronized void clear(){epoch++;cookies.clear();name="未登录";mid=0;vipStatus=-1;file.delete();}
}
