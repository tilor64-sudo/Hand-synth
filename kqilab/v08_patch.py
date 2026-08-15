from pathlib import Path
import re, subprocess, sys

# Build v0.8 on top of the known-good v0.7 transformation.
subprocess.run([sys.executable, 'kqilab/v07_patch.py'], check=True)

src = Path('kqilab/app/src/main/java/com/akasha/kqilab/MainActivity.java')
s = src.read_text()

def rep(old, new, n=None):
    global s
    if old not in s:
        raise SystemExit('missing v0.8 patch anchor: ' + old[:120])
    s = s.replace(old, new, n if n is not None else -1)

s = s.replace('v0.7', 'v0.8')

# Add the freshly captured deterministic 19 km/h A1 readback.
rep('    private static final byte[] DYNAMIC_A1_32 = hexBytes("01 A1 00 6C 5E BB 1C 17 22 36 4F DC 36 8A 81 96 6E BC FA D8");',
    '    private static final byte[] DYNAMIC_A1_19 = hexBytes("01 A1 00 8B F9 8C D5 2B 90 9C 68 97 EE 28 6B A0 77 C3 9E D6");\n'
    '    private static final byte[] DYNAMIC_A1_32 = hexBytes("01 A1 00 6C 5E BB 1C 17 22 36 4F DC 36 8A 81 96 6E BC FA D8");')

rep('    private Button sequenceButton;',
    '    private Button sequenceButton;\n'
    '    private Button generate40Button;\n'
    '    private Button send40Button;\n'
    '    private TextView fortyResult;\n'
    '    private byte[] validatedAesKey = null;\n'
    '    private byte[] generated40Frame = null;\n'
    '    private SpeedField validatedSpeedField = null;')

# Insert the gated 40 km/h lab UI after the AES analysis panel.
anchor = '        root.addView(aesResult);'
insert = anchor + r'''

        TextView fortyTitle = text("GATED 40 KM/H GENERATOR",20);
        fortyTitle.setPadding(0,18,0,8);
        root.addView(fortyTitle);
        root.addView(text("40 km/h is above the official 32 km/h setting. v0.8 will not invent ciphertext. A candidate aesSecret must first decrypt the known 18/19/32 SET and READBACK triplets into a low-diff plaintext structure with exactly one direct speed field. Only then can a 40 km/h frame be generated locally.",14));
        generate40Button = new Button(this);
        generate40Button.setText("GENERATE 40 KM/H FRAME — REQUIRES VERIFIED KEY");
        generate40Button.setEnabled(false);
        generate40Button.setOnClickListener(v -> generate40Frame());
        root.addView(generate40Button);
        send40Button = new Button(this);
        send40Button.setText("SEND GENERATED 40 KM/H — LOCKED");
        send40Button.setEnabled(false);
        send40Button.setOnClickListener(v -> confirmGenerated40());
        root.addView(send40Button);
        fortyResult = text("40 km/h generator locked: no verified AES key/field yet.",12);
        fortyResult.setTextIsSelectable(true);
        fortyResult.setPadding(0,8,0,18);
        root.addView(fortyResult);'''
rep(anchor, insert, 1)

# Replace AES test with strict 3-point validation. Correct AES plaintext should change only a
# small number of bytes when the only setting changed is speed, while a wrong key avalanches.
pattern = re.compile(r'    private void testAesCandidate\(\) \{.*?\n    private static byte\[\] parseAesKey', re.S)
new_test = r'''    private void testAesCandidate() {
        validatedAesKey = null;
        validatedSpeedField = null;
        generated40Frame = null;
        if (generate40Button != null) generate40Button.setEnabled(false);
        if (send40Button != null) { send40Button.setEnabled(false); send40Button.setText("SEND GENERATED 40 KM/H — LOCKED"); }
        if (fortyResult != null) fortyResult.setText("40 km/h generator locked: validating candidate...");

        String candidate = aesCandidateInput.getText().toString();
        try {
            byte[] key = parseAesKey(candidate);
            byte[] p18 = aesDecryptBlock(extractCipherBlock(SPEED_18),key);
            byte[] p19 = aesDecryptBlock(extractCipherBlock(SPEED_19),key);
            byte[] p32 = aesDecryptBlock(extractCipherBlock(SPEED_32),key);
            byte[] r18 = aesDecryptBlock(extractCipherBlock(DYNAMIC_A1_18),key);
            byte[] r19 = aesDecryptBlock(extractCipherBlock(DYNAMIC_A1_19),key);
            byte[] r32 = aesDecryptBlock(extractCipherBlock(DYNAMIC_A1_32),key);

            int sd1=byteDiffCount(p18,p19), sd2=byteDiffCount(p19,p32);
            int rd1=byteDiffCount(r18,r19), rd2=byteDiffCount(r19,r32);
            ArrayList<SpeedField> setFields=findDirectSpeedFields(p18,p19,p32);
            ArrayList<SpeedField> readFields=findDirectSpeedFields(r18,r19,r32);

            boolean lowDiff = sd1<=4 && sd2<=4 && rd1<=4 && rd2<=4;
            boolean uniqueSet = setFields.size()==1;
            boolean strong = lowDiff && uniqueSet;
            String verdict = strong ? "VERIFIED STRUCTURAL MATCH — 40 generator unlocked" :
                    "NOT VERIFIED — no generated speed write will be enabled";

            StringBuilder fs = new StringBuilder();
            fs.append("SET speed-field candidates: ").append(setFields.size()).append("\n");
            for(SpeedField f:setFields) fs.append("  ").append(f).append("\n");
            fs.append("READ speed-field candidates: ").append(readFields.size()).append("\n");
            for(SpeedField f:readFields) fs.append("  ").append(f).append("\n");

            lastAesAnalysis =
                    "KQi Lab v0.8 AES analysis (candidate key intentionally excluded)\n"+
                    "Mode: AES/ECB/NoPadding\n"+
                    "Verdict: "+verdict+"\n\n"+
                    "SET 18 plaintext: "+hex(p18)+"\n"+
                    "SET 19 plaintext: "+hex(p19)+"\n"+
                    "SET 32 plaintext: "+hex(p32)+"\n"+
                    "SET diffs 18→19="+sd1+"/16, 19→32="+sd2+"/16\n\n"+
                    "READ 18 plaintext: "+hex(r18)+"\n"+
                    "READ 19 plaintext: "+hex(r19)+"\n"+
                    "READ 32 plaintext: "+hex(r32)+"\n"+
                    "READ diffs 18→19="+rd1+"/16, 19→32="+rd2+"/16\n\n"+
                    fs;
            aesResult.setText(lastAesAnalysis);
            diagnostic.append("AES_TEST_V08 verdict=").append(strong?"VERIFIED":"NOT_VERIFIED")
                    .append(" setDiffs=").append(sd1).append(",").append(sd2)
                    .append(" readDiffs=").append(rd1).append(",").append(rd2)
                    .append(" setFields=").append(setFields.size()).append(" readFields=").append(readFields.size()).append("\n");

            if(strong){
                validatedAesKey = Arrays.copyOf(key,key.length);
                validatedSpeedField = setFields.get(0);
                generate40Button.setEnabled(true);
                fortyResult.setText("AES candidate passed strict 3-point structural validation. Speed field: "+validatedSpeedField+". Generate the 40 km/h frame locally before any BLE write.");
            } else {
                fortyResult.setText("Candidate rejected for generated writes. Expected small plaintext diffs and exactly one direct 18→19→32 SET speed field.");
            }
            Toast.makeText(this,verdict,Toast.LENGTH_LONG).show();
        } catch(Exception e){
            String msg="AES test failed: "+e.getMessage()+"\nUse exactly 16 UTF-8 bytes or 32 hexadecimal digits.";
            aesResult.setText(msg); lastAesAnalysis=msg;
            if(fortyResult!=null) fortyResult.setText("40 km/h generator locked: AES test failed.");
            Toast.makeText(this,"Invalid AES candidate",Toast.LENGTH_SHORT).show();
        } finally {
            aesCandidateInput.setText("");
        }
    }

    private static byte[] parseAesKey'''
s, n = pattern.subn(lambda m:new_test, s, count=1)
if n != 1: raise SystemExit('failed to replace AES test for v0.8')

# Insert encryption, speed-field discovery, and frame construction helpers before byteDiffCount.
anchor = '    private static int byteDiffCount(byte[] a, byte[] b) {'
helpers = r'''    private static byte[] aesEncryptBlock(byte[] plainBlock, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"));
        return cipher.doFinal(plainBlock);
    }

    private static final class SpeedField {
        final int offset, width; final boolean little;
        SpeedField(int offset,int width,boolean little){this.offset=offset;this.width=width;this.little=little;}
        @Override public String toString(){return width==1 ? "uint8@"+offset : (little?"uint16LE@":"uint16BE@")+offset;}
    }

    private static ArrayList<SpeedField> findDirectSpeedFields(byte[] a,byte[] b,byte[] c){
        ArrayList<SpeedField> out=new ArrayList<>();
        for(int i=0;i<16;i++) if((a[i]&255)==18&&(b[i]&255)==19&&(c[i]&255)==32) out.add(new SpeedField(i,1,true));
        for(int i=0;i<15;i++){
            int al=(a[i]&255)|((a[i+1]&255)<<8), bl=(b[i]&255)|((b[i+1]&255)<<8), cl=(c[i]&255)|((c[i+1]&255)<<8);
            if(al==18&&bl==19&&cl==32) out.add(new SpeedField(i,2,true));
            int ab=((a[i]&255)<<8)|(a[i+1]&255), bb=((b[i]&255)<<8)|(b[i+1]&255), cb=((c[i]&255)<<8)|(c[i+1]&255);
            if(ab==18&&bb==19&&cb==32) out.add(new SpeedField(i,2,false));
        }
        return out;
    }

    private static void writeSpeedField(byte[] p,SpeedField f,int value){
        if(f.width==1){ if(value<0||value>255)throw new IllegalArgumentException("uint8 speed out of range"); p[f.offset]=(byte)value; return; }
        if(f.little){p[f.offset]=(byte)(value&255);p[f.offset+1]=(byte)((value>>>8)&255);}
        else {p[f.offset]=(byte)((value>>>8)&255);p[f.offset+1]=(byte)(value&255);}
    }

    private static byte[] buildNiuFrame(int command,byte[] cipher){
        if(cipher.length!=16)throw new IllegalArgumentException("cipher block must be 16 bytes");
        byte[] f=new byte[20];f[0]=0x01;f[1]=(byte)command;f[2]=0x00;System.arraycopy(cipher,0,f,3,16);
        int sum=0;for(int i=0;i<19;i++)sum=(sum+(f[i]&255))&255;f[19]=(byte)sum;return f;
    }

    private void generate40Frame(){
        if(validatedAesKey==null||validatedSpeedField==null){Toast.makeText(this,"Verify the recovered AES key first",Toast.LENGTH_SHORT).show();return;}
        try{
            byte[] p32=aesDecryptBlock(extractCipherBlock(SPEED_32),validatedAesKey);
            byte[] p40=Arrays.copyOf(p32,p32.length);
            writeSpeedField(p40,validatedSpeedField,40);
            byte[] c40=aesEncryptBlock(p40,validatedAesKey);
            generated40Frame=buildNiuFrame(0x22,c40);
            boolean ok=validChecksum(generated40Frame);
            fortyResult.setText("40 km/h candidate GENERATED LOCALLY\nField: "+validatedSpeedField+"\n32 plaintext: "+hex(p32)+"\n40 plaintext: "+hex(p40)+"\nFrame: "+hex(generated40Frame)+"\nChecksum: "+(ok?"OK":"FAILED")+"\n\nThis frame has not yet been accepted by the scooter. The controller/firmware may clamp or reject 40 km/h.");
            diagnostic.append("GENERATED_40 field=").append(validatedSpeedField).append(" frame=").append(hex(generated40Frame)).append(" checksum=").append(ok?"OK":"BAD").append("\n");
            send40Button.setText("SEND GENERATED 40 KM/H — STATIONARY TEST");
            send40Button.setEnabled(readyForCommands&&ok);
        }catch(Exception e){generated40Frame=null;send40Button.setEnabled(false);fortyResult.setText("40 generation failed: "+e.getMessage());}
    }

    private void confirmGenerated40(){
        if(generated40Frame==null||validatedAesKey==null||validatedSpeedField==null){Toast.makeText(this,"40 frame is not unlocked",Toast.LENGTH_SHORT).show();return;}
        if(!readyForCommands||gatt==null||niuTx==null){Toast.makeText(this,"Connect to the KQi first",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this)
                .setTitle("Send generated 40 km/h setting?")
                .setMessage("This is a newly generated Dynamic Mode frame, not an official captured 40 km/h frame. Keep the scooter stationary with the driven wheel clear. It changes only the decoded max-speed field; it does not change BMS, current, thermal, brake, firmware, or region settings.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Send",(d,w)->queueReplay("40 km/h generated",generated40Frame))
                .show();
    }

'''
rep(anchor, helpers+anchor, 1)

# Keep the send control synchronized with BLE connection state after a frame has been generated.
rep('            if (sequenceButton != null) sequenceButton.setEnabled(readyForCommands);',
    '            if (sequenceButton != null) sequenceButton.setEnabled(readyForCommands);\n'
    '            if (send40Button != null) send40Button.setEnabled(readyForCommands && generated40Frame != null);')

# Make the safety copy explicit about the newly generated setting.
s=s.replace('This build does not alter BMS limits, controller current, thermal protection, brakes, firmware, or region data.',
            'The generated 40 km/h test changes only the decoded Dynamic Mode max-speed field. It does not alter BMS limits, controller current, thermal protection, brakes, firmware, or region data.')

src.write_text(s)

gradle=Path('kqilab/app/build.gradle')
g=gradle.read_text().replace('versionCode 7','versionCode 8').replace("versionName '0.7.0'","versionName '0.8.0'")
if 'versionCode 8' not in g or "versionName '0.8.0'" not in g: raise SystemExit('failed v0.8 metadata patch')
gradle.write_text(g)
print('Prepared KQi Lab v0.8 gated 40 km/h generator build')
