package com.akasha.kqilab;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_BT = 42;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private LinearLayout deviceList;
    private TextView log;
    private final StringBuilder diagnostic = new StringBuilder();
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private BluetoothGatt gatt;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        BluetoothManager bm = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = bm.getAdapter();
        buildUi();
        ensurePermissions();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28,28,28,28);
        scroll.addView(root);
        root.addView(text("KQi LAB", 28));
        root.addView(text("NIU KQi 200 BLE diagnostics + performance research", 14));
        TextView warning = text("Performance mode does not guess or force unknown speed bytes. First capture the exact KQi 200 protocol; preserve BMS, thermal, current and braking protections.", 14);
        warning.setPadding(0,16,0,16); root.addView(warning);
        Button scanBtn = new Button(this); scanBtn.setText("SCAN FOR KQI / NIU"); scanBtn.setOnClickListener(v -> startScan()); root.addView(scanBtn);
        Button exportBtn = new Button(this); exportBtn.setText("SHARE DIAGNOSTICS"); exportBtn.setOnClickListener(v -> shareDiagnostics()); root.addView(exportBtn);
        TextView perf = text("PERFORMANCE RESEARCH", 20); perf.setPadding(0,22,0,8); root.addView(perf);
        root.addView(text("Goal: identify the genuine max-speed setting used by this firmware, then raise only that verified ceiling as far as the stock hardware can responsibly sustain. No 40-mph hard-code, no current-limit bypass, no cross-flashing.", 14));
        TextView found = text("DISCOVERED DEVICES", 20); found.setPadding(0,22,0,8); root.addView(found);
        deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); root.addView(deviceList);
        TextView lg = text("GATT / PACKET LOG", 20); lg.setPadding(0,22,0,8); root.addView(lg);
        log = text("Ready.\n", 12); log.setTextIsSelectable(true); root.addView(log);
        setContentView(scroll);
    }

    private TextView text(String s, int sp) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); return t; }
    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED))
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
    }
    private boolean btOk() { return Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED); }

    private void startScan() {
        if (!btOk()) { ensurePermissions(); return; }
        if (adapter == null) { append("Bluetooth unavailable"); return; }
        if (!adapter.isEnabled()) { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return; }
        scanner = adapter.getBluetoothLeScanner(); devices.clear(); deviceList.removeAllViews(); diagnostic.setLength(0);
        append("Starting BLE scan...");
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        new Handler(Looper.getMainLooper()).postDelayed(() -> { try { scanner.stopScan(scanCallback); append("Scan stopped"); } catch(Exception ignored){} }, 12000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int cb, ScanResult r) {
            BluetoothDevice d = r.getDevice(); String addr = d.getAddress(); if (devices.containsKey(addr)) return; devices.put(addr,d);
            String name = "Unknown"; try { if (d.getName()!=null) name=d.getName(); } catch(SecurityException ignored){}
            boolean likely = name.toLowerCase(Locale.US).contains("niu") || name.toLowerCase(Locale.US).contains("kqi");
            Button b = new Button(MainActivity.this); b.setText((likely ? "★ " : "") + name + "\n" + addr + "  RSSI " + r.getRssi()); b.setOnClickListener(v -> connect(d)); deviceList.addView(b);
            diagnostic.append("SCAN ").append(name).append(" ").append(addr).append(" RSSI=").append(r.getRssi()).append("\n");
        }
        @Override public void onScanFailed(int e) { append("Scan failed: "+e); }
    };

    private void connect(BluetoothDevice d) {
        if (!btOk()) return; try { if (scanner!=null) scanner.stopScan(scanCallback); } catch(Exception ignored){}
        append("Connecting to "+d.getAddress()); gatt = d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) { append("Connection state="+state+" status="+status); if (state==BluetoothProfile.STATE_CONNECTED) try { g.discoverServices(); } catch(SecurityException e){ append(e.toString()); } }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            append("Services discovered status="+status);
            for (BluetoothGattService s: g.getServices()) {
                append("SERVICE "+s.getUuid()); diagnostic.append("SERVICE ").append(s.getUuid()).append("\n");
                for (BluetoothGattCharacteristic c: s.getCharacteristics()) {
                    int p=c.getProperties(); append("  CHAR "+c.getUuid()+" props=0x"+Integer.toHexString(p)); diagnostic.append("CHAR ").append(c.getUuid()).append(" props=0x").append(Integer.toHexString(p)).append("\n");
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0 || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0) enableNotify(g,c);
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ)!=0) try { g.readCharacteristic(c); } catch(SecurityException ignored){}
                }
            }
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) { append("READ "+c.getUuid()+" "+hex(value)+" status="+status); diagnostic.append("READ ").append(c.getUuid()).append(" ").append(hex(value)).append("\n"); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) { append("RX "+c.getUuid()+" "+hex(value)); diagnostic.append("RX ").append(c.getUuid()).append(" ").append(hex(value)).append("\n"); }
    };

    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try { g.setCharacteristicNotification(c,true); BluetoothGattDescriptor d=c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")); if(d!=null) { byte[] v=((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0)?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d,v); } } catch(Exception e){ append("Notify setup: "+e.getMessage()); }
    }
    private String hex(byte[] b) { if(b==null) return ""; StringBuilder s=new StringBuilder(); for(byte x:b) s.append(String.format(Locale.US,"%02X ",x)); return s.toString().trim(); }
    private void append(String s) { runOnUiThread(() -> log.append(new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+"  "+s+"\n")); }
    private void shareDiagnostics() { Intent i=new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_SUBJECT,"KQi Lab diagnostics"); i.putExtra(Intent.EXTRA_TEXT, "KQi Lab v0.2\n"+diagnostic+"\nLOG\n"+log.getText()); startActivity(Intent.createChooser(i,"Share diagnostics")); }
    @Override protected void onDestroy() { super.onDestroy(); try { if(gatt!=null) gatt.close(); } catch(Exception ignored){} }
}
