package com.akasha.kqilab;

import android.app.Activity;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExportActivity extends Activity {
    private static final int REQ_CREATE_ZIP = 600;
    private static final String NIU_PACKAGE = "com.niu.manager";

    private TextView status;
    private Button exportButton;
    private ApplicationInfo niuInfo;
    private PackageInfo niuPackageInfo;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        inspectNiu();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("KQi LAB v0.6");
        title.setTextSize(28);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Installed NIU package exporter + BLE lab");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, 4, 0, 18);
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(15);
        status.setText("Checking for the installed NIU app...");
        status.setPadding(0, 0, 0, 16);
        root.addView(status);

        exportButton = new Button(this);
        exportButton.setText("EXPORT INSTALLED NIU APP TO ZIP");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> chooseExportDestination());
        root.addView(exportButton);

        Button labButton = new Button(this);
        labButton.setText("OPEN KQI BLE LAB");
        labButton.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(labButton);

        TextView info = new TextView(this);
        info.setText("The exporter only copies the NIU APK files already installed on this phone into a ZIP you choose. It does not modify, uninstall, patch, or launch the NIU app. No root access is used.");
        info.setTextSize(14);
        info.setPadding(0, 18, 0, 0);
        root.addView(info);

        setContentView(scroll);
    }

    @SuppressWarnings("deprecation")
    private void inspectNiu() {
        try {
            PackageManager pm = getPackageManager();
            if (Build.VERSION.SDK_INT >= 33) {
                niuInfo = pm.getApplicationInfo(NIU_PACKAGE, PackageManager.ApplicationInfoFlags.of(0));
                niuPackageInfo = pm.getPackageInfo(NIU_PACKAGE, PackageManager.PackageInfoFlags.of(0));
            } else {
                niuInfo = pm.getApplicationInfo(NIU_PACKAGE, 0);
                niuPackageInfo = pm.getPackageInfo(NIU_PACKAGE, 0);
            }

            int splits = niuInfo.splitSourceDirs == null ? 0 : niuInfo.splitSourceDirs.length;
            String version = niuPackageInfo.versionName == null ? "unknown" : niuPackageInfo.versionName;
            long versionCode = Build.VERSION.SDK_INT >= 28 ? niuPackageInfo.getLongVersionCode() : niuPackageInfo.versionCode;
            status.setText("NIU found\nPackage: " + NIU_PACKAGE + "\nVersion: " + version + " (" + versionCode + ")\nBase APK + " + splits + " split APK(s)\n\nReady to export.");
            exportButton.setEnabled(true);
        } catch (Exception e) {
            niuInfo = null;
            niuPackageInfo = null;
            status.setText("NIU package not visible/found: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            exportButton.setEnabled(false);
        }
    }

    private void chooseExportDestination() {
        if (niuInfo == null) {
            inspectNiu();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "NIU-installed-apks.zip");
        startActivityForResult(i, REQ_CREATE_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CREATE_ZIP || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        status.setText("Exporting NIU APK files...");
        exportButton.setEnabled(false);

        new Thread(() -> {
            try {
                exportNiu(uri);
                runOnUiThread(() -> {
                    status.setText("Export complete. Upload NIU-installed-apks.zip to ChatGPT and Akasha can inspect the exact installed NIU build.");
                    exportButton.setEnabled(true);
                    Toast.makeText(this, "NIU export complete", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Export failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    exportButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void exportNiu(Uri uri) throws Exception {
        if (niuInfo == null) throw new IllegalStateException("NIU app not found");
        OutputStream raw = getContentResolver().openOutputStream(uri, "w");
        if (raw == null) throw new IOException("Could not open destination");

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            StringBuilder meta = new StringBuilder();
            meta.append("package=").append(NIU_PACKAGE).append('\n');
            if (niuPackageInfo != null) {
                meta.append("versionName=").append(niuPackageInfo.versionName).append('\n');
                meta.append("versionCode=").append(Build.VERSION.SDK_INT >= 28 ? niuPackageInfo.getLongVersionCode() : niuPackageInfo.versionCode).append('\n');
            }
            meta.append("baseSourceDir=").append(niuInfo.sourceDir).append('\n');
            if (niuInfo.splitSourceDirs != null) {
                for (String p : niuInfo.splitSourceDirs) meta.append("splitSourceDir=").append(p).append('\n');
            }
            addBytes(zip, "metadata.txt", meta.toString().getBytes(StandardCharsets.UTF_8));

            addFile(zip, new File(niuInfo.sourceDir), "base.apk");
            if (niuInfo.splitSourceDirs != null) {
                int i = 0;
                for (String path : niuInfo.splitSourceDirs) {
                    File f = new File(path);
                    String name = f.getName();
                    if (name == null || name.isEmpty() || name.equals("base.apk")) name = "split_" + i + ".apk";
                    addFile(zip, f, "splits/" + name);
                    i++;
                }
            }
        }
    }

    private static void addFile(ZipOutputStream zip, File file, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) zip.write(buf, 0, n);
            }
        }
        zip.closeEntry();
    }

    private static void addBytes(ZipOutputStream zip, String entryName, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(bytes);
        zip.closeEntry();
    }
}
