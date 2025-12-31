package com.example.usagi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // 检查是否是开机完成广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 获取SharedPreferences
            SharedPreferences sharedPreferences = context.getSharedPreferences("usagi_settings", Context.MODE_PRIVATE);
            // 检查是否开启了开机自启
            boolean autoStart = sharedPreferences.getBoolean("auto_start", false);
            if (autoStart) {
                // 启动桌宠服务
                Intent serviceIntent = new Intent(context, UsagiService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}