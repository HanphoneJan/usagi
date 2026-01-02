package com.example.usagi;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ImageView;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnSettings;
    private ImageView ivBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSettings = findViewById(R.id.btn_settings);
        ivBackground = findViewById(R.id.iv_background);

        // 加载背景
        loadBackground();

        // 启动按钮点击事件
        btnStart.setOnClickListener(v -> {
            // 检查并申请悬浮窗权限
            if ( !Settings.canDrawOverlays(MainActivity.this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                // 启动桌宠服务
                startService(new Intent(MainActivity.this, UsagiService.class));
            }
        });

        // 停止按钮点击事件
        btnStop.setOnClickListener(v -> {
            // 停止桌宠服务
            stopService(new Intent(MainActivity.this, UsagiService.class));
        });

        // 设置按钮点击事件
        btnSettings.setOnClickListener(v -> {
            // 打开设置界面
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置返回时重新加载背景
        loadBackground();
    }

    private void loadBackground() {
        File backgroundImageFile = new File(getFilesDir(), "background_image.jpg");
        if (backgroundImageFile.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(backgroundImageFile.getAbsolutePath());
            if (bitmap != null) {
                ivBackground.setImageBitmap(bitmap);
                ivBackground.setVisibility(android.view.View.VISIBLE);
            }
        } else {
            ivBackground.setVisibility(android.view.View.GONE);
        }
    }
}