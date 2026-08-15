from pathlib import Path
import re

src = Path('kqilab/app/src/main/java/com/akasha/kqilab/MainActivity.java')
s = src.read_text()

def rep(old, new, n=None):
    global s
    if old not in s:
        raise SystemExit('missing patch anchor: ' + old[:100])
    s = s.replace(old, new, n if n is not None else -1)

# Correct the mapping established by the clean official NIU 18 -> 19 -> 32 capture.
rep('    private static final byte[] SPEED_19 = hexBytes("01 22 00 8E 9A 94 F6 34 0A 29 A7 58 F8 72 4D FF CC 73 EB 1B");',
    '    private static final byte[] SPEED_18 = hexBytes("01 22 00 8E 9A 94 F6 34 0A 29 A7 58 F8 72 4D FF CC 73 EB 1B");\n'
    '    private static final byte[] SPEED_19 = hexBytes("01 22 00 EF 2A F7 8E A2 66 E7 9F 5B 5D 55 0F 90 C9 26 11 FB");')
rep('DYNAMIC_A1_19', 'DYNAMIC_A1_18')
rep('private Button speed19Button;\n    private Button speed32Button;',
    'private Button speed18Button;\n    private Button speed19Button;\n    private Button speed32Button;\n    private Button sequenceButton;')

# Version text and explanatory copy.
s = s.replace('v0.6', 'v0.7')
s = s.replace('known 19/32 km/h SET and READBACK blocks', 'known 18/19/32 km/h SET blocks plus known 18/32 READBACK blocks')
s = s.replace('at 19 and 32 km/h', 'at 18, 19 and 32 km/h')
s = s.replace('These 0x22 commands were captured from the official NIU app while changing Dynamic Mode from 19 km/h to 32 km/h.',
              'These 0x22 commands were captured from the official NIU app at 18, 19 and 32 km/h.')

# Add the corrected 18 km/h replay button.
anchor = '''        speed19Button = new Button(this);\n        speed19Button.setText("SET 19 KM/H — CAPTURED OFFICIAL");'''
insert = '''        speed18Button = new Button(this);\n        speed18Button.setText("SET 18 KM/H — CAPTURED OFFICIAL");\n        speed18Button.setEnabled(false);\n        speed18Button.setOnClickListener(v -> confirmReplay("18 km/h", SPEED_18));\n        root.addView(speed18Button);\n\n        speed19Button = new Button(this);\n        speed19Button.setText("SET 19 KM/H — CAPTURED OFFICIAL");'''
rep(anchor, insert)

# Add one-tap three-point research sequence UI after the 32 km/h button.
anchor = '''        speed32Button.setOnClickListener(v -> confirmReplay("32 km/h", SPEED_32));\n        root.addView(speed32Button);'''
insert = anchor + '''\n\n        TextView sequenceTitle = text("3-POINT RESEARCH SEQUENCE", 20);\n        sequenceTitle.setPadding(0,22,0,8);\n        root.addView(sequenceTitle);\n        root.addView(text("One tap performs 18 km/h → readback → 19 km/h → readback → 32 km/h → readback. Every speed write is an exact captured official NIU frame; no guessed value is sent.", 14));\n        sequenceButton = new Button(this);\n        sequenceButton.setText("RUN 18 → 19 → 32 RESEARCH SEQUENCE");\n        sequenceButton.setEnabled(false);\n        sequenceButton.setOnClickListener(v -> confirmThreePointSequence());\n        root.addView(sequenceButton);'''
rep(anchor, insert, 1)

# Replace AES test with a corrected three-point SET test plus known 18/32 readbacks.
pattern = re.compile(r'    private void testAesCandidate\(\) \{.*?\n    private static byte\[\] parseAesKey', re.S)
new_method = r'''    private void testAesCandidate() {
        String candidate = aesCandidateInput.getText().toString();
        try {
            byte[] key = parseAesKey(candidate);
            byte[] set18 = aesDecryptBlock(extractCipherBlock(SPEED_18), key);
            byte[] set19 = aesDecryptBlock(extractCipherBlock(SPEED_19), key);
            byte[] set32 = aesDecryptBlock(extractCipherBlock(SPEED_32), key);
            byte[] read18 = aesDecryptBlock(extractCipherBlock(DYNAMIC_A1_18), key);
            byte[] read32 = aesDecryptBlock(extractCipherBlock(DYNAMIC_A1_32), key);

            int d1819 = byteDiffCount(set18, set19);
            int d1932 = byteDiffCount(set19, set32);
            int dRead = byteDiffCount(read18, read32);
            int structuralScore = d1819 + d1932 + dRead;
            String verdict;
            if (structuralScore <= 10) verdict = "STRONG CANDIDATE — plaintext became highly structured";
            else if (structuralScore <= 20) verdict = "INTERESTING CANDIDATE — inspect plaintext";
            else if (structuralScore <= 32) verdict = "WEAK CANDIDATE";
            else verdict = "UNLIKELY KEY — plaintext still shows cipher-like avalanche";

            StringBuilder patterns = new StringBuilder("PLAINTEXT SPEED-PATTERN SEARCH\n");
            int hits = 0;
            for (int i=0; i<16; i++) {
                if ((set18[i]&0xff)==18 && (set19[i]&0xff)==19 && (set32[i]&0xff)==32) {
                    patterns.append("SET byte[").append(i).append("] matches 18→19→32 directly\n");
                    hits++;
                }
            }
            for (int i=0; i<=14; i++) {
                int a=(set18[i]&0xff)|((set18[i+1]&0xff)<<8);
                int b=(set19[i]&0xff)|((set19[i+1]&0xff)<<8);
                int c=(set32[i]&0xff)|((set32[i+1]&0xff)<<8);
                if (a==18 && b==19 && c==32) {
                    patterns.append("SET uint16LE@").append(i).append(" matches 18→19→32\n");
                    hits++;
                }
            }
            if (hits==0) patterns.append("No direct 18→19→32 integer field found.\n");
            else patterns.append("Pattern hits: ").append(hits).append("\n");

            lastAesAnalysis =
                    "KQi Lab v0.7 AES candidate analysis (candidate key intentionally excluded)\n"+
                    "Mode: AES/ECB/NoPadding\n"+
                    "Verdict: "+verdict+"\n\n"+
                    "SET 18 plaintext: "+hex(set18)+"\n"+
                    "SET 19 plaintext: "+hex(set19)+"\n"+
                    "SET 32 plaintext: "+hex(set32)+"\n"+
                    "SET 18→19 difference: "+d1819+"/16 bytes, "+bitDiffCount(set18,set19)+"/128 bits\n"+
                    "SET 19→32 difference: "+d1932+"/16 bytes, "+bitDiffCount(set19,set32)+"/128 bits\n\n"+
                    "READ 18 plaintext: "+hex(read18)+"\n"+
                    "READ 32 plaintext: "+hex(read32)+"\n"+
                    "READ 18→32 difference: "+dRead+"/16 bytes, "+bitDiffCount(read18,read32)+"/128 bits\n\n"+
                    patterns;

            aesResult.setText(lastAesAnalysis);
            diagnostic.append("AES_TEST verdict=").append(verdict)
                    .append(" set18to19=").append(d1819)
                    .append(" set19to32=").append(d1932)
                    .append(" read18to32=").append(dRead).append("\n");
            Toast.makeText(this, verdict, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            String msg = "AES test failed: "+e.getMessage()+"\nUse exactly 16 UTF-8 bytes or exactly 32 hexadecimal digits.";
            aesResult.setText(msg);
            lastAesAnalysis = msg;
            Toast.makeText(this,"Invalid AES candidate",Toast.LENGTH_SHORT).show();
        } finally {
            aesCandidateInput.setText("");
        }
    }

    private static byte[] parseAesKey'''
# Use a callable replacement so backslash escapes in the Java source are preserved verbatim.
s2, n = pattern.subn(lambda m: new_method, s, count=1)
if n != 1:
    raise SystemExit('failed to replace AES test method')
s = s2

# Add a controlled timed three-point sequence. The gaps are intentionally generous compared with
# the sub-100 ms ACK/read response times observed in the HCI capture.
anchor = '    private void queueDynamicRead() {'
sequence_methods = r'''    private void confirmThreePointSequence() {
        if (!readyForCommands || gatt == null || niuTx == null) {
            Toast.makeText(this,"Connect to the KQi first",Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Run 18 → 19 → 32 research sequence?")
                .setMessage("This sends only exact official NIU frames captured from your scooter, with a readback after each setting. Keep the scooter stationary with the wheel clear.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run", (d,w) -> runThreePointSequence())
                .show();
    }

    private void runThreePointSequence() {
        dynamicReadCount = 0;
        previousDynamicResponse = null;
        append("THREE_POINT_SEQUENCE START 18→19→32");
        diagnostic.append("THREE_POINT_SEQUENCE_START\n");
        queueReplay("18 km/h", SPEED_18);
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> sequenceRead("18 km/h"), 1200);
        h.postDelayed(() -> sequenceSet("19 km/h", SPEED_19), 2700);
        h.postDelayed(() -> sequenceRead("19 km/h"), 3900);
        h.postDelayed(() -> sequenceSet("32 km/h", SPEED_32), 5400);
        h.postDelayed(() -> sequenceRead("32 km/h"), 6600);
        h.postDelayed(() -> {
            String line = "THREE_POINT_SEQUENCE END dynamicReads="+dynamicReadCount;
            append(line);
            diagnostic.append(line).append("\n");
            Toast.makeText(this, dynamicReadCount==3 ? "3-point capture complete — share diagnostics" : "Sequence ended; check log", Toast.LENGTH_LONG).show();
        }, 8200);
    }

    private void sequenceSet(String label, byte[] frame) {
        if (pendingReplay != null || pendingDynamicRead) {
            append("SEQUENCE_ABORT busy before "+label);
            diagnostic.append("SEQUENCE_ABORT busy_before_").append(label).append("\n");
            return;
        }
        queueReplay(label, frame);
    }

    private void sequenceRead(String afterLabel) {
        if (pendingReplay != null || pendingDynamicRead) {
            append("SEQUENCE_ABORT busy before read after "+afterLabel);
            diagnostic.append("SEQUENCE_ABORT busy_before_read_after_").append(afterLabel).append("\n");
            return;
        }
        append("SEQUENCE_READ after "+afterLabel);
        diagnostic.append("SEQUENCE_READ after=").append(afterLabel).append("\n");
        queueDynamicRead();
    }

'''
rep(anchor, sequence_methods + anchor, 1)

# Enable the new buttons when the GATT command channel is ready.
rep('            if (speed19Button != null) speed19Button.setEnabled(readyForCommands);',
    '            if (speed18Button != null) speed18Button.setEnabled(readyForCommands);\n            if (speed19Button != null) speed19Button.setEnabled(readyForCommands);')
rep('            if (speed32Button != null) speed32Button.setEnabled(readyForCommands);',
    '            if (speed32Button != null) speed32Button.setEnabled(readyForCommands);\n            if (sequenceButton != null) sequenceButton.setEnabled(readyForCommands);')

src.write_text(s)

# Build metadata is patched only in the CI workspace; repository source remains a clean v0.6 baseline.
gradle = Path('kqilab/app/build.gradle')
g = gradle.read_text()
g = g.replace('versionCode 6', 'versionCode 7').replace("versionName '0.6.0'", "versionName '0.7.0'")
if "versionCode 7" not in g or "versionName '0.7.0'" not in g:
    raise SystemExit('failed to patch v0.7 build metadata')
gradle.write_text(g)

print('Prepared KQi Lab v0.7 research build')
