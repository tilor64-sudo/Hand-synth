package com.akasha.kqilab;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class NiuExporterActivity extends Activity {
    private static final String NIU_PACKAGE = "com.niu.manager";
    private TextView status;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32,32,32,32);

        TextView title = new TextView(this);
        title.setText("NIU APK EXPORTER");
        title.setTextSize(28);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Exports the official NIU app already installed on this phone so its KQi protocol implementation can be inspected for interoperability research. Nothing is modified or installed.");
        info.setTextSize(15);
        info.setPadding(0,16,0,20);
        root.addView(info);

        status = new TextView(this);
        status.setText("Ready.");
        status.setTextSize(15);
        status.setPadding(0,0,0,18);
        root.addView(status);

        Button export = new Button(this);
        export.setText("EXPORT INSTALLED NIU APK(S)");
        export.setOnClickListener(v -> new Thread(this::exportNiu).start());
        root.addView(export);

        Button back = new Button(this);
        back.setText("OPEN KQI LAB");
        back.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(back);

        setContentView(root);
        detectNiu();
    }

    private void detectNiu() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(NIU_PACKAGE, 0);
            status.setText("Detected official NIU app: " + pi.versionName + " (" + pi.getLongVersionCode() + ")");
        } catch (Exception e) {
            status.setText("Official NIU app not detected: " + e.getMessage());
        }
    }

    private void exportNiu() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(NIU_PACKAGE, 0);
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || ai.sourceDir == null) throw new Exception("NIU source APK path unavailable");

            List<File> apks = new ArrayList<>();
            apks.add(new File(ai.sourceDir));
            if (ai.splitSourceDirs != null) {
                for (String path : ai.splitSourceDirs) if (path != null) apks.add(new File(path));
            }

            String safeVersion = pi.versionName == null ? "unknown" : pi.versionName.replaceAll("[^A-Za-z0-9._-]", "_");
            String fileName = "NIU-" + safeVersion + "-installed-apks.zip";

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KQiLab");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Could not create Downloads file");

            try (OutputStream raw = getContentResolver().openOutputStream(uri);
                 ZipOutputStream zip = new ZipOutputStream(raw)) {
                if (raw == null) throw new Exception("Could not open output stream");
                byte[] buffer = new byte[1024 * 64];
                int index = 0;
                for (File apk : apks) {
                    if (!apk.isFile()) continue;
                    String entryName = index == 0 ? "base.apk" : ("split-" + index + "-" + apk.getName());
                    ZipEntry entry = new ZipEntry(entryName);
                    zip.putNextEntry(entry);
                    try (FileInputStream in = new FileInputStream(apk)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                    index++;
                }
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);

            runOnUiThread(() -> {
                status.setText("Exported " + apks.size() + " installed APK file(s) to Downloads/KQiLab/" + fileName);
                Toast.makeText(this, "NIU APK export complete", Toast.LENGTH_LONG).show();
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/zip");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Share NIU APK export"));
            });
        } catch (Exception e) {
            runOnUiThread(() -> status.setText("Export failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }
}
