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
    private static final UUID NIU_SERVICE = UUID.fromString("8ec94e30-f315-4f60-9fb8-838830daea50");
    private static final UUID NIU_RX = UUID.fromString("8ec94e31-f315-4f60-9fb8-838830daea50");
    private static final UUID NIU_TX = UUID.fromString("8ec94e32-f315-4f60-9fb8-838830daea50");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private LinearLayout deviceList;
    private TextView log;
    private TextView status;
    private TextView protocolStatus;
    private final StringBuilder diagnostic = new StringBuilder();
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic niuRx;
    private BluetoothGattCharacteristic niuTx;
    private int rxCount = 0;

    private abstract static class GattOp {
        final String label;
        GattOp(String label) { this.label = label; }
        abstract boolean start(BluetoothGatt gatt);
    }
    private final ArrayDeque<GattOp> opQueue = new ArrayDeque<>();
    private boolean opBusy = false;

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

        root.addView(text("KQi LAB v0.3", 28));
        root.addView(text("NIU KQi BLE protocol capture + performance research", 14));

        status = text("Status: ready", 16);
        status.setPadding(0,14,0,6);
        root.addView(status);

        protocolStatus = text("Protocol: waiting for KQi connection", 14);
        protocolStatus.setPadding(0,0,0,16);
        root.addView(protocolStatus);

        TextView warning = text("This build does NOT transmit guessed speed commands. It first identifies the exact KQi protocol while preserving BMS, thermal, current and braking protections.", 14);
        warning.setPadding(0,8,0,16);
        root.addView(warning);

        Button scanBtn = new Button(this);
        scanBtn.setText("SCAN FOR KQI / NIU");
        scanBtn.setOnClickListener(v -> startScan());
        root.addView(scanBtn);

        Button disconnectBtn = new Button(this);
        disconnectBtn.setText("DISCONNECT");
        disconnectBtn.setOnClickListener(v -> disconnect());
        root.addView(disconnectBtn);

        Button markA = new Button(this);
        markA.setText("MARK CAPTURE A");
        markA.setOnClickListener(v -> mark("CAPTURE_A"));
        root.addView(markA);

        Button markB = new Button(this);
        markB.setText("MARK CAPTURE B");
        markB.setOnClickListener(v -> mark("CAPTURE_B"));
        root.addView(markB);

        Button exportBtn = new Button(this);
        exportBtn.setText("SHARE DIAGNOSTICS");
        exportBtn.setOnClickListener(v -> shareDiagnostics());
        root.addView(exportBtn);

        TextView next = text("NEXT PROTOCOL STEP", 20);
        next.setPadding(0,22,0,8);
        root.addView(next);
        root.addView(text("The KQi exposes E31 as READ+NOTIFY and E32 as READ+WRITE+WRITE_NO_RESPONSE. v0.3 prioritizes a proper E31 CCCD subscription before any reads. If the scooter only replies after commands, capture the official NIU app with Android Bluetooth HCI snoop logging while changing max speed between two known values; that lets us identify the exact E32 packet without guessing.", 14));

        TextView perf = text("PERFORMANCE RESEARCH", 20);
        perf.setPadding(0,22,0,8);
        root.addView(perf);
        root.addView(text("Goal: identify the genuine max-speed setting used by this KQi 200 firmware, then raise only that verified ceiling as far as the stock motor/controller/battery can support. No arbitrary 40-mph byte, no current-limit bypass, no cross-flashing.", 14));

        TextView found = text("DISCOVERED DEVICES", 20);
        found.setPadding(0,22,0,8);
        root.addView(found);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);

        TextView lg = text("GATT / PACKET LOG", 20);
        lg.setPadding(0,22,0,8);
        root.addView(lg);
        log = text("Ready.\n", 12);
        log.setTextIsSelectable(true);
        root.addView(log);
        setContentView(scroll);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        return t;
    }

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 31 &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    private boolean btOk() {
        return Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);
    }

    private void startScan() {
        if (!btOk()) { ensurePermissions(); return; }
        if (adapter == null) { append("Bluetooth unavailable"); return; }
        if (!adapter.isEnabled()) { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return; }
        disconnect();
        scanner = adapter.getBluetoothLeScanner();
        devices.clear();
        deviceList.removeAllViews();
        diagnostic.setLength(0);
        rxCount = 0;
        status.setText("Status: scanning...");
        protocolStatus.setText("Protocol: waiting for KQi connection");
        append("Starting BLE scan...");
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { if (scanner != null) scanner.stopScan(scanCallback); append("Scan stopped"); } catch(Exception ignored){}
        }, 12000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int cb, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String addr = d.getAddress();
            if (devices.containsKey(addr)) return;
            devices.put(addr,d);
            String name = "Unknown";
            try { if (d.getName()!=null) name=d.getName(); } catch(SecurityException ignored){}
            boolean likely = name.toLowerCase(Locale.US).contains("niu") || name.toLowerCase(Locale.US).contains("kqi");
            Button b = new Button(MainActivity.this);
            b.setText((likely ? "★ " : "") + name + "\n" + addr + "  RSSI " + r.getRssi());
            b.setOnClickListener(v -> { b.setEnabled(false); connect(d); });
            deviceList.addView(b);
            diagnostic.append("SCAN ").append(name).append(" ").append(addr).append(" RSSI=").append(r.getRssi()).append("\n");
        }
        @Override public void onScanFailed(int e) { append("Scan failed: "+e); }
    };

    private void connect(BluetoothDevice d) {
        if (!btOk()) return;
        try { if (scanner!=null) scanner.stopScan(scanCallback); } catch(Exception ignored){}
        if (gatt != null) disconnect();
        niuRx = null;
        niuTx = null;
        clearOps();
        status.setText("Status: connecting to " + d.getAddress());
        append("Connecting to "+d.getAddress());
        gatt = d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnect() {
        clearOps();
        if (gatt != null) {
            try { gatt.disconnect(); } catch(Exception ignored){}
            try { gatt.close(); } catch(Exception ignored){}
            gatt = null;
        }
        niuRx = null;
        niuTx = null;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int state) {
            append("Connection state="+state+" status="+statusCode);
            if (state==BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> status.setText("Status: connected; discovering services"));
                try { g.discoverServices(); } catch(SecurityException e){ append(e.toString()); }
            } else if (state==BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> status.setText("Status: disconnected"));
                clearOps();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            append("Services discovered status="+statusCode);
            clearOps();
            ArrayList<BluetoothGattCharacteristic> reads = new ArrayList<>();

            for (BluetoothGattService s: g.getServices()) {
                append("SERVICE "+s.getUuid());
                diagnostic.append("SERVICE ").append(s.getUuid()).append("\n");
                for (BluetoothGattCharacteristic c: s.getCharacteristics()) {
                    int p=c.getProperties();
                    append("  CHAR "+c.getUuid()+" props=0x"+Integer.toHexString(p));
                    diagnostic.append("CHAR ").append(c.getUuid()).append(" props=0x").append(Integer.toHexString(p)).append("\n");
                    for (BluetoothGattDescriptor d : c.getDescriptors()) {
                        append("    DESC "+d.getUuid());
                        diagnostic.append("DESC ").append(c.getUuid()).append(" ").append(d.getUuid()).append("\n");
                    }
                    if (NIU_RX.equals(c.getUuid())) niuRx = c;
                    if (NIU_TX.equals(c.getUuid())) niuTx = c;
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ)!=0) reads.add(c);
                }
            }

            if (niuRx != null) queueNotify(g, niuRx, true);
            for (BluetoothGattCharacteristic c : reads) queueRead(c);
            runOnUiThread(() -> {
                String rx = niuRx != null ? "E31 RX/notify found" : "E31 missing";
                String tx = niuTx != null ? "E32 TX/write found" : "E32 missing";
                protocolStatus.setText("Protocol: " + rx + " | " + tx);
                status.setText("Status: NIU services discovered");
            });
            startNextOp(g);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int statusCode) {
            append("DESC_WRITE "+d.getUuid()+" status="+statusCode+" value="+hex(d.getValue()));
            diagnostic.append("DESC_WRITE ").append(d.getUuid()).append(" status=").append(statusCode).append("\n");
            finishOp(g);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int statusCode) {
            append("READ "+c.getUuid()+" "+hex(value)+" status="+statusCode);
            diagnostic.append("READ ").append(c.getUuid()).append(" ").append(hex(value)).append(" status=").append(statusCode).append("\n");
            finishOp(g);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value = c.getValue();
                append("READ "+c.getUuid()+" "+hex(value)+" status="+statusCode);
                diagnostic.append("READ ").append(c.getUuid()).append(" ").append(hex(value)).append(" status=").append(statusCode).append("\n");
                finishOp(g);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            recordRx(c, value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) recordRx(c, c.getValue());
        }
    };

    private void recordRx(BluetoothGattCharacteristic c, byte[] value) {
        rxCount++;
        String line = "RX#"+rxCount+" "+c.getUuid()+" "+hex(value);
        append(line);
        diagnostic.append(line).append("\n");
        runOnUiThread(() -> protocolStatus.setText("Protocol: E31 RX/notify active | E32 TX/write " + (niuTx != null ? "found" : "missing") + " | packets=" + rxCount));
    }

    private void queueNotify(BluetoothGatt g, BluetoothGattCharacteristic c, boolean priority) {
        try {
            boolean local = g.setCharacteristicNotification(c,true);
            append("NOTIFY_LOCAL "+c.getUuid()+" result="+local);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) {
                append("CCCD missing for "+c.getUuid());
                diagnostic.append("CCCD_MISSING ").append(c.getUuid()).append("\n");
                return;
            }
            byte[] value = ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0)
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            GattOp op = new GattOp("enable notify "+c.getUuid()) {
                @SuppressWarnings("deprecation")
                @Override boolean start(BluetoothGatt gg) {
                    d.setValue(value);
                    return gg.writeDescriptor(d);
                }
            };
            if (priority) opQueue.addFirst(op); else opQueue.addLast(op);
        } catch(Exception e) {
            append("Notify setup: "+e.getMessage());
        }
    }

    private void queueRead(BluetoothGattCharacteristic c) {
        opQueue.addLast(new GattOp("read "+c.getUuid()) {
            @Override boolean start(BluetoothGatt gg) {
                try { return gg.readCharacteristic(c); }
                catch(SecurityException e) { append("Read denied: "+e); return false; }
            }
        });
    }

    private synchronized void startNextOp(BluetoothGatt g) {
        if (opBusy) return;
        while (!opQueue.isEmpty()) {
            GattOp op = opQueue.removeFirst();
            opBusy = true;
            append("GATT_OP start: "+op.label);
            boolean started = false;
            try { started = op.start(g); } catch(Exception e) { append("GATT_OP error: "+e); }
            if (started) return;
            append("GATT_OP not started: "+op.label);
            opBusy = false;
        }
        append("GATT_OP queue complete");
    }

    private synchronized void finishOp(BluetoothGatt g) {
        opBusy = false;
        new Handler(Looper.getMainLooper()).postDelayed(() -> startNextOp(g), 60);
    }

    private synchronized void clearOps() {
        opQueue.clear();
        opBusy = false;
    }

    private void mark(String label) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date());
        String line = "MARK "+label+" "+stamp;
        diagnostic.append(line).append("\n");
        append(line);
        Toast.makeText(this, label+" marked", Toast.LENGTH_SHORT).show();
    }

    private String hex(byte[] b) {
        if(b==null) return "";
        StringBuilder s=new StringBuilder();
        for(byte x:b) s.append(String.format(Locale.US,"%02X ",x));
        return s.toString().trim();
    }

    private void append(String s) {
        runOnUiThread(() -> log.append(new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+"  "+s+"\n"));
    }

    private void shareDiagnostics() {
        Intent i=new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT,"KQi Lab v0.3 diagnostics");
        i.putExtra(Intent.EXTRA_TEXT, "KQi Lab v0.3\n"+diagnostic+"\nLOG\n"+log.getText());
        startActivity(Intent.createChooser(i,"Share diagnostics"));
    }

    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
