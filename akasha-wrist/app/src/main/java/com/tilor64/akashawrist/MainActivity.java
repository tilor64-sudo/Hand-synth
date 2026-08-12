package com.tilor64.akashawrist;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int VOICE_REQ = 42;
    private static final int SENSOR_REQ = 43;
    private static final int GREEN = Color.rgb(102,255,204);
    private static final int BLUE = Color.rgb(90,190,255);
    private static final int VIOLET = Color.rgb(180,120,255);

    private LinearLayout root;
    private TextView heartView;
    private TextView clockView;
    private SensorManager sensorManager;
    private Sensor heartSensor;
    private SharedPreferences prefs;
    private String voiceMode = "ask";
    private CountDownTimer timer;
    private long timerLeft = 0;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("akasha", MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        heartSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        showHome();
        clockHandler.post(clockTick);
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (clockView != null) clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            clockHandler.postDelayed(this, 1000);
        }
    };

    private void base(String title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.BLACK);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(16);
        root.setPadding(pad, dp(18), pad, dp(26));
        scroll.addView(root, new ScrollView.LayoutParams(-1,-1));
        setContentView(scroll);
        TextView t = text(title, 20, GREEN);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLetterSpacing(.12f);
        root.addView(t);
    }

    private void showHome() {
        base("AKASHA // WRIST");
        clockView = text("--:--", 28, Color.WHITE);
        root.addView(clockView, lp(dp(8)));
        TextView sig = text("◉  AWAKE", 11, VIOLET);
        root.addView(sig, lp(dp(2)));

        Button speak = button("◉  SPEAK", GREEN);
        speak.setTextSize(18);
        speak.setOnClickListener(v -> startVoice("ask"));
        root.addView(speak, lp(dp(12)));

        heartView = text("♡  -- BPM", 14, BLUE);
        heartView.setOnClickListener(v -> enableHeartRate());
        root.addView(heartView, lp(dp(6)));
        enableHeartRate();

        addNav("SHIFT MODE", BLUE, v -> showShift());
        addNav("TIMER", GREEN, v -> showTimer());
        addNav("NOTE", VIOLET, v -> startVoice("note"));
        addNav("HUD", BLUE, v -> showHud());
        addNav("RECENT NOTES", GREEN, v -> showNotes());

        TextView footer = text("tap heart rate to enable sensor", 9, Color.GRAY);
        root.addView(footer, lp(dp(8)));
    }

    private void showShift() {
        base("SHIFT MODE");
        boolean active = prefs.getBoolean("shift_active", false);
        long start = prefs.getLong("shift_start", 0);
        TextView status = text(active ? "ACTIVE" : "STANDBY", 17, active ? GREEN : Color.GRAY);
        root.addView(status, lp(dp(8)));
        TextView elapsed = text(active ? elapsed(start) : "00:00", 25, Color.WHITE);
        root.addView(elapsed, lp(dp(8)));
        if (active) {
            Handler h = new Handler(Looper.getMainLooper());
            Runnable r = new Runnable(){ public void run(){
                if (prefs.getBoolean("shift_active",false)) { elapsed.setText(elapsed(prefs.getLong("shift_start",0))); h.postDelayed(this,1000); }
            }}; h.post(r);
        }
        Button toggle = button(active ? "END SHIFT" : "START SHIFT", active ? VIOLET : GREEN);
        toggle.setOnClickListener(v -> {
            boolean now = !prefs.getBoolean("shift_active",false);
            SharedPreferences.Editor e = prefs.edit().putBoolean("shift_active",now);
            if (now) e.putLong("shift_start",System.currentTimeMillis());
            e.apply(); vibrate(60); showShift();
        });
        root.addView(toggle, lp(dp(8)));
        addNav("VOICE NOTE", VIOLET, v -> startVoice("note"));
        addNav("TIMER", GREEN, v -> showTimer());
        back();
    }

    private String elapsed(long start) {
        long s = Math.max(0,(System.currentTimeMillis()-start)/1000);
        return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60);
    }

    private void showTimer() {
        base("TIMER");
        TextView display = text(timerLeft > 0 ? fmtTimer(timerLeft) : "00:00", 32, Color.WHITE);
        root.addView(display, lp(dp(10)));
        int[] mins = {5,10,15,30};
        for (int m: mins) addNav(m + " MIN", m==5?GREEN:BLUE, v -> startTimer(m, display));
        if (timerLeft > 0) {
            Button cancel = button("CANCEL", VIOLET);
            cancel.setOnClickListener(v -> { if(timer!=null) timer.cancel(); timerLeft=0; showTimer(); });
            root.addView(cancel, lp(dp(8)));
        }
        back();
    }

    private void startTimer(int minutes, TextView display) {
        if (timer != null) timer.cancel();
        timerLeft = minutes * 60_000L;
        timer = new CountDownTimer(timerLeft, 1000) {
            public void onTick(long ms){ timerLeft=ms; display.setText(fmtTimer(ms)); }
            public void onFinish(){ timerLeft=0; display.setText("DONE"); vibrate(700); Toast.makeText(MainActivity.this,"Akasha timer complete",Toast.LENGTH_LONG).show(); }
        }.start();
        vibrate(40);
    }

    private String fmtTimer(long ms) {
        long sec=(ms+999)/1000;
        return String.format(Locale.US,"%02d:%02d",sec/60,sec%60);
    }

    private void showHud() {
        base("AKASHA HUD");
        TextView status=text("STATUS: STANDBY",12,GREEN);
        root.addView(status,lp(dp(12)));
        String[] cmds={"WAKE HUD","CENTER DISPLAY","SLEEP HUD"};
        for(String c:cmds) addNav(c,BLUE,v->{ status.setText("COMMAND: "+c); vibrate(35); });
        TextView note=text("Phone / XR bridge endpoint reserved for V2",9,Color.GRAY);
        root.addView(note,lp(dp(10)));
        back();
    }

    private void showNotes() {
        base("RECENT NOTES");
        String saved=prefs.getString("notes","");
        if(saved.isEmpty()) root.addView(text("No notes yet.",12,Color.GRAY),lp(dp(12)));
        else {
            String[] notes=saved.split("\\n---\\n");
            for(int i=notes.length-1;i>=Math.max(0,notes.length-6);i--) {
                TextView n=text(notes[i],11,Color.WHITE);
                n.setGravity(Gravity.START);
                root.addView(n,lp(dp(8)));
            }
        }
        addNav("NEW VOICE NOTE",VIOLET,v->startVoice("note"));
        back();
    }

    private void showResponse(String heard) {
        base("AKASHA");
        TextView you=text("YOU:  "+heard,11,BLUE); you.setGravity(Gravity.START); root.addView(you,lp(dp(10)));
        String response = demoReply(heard);
        TextView a=text("AKASHA:  "+response,13,GREEN); a.setGravity(Gravity.START); root.addView(a,lp(dp(12)));
        addNav("◉ SPEAK AGAIN",GREEN,v->startVoice("ask"));
        back(); vibrate(45);
    }

    private String demoReply(String q) {
        String s=q.toLowerCase(Locale.US);
        if(s.contains("timer")) return "Timer controls are ready. Return home and tap TIMER.";
        if(s.contains("shift")) return prefs.getBoolean("shift_active",false) ? "Shift Mode is active. Keep the signal clean." : "Shift Mode is standing by.";
        if(s.contains("heart")) return heartView!=null ? heartView.getText().toString() : "Heart sensor is standing by.";
        if(s.contains("note")) return "Say your thought through NOTE and I will preserve it locally on the watch.";
        return "Signal received: “"+q+"”  Demo core is online. Phone-linked intelligence arrives in V2.";
    }

    private void startVoice(String mode) {
        voiceMode=mode;
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, mode.equals("note")?"Speak note":"Speak to Akasha");
        try { startActivityForResult(i,VOICE_REQ); }
        catch(Exception e){ Toast.makeText(this,"Speech recognition is unavailable on this watch.",Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==VOICE_REQ && result==RESULT_OK && data!=null){
            ArrayList<String> list=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(list==null||list.isEmpty()) return;
            String heard=list.get(0);
            if("note".equals(voiceMode)) {
                String stamp=new SimpleDateFormat("MMM d, HH:mm",Locale.getDefault()).format(new Date());
                String old=prefs.getString("notes","");
                String item=stamp+"  •  "+heard;
                prefs.edit().putString("notes",old.isEmpty()?item:old+"\n---\n"+item).apply();
                vibrate(55); Toast.makeText(this,"Note saved",Toast.LENGTH_SHORT).show(); showNotes();
            } else showResponse(heard);
        }
    }

    private void enableHeartRate() {
        if(heartView==null) return;
        if(heartSensor==null){ heartView.setText("♡  -- BPM"); return; }
        if(checkSelfPermission(Manifest.permission.BODY_SENSORS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS},SENSOR_REQ); return;
        }
        sensorManager.unregisterListener(this);
        sensorManager.registerListener(this,heartSensor,SensorManager.SENSOR_DELAY_NORMAL);
        heartView.setText("♡  reading…");
    }

    @Override public void onSensorChanged(SensorEvent e){
        if(e.sensor.getType()==Sensor.TYPE_HEART_RATE && heartView!=null && e.values.length>0 && e.values[0]>0)
            heartView.setText("♡  "+Math.round(e.values[0])+" BPM");
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    @Override public void onRequestPermissionsResult(int req,String[] p,int[] g){
        super.onRequestPermissionsResult(req,p,g);
        if(req==SENSOR_REQ && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) enableHeartRate();
        else if(req==SENSOR_REQ && heartView!=null) heartView.setText("♡  -- BPM");
    }

    private void addNav(String label,int color,View.OnClickListener l){
        Button b=button(label,color); b.setOnClickListener(l); root.addView(b,lp(dp(5)));
    }
    private void back(){ addNav("‹ HOME",Color.DKGRAY,v->showHome()); }

    private Button button(String s,int color){
        Button b=new Button(this); b.setText(s); b.setTextColor(color); b.setTextSize(12); b.setAllCaps(false);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(10),dp(5),dp(10),dp(5));
        GradientDrawable gd=new GradientDrawable(); gd.setColor(Color.rgb(8,12,14)); gd.setStroke(dp(1),color); gd.setCornerRadius(dp(22)); b.setBackground(gd);
        return b;
    }
    private TextView text(String s,int sp,int color){
        TextView t=new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(sp); t.setGravity(Gravity.CENTER); return t;
    }
    private LinearLayout.LayoutParams lp(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=top; return p;
    }
    private int dp(int n){ return Math.round(n*getResources().getDisplayMetrics().density); }

    private void vibrate(long ms){
        Vibrator v=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE); if(v==null||!v.hasVibrator())return;
        v.vibrate(VibrationEffect.createOneShot(ms,VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @Override protected void onPause(){ super.onPause(); if(sensorManager!=null)sensorManager.unregisterListener(this); }
    @Override protected void onResume(){ super.onResume(); if(heartView!=null)enableHeartRate(); }
    @Override protected void onDestroy(){ super.onDestroy(); clockHandler.removeCallbacksAndMessages(null); if(timer!=null)timer.cancel(); }
}
