package com.example.usagi;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSettings = findViewById(R.id.btn_settings);

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
}