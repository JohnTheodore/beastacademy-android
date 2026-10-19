package com.example.beast_academy_apk;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Standalone test-APK UID; Java avoids a dependency on the target APK's Kotlin runtime. */
public class DiagnosticPermissionProbeActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String authority = "com.example.beast_academy_apk.inputlab.input-diagnostics";
        try {
            JSONObject result = new JSONObject().put("uid", android.os.Process.myUid());
            try (InputStream input = getContentResolver().openInputStream(Uri.parse("content://" + authority + "/snapshot"))) {
                if (input != null) input.read();
                result.put("readDenied", false);
            } catch (SecurityException error) {
                result.put("readDenied", true).put("readReason", error.getMessage());
            }
            try {
                getContentResolver().call(Uri.parse("content://" + authority), "enable-webview-debugging", null, null);
                result.put("controlDenied", false);
            } catch (SecurityException error) {
                result.put("controlDenied", true).put("controlReason", error.getMessage());
            }
            try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "diagnostic-permission-probe.json"))) {
                output.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Permission probe failed", error);
        } finally { finish(); }
    }
}
