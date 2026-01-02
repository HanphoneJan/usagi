package com.example.usagi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

public class UsagiService extends Service {

    private static final String CHANNEL_ID = "UsagiServiceChannel";
    private WindowManager windowManager;
    private UsagiView usagiView;

    @Override
    public void onCreate() {
        super.onCreate();
        // 创建通知渠道
        createNotificationChannel();
        // 创建通知
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("乌萨奇桌宠")
                .setContentText("桌宠正在运行")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        // 启动前台服务
        startForeground(1, notification);

        // 初始化WindowManager
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // 创建桌宠View
        usagiView = new UsagiView(this);
        // 设置WindowManager参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                 WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        // 设置初始位置：屏幕正中央
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0; // 由 UsagiView 内部计算并更新
        params.y = 0; // 由 UsagiView 内部计算并更新
        // 添加View到窗口
        windowManager.addView(usagiView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 移除View
        if (usagiView != null) {
            windowManager.removeView(usagiView);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 创建通知渠道
    private void createNotificationChannel() {

            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "桌宠服务",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);

    }
}