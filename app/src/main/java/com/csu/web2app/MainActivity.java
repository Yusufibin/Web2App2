package com.csu.web2app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startSmsExport();
                } else {
                    Toast.makeText(this, "Permission denied to read SMS", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> createFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        saveSmsToFileInBackground(uri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnDownloadSms = findViewById(R.id.btn_download_sms);
        btnDownloadSms.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                startSmsExport();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_SMS);
            }
        });
    }

    private void startSmsExport() {
        createFile();
    }

    private void createFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "my_sms_backup.txt");
        createFileLauncher.launch(intent);
    }

    private void saveSmsToFileInBackground(Uri uri) {
        executorService.execute(() -> {
            boolean success = false;
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    writeSmsToStream(outputStream);
                    success = true;
                }
            } catch (IOException | IllegalArgumentException e) {
                e.printStackTrace();
            }

            final boolean finalSuccess = success;
            mainHandler.post(() -> {
                if (finalSuccess) {
                    Toast.makeText(MainActivity.this, "SMS saved successfully", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Failed to save SMS", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void writeSmsToStream(OutputStream outputStream) throws IOException {
        Uri uriSms = Uri.parse("content://sms/");
        ContentResolver cr = getContentResolver();

        String[] projection = new String[]{"address", "body", "date"};
        try (Cursor cursor = cr.query(uriSms, projection, null, null, "date DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                int indexAddress = cursor.getColumnIndexOrThrow("address");
                int indexBody = cursor.getColumnIndexOrThrow("body");
                int indexDate = cursor.getColumnIndexOrThrow("date");

                do {
                    String address = cursor.getString(indexAddress);
                    String body = cursor.getString(indexBody);
                    String date = cursor.getString(indexDate);

                    StringBuilder sb = new StringBuilder();
                    sb.append("From: ").append(address).append("\n");
                    sb.append("Date: ").append(new java.util.Date(Long.parseLong(date))).append("\n");
                    sb.append("Message: ").append(body).append("\n");
                    sb.append("----------------------------------\n");

                    outputStream.write(sb.toString().getBytes());
                } while (cursor.moveToNext());
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
