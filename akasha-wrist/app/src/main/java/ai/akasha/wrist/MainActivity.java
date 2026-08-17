package ai.akasha.wrist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int SPEECH_REQUEST = 42;
    private static final String PREFS = "akasha_wrist";
    private static final String KEY_RELAY = "relay_url";
    private static final String KEY_TOKEN = "relay_token";
    private static final String KEY_CONVERSATION = "conversation_id";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView statusView;
    private TextView replyView;
    private TextView heardView;
    private OrbView orb;
    private TextToSpeech tts;
    private String conversationId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        conversationId = prefs.getString(KEY_CONVERSATION, null);
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_CONVERSATION, conversationId).apply();
        }
        tts = new TextToSpeech(this, this);
        buildUi();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(14), dp(24), dp(18));
        root.setBackgroundColor(Color.rgb(5, 4, 10));

        TextView title = text("AKASHA", 16, Color.rgb(205, 188, 255));
        title.setLetterSpacing(0.18f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        orb = new OrbView();
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(dp(112), dp(112));
        orbLp.topMargin = dp(2);
        root.addView(orb, orbLp);
        orb.setOnClickListener(v -> listen());
        orb.setOnLongClickListener(v -> { openSettings(); return true; });

        statusView = text("tap the orb", 12, Color.rgb(121, 232, 255));
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        heardView = text("", 11, Color.rgb(156, 151, 174));
        heardView.setGravity(Gravity.CENTER);
        root.addView(heardView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        replyView = text("I’m awake. Tap the orb and talk to me.\n\nLong-press the orb to configure the secure Akasha relay.", 14, Color.WHITE);
        replyView.setGravity(Gravity.CENTER);
        replyView.setLineSpacing(0f, 1.12f);
        replyView.setPadding(dp(2), dp(4), dp(2), dp(4));
        scroll.addView(replyView, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(2);
        root.addView(scroll, scrollLp);

        TextView footer = text("TAP • SPEAK    HOLD • SETUP", 9, Color.rgb(103, 96, 122));
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        setContentView(root);
    }

    private TextView text(String value, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setFontFeatureSettings("kern");
        return v;
    }

    private void listen() {
        vibrate(28);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Akasha");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            status("listening…");
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (Exception e) {
            status("voice unavailable");
            reply("Wear OS speech recognition isn’t available right now. Try again after checking the watch’s voice input settings.", true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SPEECH_REQUEST) return;
        if (resultCode != RESULT_OK || data == null) {
            status("tap the orb");
            return;
        }
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) {
            status("didn’t catch that");
            return;
        }
        String message = results.get(0).trim();
        heardView.setText("“" + message + "”");
        askAkasha(message);
    }

    private void askAkasha(String message) {
        String relay = prefs.getString(KEY_RELAY, "").trim();
        if (relay.isEmpty()) {
            status("demo mode");
            reply("I heard you: “" + message + "”\n\nMy voice path is working. Long-press the orb and add the Akasha relay URL to connect me to GPT‑5.6.", true);
            return;
        }
        if (!relay.toLowerCase(Locale.US).startsWith("https://")) {
            status("secure relay required");
            reply("For safety, Akasha Wrist only connects to HTTPS relay URLs. Long-press the orb and enter an https:// address.", true);
            return;
        }

        status("thinking…");
        orb.setThinking(true);
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(relay);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(45000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String token = prefs.getString(KEY_TOKEN, "").trim();
                if (!token.isEmpty()) conn.setRequestProperty("X-Akasha-Token", token);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("message", message);
                body.put("conversationId", conversationId);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }

                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String raw = readAll(stream);
                if (code < 200 || code >= 300) throw new Exception("Relay " + code + ": " + raw);
                JSONObject json = new JSONObject(raw);
                String answer = json.optString("reply", "").trim();
                if (answer.isEmpty()) throw new Exception("Relay returned no reply");
                main.post(() -> {
                    orb.setThinking(false);
                    status("with you");
                    reply(answer, true);
                    vibrate(35);
                });
            } catch (Exception e) {
                String problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> {
                    orb.setThinking(false);
                    status("connection snag");
                    reply("I couldn’t reach the Akasha relay.\n\n" + problem, false);
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "akasha-relay").start();
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void openSettings() {
        vibrate(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), 0);

        EditText url = new EditText(this);
        url.setHint("https://…/v1/ask");
        url.setSingleLine(false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString(KEY_RELAY, ""));
        box.addView(url);

        EditText token = new EditText(this);
        token.setHint("Relay token (optional)");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.getString(KEY_TOKEN, ""));
        box.addView(token);

        new AlertDialog.Builder(this)
                .setTitle("Akasha relay")
                .setMessage("The OpenAI API key stays on the server. This watch stores only the relay URL and optional relay token.")
                .setView(box)
                .setPositiveButton("Save", (d, which) -> {
                    prefs.edit()
                            .putString(KEY_RELAY, url.getText().toString().trim())
                            .putString(KEY_TOKEN, token.getText().toString().trim())
                            .apply();
                    status("relay saved");
                    reply("Relay configuration saved. Tap the orb and ask me something.", true);
                })
                .setNeutralButton("Demo", (d, which) -> {
                    prefs.edit().remove(KEY_RELAY).remove(KEY_TOKEN).apply();
                    status("demo mode");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void status(String s) { statusView.setText(s); }

    private void reply(String s, boolean speak) {
        replyView.setText(s);
        if (speak && tts != null) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "akasha_reply");
    }

    private void vibrate(int ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(ms, 90));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.getDefault());
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private class OrbView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase = 0f;
        private boolean thinking = false;
        private final Runnable pulse = new Runnable() {
            @Override public void run() {
                phase += thinking ? 0.16f : 0.07f;
                invalidate();
                main.postDelayed(this, 33);
            }
        };

        OrbView() {
            super(MainActivity.this);
            setClickable(true);
            setLongClickable(true);
            setContentDescription("Ask Akasha");
            main.post(pulse);
        }

        void setThinking(boolean value) { thinking = value; }

        @Override
        protected void onDetachedFromWindow() {
            main.removeCallbacks(pulse);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float base = Math.min(getWidth(), getHeight()) * 0.34f;
            float breathe = (float) ((Math.sin(phase) + 1.0) * 0.5);

            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int)(34 + 30 * breathe), 112, 71, 255));
            canvas.drawCircle(cx, cy, base * (1.55f + 0.08f * breathe), paint);
            paint.setColor(Color.argb((int)(55 + 35 * breathe), 82, 212, 255));
            canvas.drawCircle(cx, cy, base * (1.23f + 0.05f * breathe), paint);

            int core = Color.rgb(100, 232, 255);
            paint.setShader(new RadialGradient(cx, cy, base, Color.WHITE, core, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, base * (0.96f + 0.035f * breathe), paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(190, 202, 177, 255));
            canvas.drawCircle(cx, cy, base * 1.05f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
