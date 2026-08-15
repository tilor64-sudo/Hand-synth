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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final int REQ_BT = 42;
    private static final UUID NIU_RX = UUID.fromString("8ec94e31-f315-4f60-9fb8-838830daea50");
    private static final UUID NIU_TX = UUID.fromString("8ec94e32-f315-4f60-9fb8-838830daea50");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Captured from the official NIU 5.12.2 app on this KQi 200.
    private static final byte[] DYNAMIC_READ = hexBytes("01 21 00 B5 D4 C0 99 F0 50 6C 4A 7C 5B 34 B0 0C BF 13 D4 67");
    private static final byte[] SPEED_19 = hexBytes("01 22 00 8E 9A 94 F6 34 0A 29 A7 58 F8 72 4D FF CC 73 EB 1B");
    private static final byte[] SPEED_32 = hexBytes("01 22 00 4F D9 BB DB 2D 06 52 1A F4 C8 A8 8D 1B 59 87 5F CB");
    private static final byte[] SPEED_ACK = hexBytes("01 A2 00 45 78 6A AF C7 E4 50 22 F3 EC 3D 55 C9 1D 80 B5 22");

    // Deterministic Dynamic Mode A1 responses captured at known official settings.
    private static final byte[] DYNAMIC_A1_19 = hexBytes("01 A1 00 07 89 0C 3B 57 2A 04 19 A8 4D B9 4C 1B BF DE 36 FF");
    private static final byte[] DYNAMIC_A1_32 = hexBytes("01 A1 00 6C 5E BB 1C 17 22 36 4F DC 36 8A 81 96 6E BC FA D8");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private LinearLayout deviceList;
    private TextView log;
    private TextView status;
    private TextView protocolStatus;
    private TextView aesResult;
    private EditText aesCandidateInput;
    private Button dynamicReadButton;
    private Button speed19Button;
    private Button speed32Button;

    private final StringBuilder diagnostic = new StringBuilder();
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic niuRx;
    private BluetoothGattCharacteristic niuTx;
    private int rxCount = 0;
    private boolean autoConnectTriggered = false;
    private boolean readyForCommands = false;
    private String pendingReplay = null;
    private boolean pendingDynamicRead = false;
    private int dynamicReadCount = 0;
    private byte[] previousDynamicResponse = null;
    private String lastAesAnalysis = "No AES candidate tested yet.";

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

        root.addView(text("KQi LAB v0.6", 28));
        root.addView(text("NIU KQi 200 BLE + AES protocol lab", 14));

        status = text("Status: ready", 16);
        status.setPadding(0,14,0,6);
        root.addView(status);

        protocolStatus = text("Protocol: waiting for KQi connection", 14);
        protocolStatus.setPadding(0,0,0,16);
        root.addView(protocolStatus);

        TextView finding = text("Confirmed: E32 is phone→scooter, E31 is scooter→phone. NIU frames are 20 bytes: 3-byte clear header, 16-byte encrypted block, additive checksum. XAPK analysis identifies AES/ECB/NoPadding for the 16-byte block. 0x22 sets Dynamic Mode; 0x21 reads it.", 14);
        finding.setPadding(0,8,0,16);
        root.addView(finding);

        TextView aesTitle = text("OFFLINE AES KEY LAB", 20);
        aesTitle.setPadding(0,18,0,8);
        root.addView(aesTitle);
        root.addView(text("Enter a suspected 16-character aesSecret (or 32 hex digits). Testing is local only: the candidate is never saved, logged, shared, or transmitted to the scooter. v0.6 decrypts our known 19/32 km/h SET and READBACK blocks and measures how structurally similar the plaintext becomes.", 14));

        aesCandidateInput = new EditText(this);
        aesCandidateInput.setHint("16-character aesSecret or 32 hex digits");
        aesCandidateInput.setSingleLine(true);
        aesCandidateInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(aesCandidateInput);

        Button testAesBtn = new Button(this);
        testAesBtn.setText("TEST AES CANDIDATE — OFFLINE ONLY");
        testAesBtn.setOnClickListener(v -> testAesCandidate());
        root.addView(testAesBtn);

        Button clearAesBtn = new Button(this);
        clearAesBtn.setText("CLEAR CANDIDATE");
        clearAesBtn.setOnClickListener(v -> {
            aesCandidateInput.setText("");
            aesResult.setText("AES analysis cleared.");
            lastAesAnalysis = "AES analysis cleared.";
        });
        root.addView(clearAesBtn);

        Button copyAesBtn = new Button(this);
        copyAesBtn.setText("COPY AES ANALYSIS (KEY EXCLUDED)");
        copyAesBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("KQi Lab AES analysis", lastAesAnalysis));
            Toast.makeText(this,"AES analysis copied — key excluded",Toast.LENGTH_SHORT).show();
        });
        root.addView(copyAesBtn);

        aesResult = text("No AES candidate tested yet.", 12);
        aesResult.setTextIsSelectable(true);
        aesResult.setPadding(0,8,0,18);
        root.addView(aesResult);

        Button scanBtn = new Button(this);
        scanBtn.setText("SCAN + AUTO-CONNECT NIU KQI");
        scanBtn.setOnClickListener(v -> startScan());
        root.addView(scanBtn);

        Button disconnectBtn = new Button(this);
        disconnectBtn.setText("DISCONNECT");
        disconnectBtn.setOnClickListener(v -> disconnect());
        root.addView(disconnectBtn);

        TextView queryTitle = text("OFFICIAL DYNAMIC-MODE QUERY", 20);
        queryTitle.setPadding(0,22,0,8);
        root.addView(queryTitle);
        root.addView(text("Exact 0x21 request captured from the official NIU app. It reads configuration and does not change the speed setting.", 14));

        dynamicReadButton = new Button(this);
        dynamicReadButton.setText("READ CURRENT DYNAMIC CONFIG — OFFICIAL 0x21");
        dynamicReadButton.setEnabled(false);
        dynamicReadButton.setOnClickListener(v -> queueDynamicRead());
        root.addView(dynamicReadButton);

        TextView replayTitle = text("VERIFIED OFFICIAL REPLAY", 20);
        replayTitle.setPadding(0,22,0,8);
        root.addView(replayTitle);
        root.addView(text("These 0x22 commands were captured from the official NIU app at 19 and 32 km/h and have replayed successfully with verified A2 acknowledgements. No new/guessed speed frame is generated in v0.6.", 14));

        speed19Button = new Button(this);
        speed19Button.setText("SET 19 KM/H — CAPTURED OFFICIAL");
        speed19Button.setEnabled(false);
        speed19Button.setOnClickListener(v -> confirmReplay("19 km/h", SPEED_19));
        root.addView(speed19Button);

        speed32Button = new Button(this);
        speed32Button.setText("SET 32 KM/H — CAPTURED OFFICIAL");
        speed32Button.setEnabled(false);
        speed32Button.setOnClickListener(v -> confirmReplay("32 km/h", SPEED_32));
        root.addView(speed32Button);

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

        TextView safety = text("TEST SAFETY", 20);
        safety.setPadding(0,22,0,8);
        root.addView(safety);
        root.addView(text("Keep the scooter stationary while changing settings. This build does not alter BMS limits, controller current, thermal protection, brakes, firmware, or region data. AES candidate testing is completely offline.", 14));

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

    private void testAesCandidate() {
        String candidate = aesCandidateInput.getText().toString();
        try {
            byte[] key = parseAesKey(candidate);
            byte[] set19 = aesDecryptBlock(extractCipherBlock(SPEED_19), key);
            byte[] set32 = aesDecryptBlock(extractCipherBlock(SPEED_32), key);
            byte[] read19 = aesDecryptBlock(extractCipherBlock(DYNAMIC_A1_19), key);
            byte[] read32 = aesDecryptBlock(extractCipherBlock(DYNAMIC_A1_32), key);

            int setByteDiff = byteDiffCount(set19, set32);
            int setBitDiff = bitDiffCount(set19, set32);
            int readByteDiff = byteDiffCount(read19, read32);
            int readBitDiff = bitDiffCount(read19, read32);
            String setDiff = diffFrames(set19, set32);
            String readDiff = diffFrames(read19, read32);
            String patterns = speedPatternReport(set19, set32, read19, read32);

            int structuralScore = setByteDiff + readByteDiff;
            String verdict;
            if (structuralScore <= 8) verdict = "STRONG CANDIDATE — plaintext pairs became highly similar";
            else if (structuralScore <= 16) verdict = "INTERESTING CANDIDATE — inspect plaintext/diffs";
            else if (structuralScore <= 24) verdict = "WEAK CANDIDATE";
            else verdict = "UNLIKELY KEY — both pairs still show cipher-like avalanche";

            lastAesAnalysis =
                    "KQi Lab v0.6 AES candidate analysis (candidate key intentionally excluded)\n"+
                    "Mode: AES/ECB/NoPadding\n"+
                    "Verdict: "+verdict+"\n\n"+
                    "SET 19 plaintext: "+hex(set19)+"\n"+
                    "SET 32 plaintext: "+hex(set32)+"\n"+
                    "SET difference: "+setByteDiff+"/16 bytes, "+setBitDiff+"/128 bits\n"+
                    "SET diff detail: "+setDiff+"\n\n"+
                    "READ 19 plaintext: "+hex(read19)+"\n"+
                    "READ 32 plaintext: "+hex(read32)+"\n"+
                    "READ difference: "+readByteDiff+"/16 bytes, "+readBitDiff+"/128 bits\n"+
                    "READ diff detail: "+readDiff+"\n\n"+
                    patterns;

            aesResult.setText(lastAesAnalysis);
            diagnostic.append("AES_TEST verdict=").append(verdict)
                    .append(" setByteDiff=").append(setByteDiff)
                    .append(" readByteDiff=").append(readByteDiff).append("\n");
            Toast.makeText(this, verdict, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            String msg = "AES test failed: "+e.getMessage()+"\nUse exactly 16 UTF-8 bytes or exactly 32 hexadecimal digits.";
            aesResult.setText(msg);
            lastAesAnalysis = msg;
            Toast.makeText(this,"Invalid AES candidate",Toast.LENGTH_SHORT).show();
        } finally {
            // Do not retain the candidate in the input field after testing.
            aesCandidateInput.setText("");
        }
    }

    private static byte[] parseAesKey(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.matches("(?i)^[0-9a-f]{32}$")) {
            byte[] out = new byte[16];
            for (int i=0;i<16;i++) out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
            return out;
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length != 16) throw new IllegalArgumentException("candidate must encode to exactly 16 bytes");
        return utf8;
    }

    private static byte[] extractCipherBlock(byte[] frame) {
        if (frame == null || frame.length != 20) throw new IllegalArgumentException("expected 20-byte NIU frame");
        return Arrays.copyOfRange(frame, 3, 19);
    }

    private static byte[] aesDecryptBlock(byte[] cipherBlock, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"));
        return cipher.doFinal(cipherBlock);
    }

    private static int byteDiffCount(byte[] a, byte[] b) {
        int n=Math.min(a.length,b.length), c=Math.abs(a.length-b.length);
        for(int i=0;i<n;i++) if(a[i]!=b[i]) c++;
        return c;
    }

    private static int bitDiffCount(byte[] a, byte[] b) {
        int n=Math.min(a.length,b.length), c=8*Math.abs(a.length-b.length);
        for(int i=0;i<n;i++) c += Integer.bitCount((a[i]^b[i]) & 0xff);
        return c;
    }

    private static String speedPatternReport(byte[] set19, byte[] set32, byte[] read19, byte[] read32) {
        StringBuilder s = new StringBuilder("PLAINTEXT SPEED-PATTERN SEARCH\n");
        int hits=0;
        hits += findPairPatterns(s,"SET",set19,set32);
        hits += findPairPatterns(s,"READ",read19,read32);
        if(hits==0) s.append("No direct 19→32 integer encoding found in decrypted blocks.\n");
        else s.append("Pattern hits: ").append(hits).append("\n");
        return s.toString();
    }

    private static int findPairPatterns(StringBuilder s, String label, byte[] a, byte[] b) {
        int hits=0;
        for(int i=0;i<16;i++) {
            if((a[i]&0xff)==19 && (b[i]&0xff)==32) {
                s.append(label).append(" byte[").append(i).append("] matches 19→32 directly\n"); hits++;
            }
        }
        for(int i=0;i<=14;i++) {
            int ale=(a[i]&0xff)|((a[i+1]&0xff)<<8), ble=(b[i]&0xff)|((b[i+1]&0xff)<<8);
            int abe=((a[i]&0xff)<<8)|(a[i+1]&0xff), bbe=((b[i]&0xff)<<8)|(b[i+1]&0xff);
            if(ale==19 && ble==32) { s.append(label).append(" uint16LE@").append(i).append(" matches 19→32\n"); hits++; }
            if(abe==19 && bbe==32) { s.append(label).append(" uint16BE@").append(i).append(" matches 19→32\n"); hits++; }
        }
        for(int i=0;i<=12;i++) {
            long ale=(a[i]&0xffL)|((a[i+1]&0xffL)<<8)|((a[i+2]&0xffL)<<16)|((a[i+3]&0xffL)<<24);
            long ble=(b[i]&0xffL)|((b[i+1]&0xffL)<<8)|((b[i+2]&0xffL)<<16)|((b[i+3]&0xffL)<<24);
            long abe=((a[i]&0xffL)<<24)|((a[i+1]&0xffL)<<16)|((a[i+2]&0xffL)<<8)|(a[i+3]&0xffL);
            long bbe=((b[i]&0xffL)<<24)|((b[i+1]&0xffL)<<16)|((b[i+2]&0xffL)<<8)|(b[i+3]&0xffL);
            if(ale==19 && ble==32) { s.append(label).append(" uint32LE@").append(i).append(" matches 19→32\n"); hits++; }
            if(abe==19 && bbe==32) { s.append(label).append(" uint32BE@").append(i).append(" matches 19→32\n"); hits++; }
        }
        return hits;
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
        dynamicReadCount = 0;
        previousDynamicResponse = null;
        autoConnectTriggered = false;
        readyForCommands = false;
        updateCommandButtons();
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

            if (likely && !autoConnectTriggered) {
                autoConnectTriggered = true;
                append("Auto-connect candidate: "+name+" "+addr);
                new Handler(Looper.getMainLooper()).postDelayed(() -> connect(d), 250);
            }
        }
        @Override public void onScanFailed(int e) { append("Scan failed: "+e); }
    };

    private void connect(BluetoothDevice d) {
        if (!btOk()) return;
        try { if (scanner!=null) scanner.stopScan(scanCallback); } catch(Exception ignored){}
        if (gatt != null) disconnect();
        niuRx = null;
        niuTx = null;
        readyForCommands = false;
        updateCommandButtons();
        clearOps();
        status.setText("Status: connecting to " + d.getAddress());
        append("Connecting to "+d.getAddress());
        gatt = d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnect() {
        clearOps();
        readyForCommands = false;
        pendingReplay = null;
        pendingDynamicRead = false;
        updateCommandButtons();
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
                readyForCommands = false;
                updateCommandButtons();
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

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            append("WRITE_DONE "+c.getUuid()+" status="+statusCode);
            diagnostic.append("WRITE_DONE ").append(c.getUuid()).append(" status=").append(statusCode).append("\n");
            finishOp(g);
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
        boolean checksumOk = validChecksum(value);
        String line = "RX#"+rxCount+" "+c.getUuid()+" "+hex(value)+" checksum="+(checksumOk?"OK":"BAD");
        append(line);
        diagnostic.append(line).append("\n");

        if (value != null && value.length >= 2 && (value[0] & 0xff)==0x01 && (value[1] & 0xff)==0xA1) {
            dynamicReadCount++;
            append("DYNAMIC_A1 #"+dynamicReadCount+" "+hex(value));
            diagnostic.append("DYNAMIC_A1 #").append(dynamicReadCount).append(" ").append(hex(value)).append("\n");
            if (previousDynamicResponse != null) {
                String diff = diffFrames(previousDynamicResponse, value);
                append("DYNAMIC_DIFF previous→#"+dynamicReadCount+": "+diff);
                diagnostic.append("DYNAMIC_DIFF previous_to_").append(dynamicReadCount).append(" ").append(diff).append("\n");
            } else {
                append("DYNAMIC_DIFF baseline stored");
            }
            previousDynamicResponse = Arrays.copyOf(value, value.length);
            pendingDynamicRead = false;
            runOnUiThread(() -> Toast.makeText(this,"Dynamic config response #"+dynamicReadCount+" captured",Toast.LENGTH_LONG).show());
        }

        if (pendingReplay != null) {
            String label = pendingReplay;
            if (Arrays.equals(value, SPEED_ACK)) {
                append("ACK VERIFIED for "+label);
                diagnostic.append("ACK_VERIFIED ").append(label).append("\n");
                pendingReplay = null;
                runOnUiThread(() -> Toast.makeText(this, label+" ACK verified", Toast.LENGTH_LONG).show());
            } else if (value != null && value.length >= 2 && (value[0] & 0xff)==0x01 && (value[1] & 0xff)==0xA2) {
                append("A2 response received for "+label+" but payload differs from capture");
                diagnostic.append("ACK_A2_DIFFERENT ").append(label).append(" ").append(hex(value)).append("\n");
                pendingReplay = null;
            }
        }

        runOnUiThread(() -> protocolStatus.setText("Protocol: E31 active | E32 ready="+readyForCommands+" | RX=" + rxCount + " | dynamic reads="+dynamicReadCount));
    }

    private void queueDynamicRead() {
        if (!readyForCommands || gatt == null || niuTx == null) {
            Toast.makeText(this,"Connect to the KQi first",Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingDynamicRead) {
            Toast.makeText(this,"Dynamic read already pending",Toast.LENGTH_SHORT).show();
            return;
        }
        if (!validChecksum(DYNAMIC_READ)) {
            append("REFUSED dynamic read: local checksum failed");
            return;
        }
        pendingDynamicRead = true;
        byte[] copy = Arrays.copyOf(DYNAMIC_READ, DYNAMIC_READ.length);
        append("TX_QUERY dynamic 0x21 "+hex(copy)+" checksum=OK");
        diagnostic.append("TX_QUERY dynamic_0x21 ").append(hex(copy)).append("\n");

        opQueue.addLast(new GattOp("write official dynamic read 0x21") {
            @SuppressWarnings("deprecation")
            @Override boolean start(BluetoothGatt gg) {
                niuTx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                niuTx.setValue(copy);
                try { return gg.writeCharacteristic(niuTx); }
                catch(SecurityException e) { append("Write denied: "+e); return false; }
            }
        });
        startNextOp(gatt);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (pendingDynamicRead) {
                append("DYNAMIC_READ TIMEOUT");
                diagnostic.append("DYNAMIC_READ_TIMEOUT\n");
                pendingDynamicRead = false;
            }
        }, 2500);
    }

    private void confirmReplay(String label, byte[] frame) {
        if (!readyForCommands || gatt == null || niuTx == null) {
            Toast.makeText(this,"Connect to the KQi first",Toast.LENGTH_SHORT).show();
            return;
        }
        if (!validChecksum(frame)) {
            append("REFUSED "+label+": local checksum failed");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Replay official "+label+" command?")
                .setMessage("This is an exact frame captured from the official NIU app on your KQi 200. Keep the scooter stationary with the wheel clear. No current, BMS, thermal, braking, or firmware protections are changed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (d,w) -> queueReplay(label, frame))
                .show();
    }

    private void queueReplay(String label, byte[] frame) {
        if (gatt == null || niuTx == null) return;
        pendingReplay = label;
        byte[] copy = Arrays.copyOf(frame, frame.length);
        append("TX_REPLAY "+label+" "+hex(copy)+" checksum=OK");
        diagnostic.append("TX_REPLAY ").append(label).append(" ").append(hex(copy)).append("\n");

        opQueue.addLast(new GattOp("write official "+label) {
            @SuppressWarnings("deprecation")
            @Override boolean start(BluetoothGatt gg) {
                niuTx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                niuTx.setValue(copy);
                try { return gg.writeCharacteristic(niuTx); }
                catch(SecurityException e) { append("Write denied: "+e); return false; }
            }
        });
        startNextOp(gatt);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (label.equals(pendingReplay)) {
                append("ACK TIMEOUT for "+label);
                diagnostic.append("ACK_TIMEOUT ").append(label).append("\n");
                pendingReplay = null;
            }
        }, 2500);
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
        readyForCommands = (niuRx != null && niuTx != null && gatt != null);
        updateCommandButtons();
        append("GATT_OP queue complete; commandReady="+readyForCommands);
    }

    private synchronized void finishOp(BluetoothGatt g) {
        opBusy = false;
        new Handler(Looper.getMainLooper()).postDelayed(() -> startNextOp(g), 60);
    }

    private synchronized void clearOps() {
        opQueue.clear();
        opBusy = false;
    }

    private void updateCommandButtons() {
        runOnUiThread(() -> {
            if (dynamicReadButton != null) dynamicReadButton.setEnabled(readyForCommands);
            if (speed19Button != null) speed19Button.setEnabled(readyForCommands);
            if (speed32Button != null) speed32Button.setEnabled(readyForCommands);
        });
    }

    private void mark(String label) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date());
        String line = "MARK "+label+" "+stamp;
        diagnostic.append(line).append("\n");
        append(line);
        Toast.makeText(this, label+" marked", Toast.LENGTH_SHORT).show();
    }

    private static boolean validChecksum(byte[] frame) {
        if (frame == null || frame.length < 2) return false;
        int sum = 0;
        for (int i=0;i<frame.length-1;i++) sum = (sum + (frame[i] & 0xff)) & 0xff;
        return sum == (frame[frame.length-1] & 0xff);
    }

    private static String diffFrames(byte[] a, byte[] b) {
        if (a == null || b == null) return "unavailable";
        int n = Math.min(a.length, b.length);
        StringBuilder s = new StringBuilder();
        int changes = 0;
        for (int i=0;i<n;i++) {
            if (a[i] != b[i]) {
                if (changes > 0) s.append(" | ");
                s.append("[").append(i).append("] ")
                        .append(String.format(Locale.US,"%02X",a[i] & 0xff))
                        .append("→")
                        .append(String.format(Locale.US,"%02X",b[i] & 0xff));
                changes++;
            }
        }
        if (a.length != b.length) {
            if (changes > 0) s.append(" | ");
            s.append("length ").append(a.length).append("→").append(b.length);
            changes++;
        }
        if (changes == 0) return "NO CHANGES";
        return changes+" change(s): "+s;
    }

    private static byte[] hexBytes(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i=0;i<parts.length;i++) out[i]=(byte)Integer.parseInt(parts[i],16);
        return out;
    }

    private static String hex(byte[] b) {
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
        i.putExtra(Intent.EXTRA_SUBJECT,"KQi Lab v0.6 diagnostics");
        i.putExtra(Intent.EXTRA_TEXT,
                "KQi Lab v0.6\n"+diagnostic+"\nAES ANALYSIS (candidate key excluded)\n"+lastAesAnalysis+"\nLOG\n"+log.getText());
        startActivity(Intent.createChooser(i,"Share diagnostics"));
    }

    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
