package com.example.usagi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.core.app.NotificationCompat;

import java.io.File;

public class UsagiService extends Service {

    private static final String CHANNEL_ID = "UsagiServiceChannel";
    private WindowManager windowManager;
    private UsagiView usagiView;
    private ImageView backgroundView; // 背景视图

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
        
        // 加载并添加背景
        loadBackground();
        
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
        if (backgroundView != null) {
            windowManager.removeView(backgroundView);
        }
        if (usagiView != null) {
            windowManager.removeView(usagiView);
        }
    }
    
    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        // 处理设置更改通知
        if (intent != null && "reload_settings".equals(intent.getAction())) {
            if (usagiView != null) {
                usagiView.reloadSettings();
            }
            reloadBackground(); // 重新加载背景
        }
    }
    
    // 加载背景
    private void loadBackground() {
        SharedPreferences sharedPreferences = getSharedPreferences("usagi_settings", MODE_PRIVATE);
        boolean hasBackground = sharedPreferences.getBoolean("has_background", false);
        
        if (hasBackground) {
            try {
                File backgroundFile = new File(getFilesDir(), "background_image.jpg");
                if (backgroundFile.exists()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(backgroundFile.getAbsolutePath());
                    if (bitmap != null) {
                        // 移除旧背景
                        if (backgroundView != null) {
                            windowManager.removeView(backgroundView);
                        }
                        
                        // 创建新的背景视图
                        backgroundView = new ImageView(this);
                        backgroundView.setImageBitmap(bitmap);
                        backgroundView.setScaleType(ImageView.ScaleType.FIT_XY);
                        
                        // 获取屏幕尺寸
                        android.view.Display display = windowManager.getDefaultDisplay();
                        android.graphics.Point size = new android.graphics.Point();
                        display.getSize(size);
                        
                        // 设置背景WindowManager参数（覆盖整个屏幕）
                        WindowManager.LayoutParams bgParams = new WindowManager.LayoutParams(
                                size.x,
                                size.y,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                PixelFormat.TRANSLUCENT
                        );
                        bgParams.gravity = Gravity.TOP | Gravity.START;
                        bgParams.x = 0;
                        bgParams.y = 0;
                        
                        // 添加背景到窗口
                        windowManager.addView(backgroundView, bgParams);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    // 重新加载背景（设置更改时调用）
    private void reloadBackground() {
        // 移除旧背景
        if (backgroundView != null) {
            windowManager.removeView(backgroundView);
            backgroundView = null;
        }
        // 重新加载
        loadBackground();
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