package com.akasha.kqilab;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity7 extends Activity {
  static final int REQ=42;
  static final UUID RX=UUID.fromString("8ec94e31-f315-4f60-9fb8-838830daea50"), TX=UUID.fromString("8ec94e32-f315-4f60-9fb8-838830daea50"), CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  static final byte[] READ=h("01 21 00 B5 D4 C0 99 F0 50 6C 4A 7C 5B 34 B0 0C BF 13 D4 67");
  static final byte[] S18=h("01 22 00 8E 9A 94 F6 34 0A 29 A7 58 F8 72 4D FF CC 73 EB 1B");
  static final byte[] S19=h("01 22 00 EF 2A F7 8E A2 66 E7 9F 5B 5D 55 0F 90 C9 26 11 FB");
  static final byte[] S32=h("01 22 00 4F D9 BB DB 2D 06 52 1A F4 C8 A8 8D 1B 59 87 5F CB");
  static final byte[] ACK=h("01 A2 00 45 78 6A AF C7 E4 50 22 F3 EC 3D 55 C9 1D 80 B5 22");

  BluetoothAdapter adapter; BluetoothLeScanner scanner; BluetoothGatt gatt; BluetoothGattCharacteristic rx,tx;
  TextView log,status; LinearLayout devices; Button b18,b19,b32,bRead; boolean ready=false,auto=false,busy=false; String pending=null; int rxn=0;
  final StringBuilder diag=new StringBuilder(); final ArrayDeque<Op> q=new ArrayDeque<>();
  abstract static class Op { final String n; Op(String n){this.n=n;} abstract boolean go(BluetoothGatt g); }

  @Override public void onCreate(Bundle b){ super.onCreate(b); BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); adapter=bm.getAdapter(); ui(); perms(); }
  TextView t(String s,int z){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);return v;}
  Button cmd(String title,String label,byte[] frame){Button b=new Button(this);b.setText(title);b.setEnabled(false);b.setOnClickListener(v->confirm(label,frame));return b;}
  void ui(){
    ScrollView sv=new ScrollView(this); LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(28,28,28,28);sv.addView(r);
    r.addView(t("KQi LAB v0.7",28)); r.addView(t("18 / 19 / 32 km/h calibration build",14));
    status=t("Status: ready",16);status.setPadding(0,12,0,12);r.addView(status);
    r.addView(t("Correction from the fresh official-NIU capture: 8E 9A… is 18 km/h, EF 2A… is 19 km/h, and 4F D9… is 32 km/h. All three are exact official 0x22 frames; v0.7 generates no guessed speed writes.",14));
    Button scan=new Button(this);scan.setText("SCAN + AUTO-CONNECT NIU KQI");scan.setOnClickListener(v->scan());r.addView(scan);
    b18=cmd("SET 18 KM/H — OFFICIAL","18 km/h",S18);r.addView(b18);
    bRead=new Button(this);bRead.setText("READ CURRENT DYNAMIC CONFIG — 0x21");bRead.setEnabled(false);bRead.setOnClickListener(v->readConfig());r.addView(bRead);
    b19=cmd("SET 19 KM/H — OFFICIAL","19 km/h",S19);r.addView(b19);
    b32=cmd("SET 32 KM/H — OFFICIAL","32 km/h",S32);r.addView(b32);
    r.addView(t("NEXT CAPTURE: SET 18 → READ → SET 19 → READ → SET 32 → READ. The 19 km/h A1 response is the new target.",15));
    Button share=new Button(this);share.setText("SHARE DIAGNOSTICS");share.setOnClickListener(v->share());r.addView(share);
    TextView found=t("DISCOVERED DEVICES",20);found.setPadding(0,18,0,6);r.addView(found);devices=new LinearLayout(this);devices.setOrientation(LinearLayout.VERTICAL);r.addView(devices);
    TextView lg=t("PACKET LOG",20);lg.setPadding(0,18,0,6);r.addView(lg);log=t("Ready.\n",12);log.setTextIsSelectable(true);r.addView(log);setContentView(sv);
  }
  void perms(){if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED))requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},REQ);}
  boolean ok(){return Build.VERSION.SDK_INT<31||(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);}
  void scan(){if(!ok()){perms();return;}if(adapter==null)return;if(!adapter.isEnabled()){startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));return;}disc();scanner=adapter.getBluetoothLeScanner();devices.removeAllViews();diag.setLength(0);auto=false;rxn=0;status.setText("Status: scanning");a("Starting scan");scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),sc);new Handler(Looper.getMainLooper()).postDelayed(()->{try{scanner.stopScan(sc);}catch(Exception e){}},12000);}
  final ScanCallback sc=new ScanCallback(){@Override public void onScanResult(int c,ScanResult s){BluetoothDevice d=s.getDevice();String n="Unknown";try{if(d.getName()!=null)n=d.getName();}catch(Exception e){}boolean k=n.toLowerCase(Locale.US).contains("niu")||n.toLowerCase(Locale.US).contains("kqi");Button b=new Button(MainActivity7.this);b.setText((k?"★ ":"")+n+"\n"+d.getAddress()+" RSSI "+s.getRssi());b.setOnClickListener(v->connect(d));devices.addView(b);diag.append("SCAN ").append(n).append(" ").append(d.getAddress()).append(" RSSI=").append(s.getRssi()).append("\n");if(k&&!auto){auto=true;new Handler(Looper.getMainLooper()).postDelayed(()->connect(d),250);}}};
  void connect(BluetoothDevice d){if(!ok())return;try{if(scanner!=null)scanner.stopScan(sc);}catch(Exception e){}disc();a("Connecting "+d.getAddress());gatt=d.connectGatt(this,false,cb,BluetoothDevice.TRANSPORT_LE);}
  void disc(){q.clear();busy=false;ready=false;pending=null;buttons();if(gatt!=null){try{gatt.disconnect();}catch(Exception e){}try{gatt.close();}catch(Exception e){}gatt=null;}rx=null;tx=null;}
  final BluetoothGattCallback cb=new BluetoothGattCallback(){
    @Override public void onConnectionStateChange(BluetoothGatt g,int st,int state){a("STATE "+state+" status="+st);if(state==BluetoothProfile.STATE_CONNECTED)g.discoverServices();else{ready=false;buttons();}}
    @Override public void onServicesDiscovered(BluetoothGatt g,int st){a("SERVICES status="+st);for(BluetoothGattService s:g.getServices())for(BluetoothGattCharacteristic c:s.getCharacteristics()){diag.append("CHAR ").append(c.getUuid()).append(" props=0x").append(Integer.toHexString(c.getProperties())).append("\n");if(RX.equals(c.getUuid()))rx=c;if(TX.equals(c.getUuid()))tx=c;}if(rx!=null)notifyOn(g,rx);start(g);}
    @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int st){a("DESC_WRITE "+d.getUuid()+" status="+st);done(g);}
    @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int st){a("WRITE_DONE "+c.getUuid()+" status="+st);done(g);}
    @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){got(v);}
    @SuppressWarnings("deprecation") @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33)got(c.getValue());}
  };
  void got(byte[] v){rxn++;String s="RX#"+rxn+" "+hex(v)+" checksum="+(sum(v)?"OK":"BAD");a(s);diag.append(s).append("\n");if(v!=null&&v.length>1&&(v[1]&255)==0xA1)diag.append("DYNAMIC_A1 ").append(hex(v)).append("\n");if(pending!=null&&Arrays.equals(v,ACK)){a("ACK VERIFIED "+pending);diag.append("ACK_VERIFIED ").append(pending).append("\n");pending=null;}}
  void notifyOn(BluetoothGatt g,BluetoothGattCharacteristic c){boolean l=g.setCharacteristicNotification(c,true);a("NOTIFY_LOCAL "+l);BluetoothGattDescriptor d=c.getDescriptor(CCCD);if(d==null)return;q.add(new Op("notify"){@SuppressWarnings("deprecation")boolean go(BluetoothGatt x){d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);return x.writeDescriptor(d);}});}
  void confirm(String label,byte[] f){if(!ready)return;new AlertDialog.Builder(this).setTitle("Send official "+label+" frame?").setMessage("Keep the scooter stationary. This is an exact frame captured from the official NIU app.").setNegativeButton("Cancel",null).setPositiveButton("Send",(d,w)->write(label,f)).show();}
  void write(String label,byte[] f){if(!sum(f)){a("Checksum refused");return;}pending=label;byte[] x=Arrays.copyOf(f,f.length);a("TX_REPLAY "+label+" "+hex(x));diag.append("TX_REPLAY ").append(label).append(" ").append(hex(x)).append("\n");q.add(new Op("write "+label){@SuppressWarnings("deprecation")boolean go(BluetoothGatt g){tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);tx.setValue(x);return g.writeCharacteristic(tx);}});start(gatt);}
  void readConfig(){if(!ready)return;byte[] x=Arrays.copyOf(READ,READ.length);a("TX_QUERY 0x21 "+hex(x));diag.append("TX_QUERY dynamic_0x21 ").append(hex(x)).append("\n");q.add(new Op("read-config"){@SuppressWarnings("deprecation")boolean go(BluetoothGatt g){tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);tx.setValue(x);return g.writeCharacteristic(tx);}});start(gatt);}
  synchronized void start(BluetoothGatt g){if(busy)return;while(!q.isEmpty()){Op o=q.removeFirst();busy=true;a("OP "+o.n);if(o.go(g))return;busy=false;}ready=rx!=null&&tx!=null&&gatt!=null;buttons();a("READY="+ready);}
  synchronized void done(BluetoothGatt g){busy=false;new Handler(Looper.getMainLooper()).postDelayed(()->start(g),60);}
  void buttons(){runOnUiThread(()->{if(b18!=null)b18.setEnabled(ready);if(b19!=null)b19.setEnabled(ready);if(b32!=null)b32.setEnabled(ready);if(bRead!=null)bRead.setEnabled(ready);});}
  static boolean sum(byte[] f){if(f==null||f.length<2)return false;int s=0;for(int i=0;i<f.length-1;i++)s=(s+(f[i]&255))&255;return s==(f[f.length-1]&255);}
  static byte[] h(String s){String[] p=s.split(" ");byte[] b=new byte[p.length];for(int i=0;i<p.length;i++)b[i]=(byte)Integer.parseInt(p[i],16);return b;}
  static String hex(byte[] b){if(b==null)return"";StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02X ",x));return s.toString().trim();}
  void a(String s){runOnUiThread(()->log.append(new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+"  "+s+"\n"));}
  void share(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_SUBJECT,"KQi Lab v0.7 diagnostics");i.putExtra(Intent.EXTRA_TEXT,"KQi Lab v0.7\n"+diag+"\nLOG\n"+log.getText());startActivity(Intent.createChooser(i,"Share diagnostics"));}
  @Override protected void onDestroy(){disc();super.onDestroy();}
}
