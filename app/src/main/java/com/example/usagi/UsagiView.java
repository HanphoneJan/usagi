package com.example.usagi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Random;

public class UsagiView extends View {

    // 动画状态枚举
    private enum AnimationState {
        IDLE, MOVE, TOUCH, DOUBLE_TAP
    }

    private Context context;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    
    // 屏幕尺寸
    private int screenWidth, screenHeight;
    
    // 角色位置
    private int x, y;
    private int dx, dy;
    
    // 动画相关
    private AnimationState currentState = AnimationState.IDLE;
    private int currentFrame = 0;
    private long lastFrameTime = 0;
    private int frameDelay = 100; // 帧延迟，毫秒
    
    // 图片资源数组
    private Bitmap[] idleFrames;
    private Bitmap[] moveFrames;
    private Bitmap[] touchFrames;
    private Bitmap[] doubleTapFrames;
    
    // 音频资源
    private SoundPool soundPool;
    private int soundStart, soundSit, soundDouble, sound5;
    
    // 随机数生成器
    private Random random = new Random();
    
    // 拖拽相关
    private boolean isDragging = false;
    private int lastTouchX, lastTouchY;

    public UsagiView(Context context) {
        super(context);
        this.context = context;
        init();
    }

    public UsagiView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        init();
    }

    private void init() {
        // 获取WindowManager
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        
        // 初始化位置
        x = 0;
        y = 100;
        
        // 初始化移动方向和速度
        dx = random.nextInt(5) + 2;
        dy = random.nextInt(5) + 2;
        
        // 初始化图片资源
        loadImages();
        
        // 初始化音频资源
        loadSounds();
        
        // 开始动画
        startAnimation();
    }

    // 加载图片资源
    private void loadImages() {
        // 获取包名
        String packageName = context.getPackageName();
        
        // 待机动画帧
        idleFrames = new Bitmap[8];
        for (int i = 0; i < 8; i++) {
            String resourceName = "shime" + (i + 1);
            int resourceId = getResources().getIdentifier(resourceName, "drawable", packageName);
            if (resourceId > 0) {
                idleFrames[i] = BitmapFactory.decodeResource(getResources(), resourceId);
            }
        }
        
        // 移动动画帧
        moveFrames = new Bitmap[16];
        for (int i = 0; i < 16; i++) {
            String resourceName = "shime" + (i + 9);
            int resourceId = getResources().getIdentifier(resourceName, "drawable", packageName);
            if (resourceId > 0) {
                moveFrames[i] = BitmapFactory.decodeResource(getResources(), resourceId);
            }
        }
        
        // 触摸动画帧
        touchFrames = new Bitmap[8];
        for (int i = 0; i < 8; i++) {
            String resourceName = "shime" + (i + 25);
            int resourceId = getResources().getIdentifier(resourceName, "drawable", packageName);
            if (resourceId > 0) {
                touchFrames[i] = BitmapFactory.decodeResource(getResources(), resourceId);
            }
        }
        
        // 双击动画帧
        doubleTapFrames = new Bitmap[8];
        for (int i = 0; i < 8; i++) {
            String resourceName = "shime" + (i + 33);
            int resourceId = getResources().getIdentifier(resourceName, "drawable", packageName);
            if (resourceId > 0) {
                doubleTapFrames[i] = BitmapFactory.decodeResource(getResources(), resourceId);
            }
        }
    }

    // 加载音频资源
    private void loadSounds() {
        // 初始化SoundPool
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build();
        
        // 加载音效
        try {
            soundStart = soundPool.load(context, R.raw.start, 1);
            soundSit = soundPool.load(context, R.raw.sit, 1);
            soundDouble = soundPool.load(context, R.raw.sound_double, 1);
            sound5 = soundPool.load(context, R.raw.sound_5, 1);
            
            // 播放启动音效
            soundPool.play(soundStart, 1.0f, 1.0f, 0, 0, 1.0f);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 开始动画
    private void startAnimation() {
        final Handler handler = new Handler();
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateAnimation();
                invalidate();
                handler.postDelayed(this, 16); // 约60fps
            }
        });
    }

    // 更新动画
    private void updateAnimation() {
        long currentTime = System.currentTimeMillis();
        
        // 帧更新
        if (currentTime - lastFrameTime > frameDelay) {
            currentFrame++;
            lastFrameTime = currentTime;
            
            // 根据不同状态处理帧循环
            switch (currentState) {
                case IDLE:
                    if (currentFrame >= idleFrames.length) {
                        currentFrame = 0;
                    }
                    break;
                case MOVE:
                    if (currentFrame >= moveFrames.length) {
                        currentFrame = 0;
                    }
                    break;
                case TOUCH:
                    if (currentFrame >= touchFrames.length) {
                        currentFrame = 0;
                        currentState = AnimationState.IDLE;
                    }
                    break;
                case DOUBLE_TAP:
                    if (currentFrame >= doubleTapFrames.length) {
                        currentFrame = 0;
                        currentState = AnimationState.IDLE;
                    }
                    break;
            }
        }
        
        // 非拖拽状态下更新位置
        if (!isDragging) {
            // 根据状态移动
            if (currentState == AnimationState.MOVE) {
                x += dx;
                y += dy;
                
                // 边界检测
                if (x <= 0 || x >= screenWidth - getWidth()) {
                    dx = -dx;
                }
                if (y <= 0 || y >= screenHeight - getHeight()) {
                    dy = -dy;
                }
                
                // 随机切换到待机状态
                if (random.nextInt(100) < 2) {
                    currentState = AnimationState.IDLE;
                    currentFrame = 0;
                }
            } else {
                // 随机切换到移动状态
                if (random.nextInt(100) < 1) {
                    currentState = AnimationState.MOVE;
                    currentFrame = 0;
                    // 随机改变移动方向和速度
                    dx = (random.nextBoolean() ? 1 : -1) * (random.nextInt(5) + 2);
                    dy = (random.nextBoolean() ? 1 : -1) * (random.nextInt(5) + 2);
                }
            }
            
            // 更新窗口位置
            updateWindowPosition();
        }
    }

    // 更新窗口位置
    private void updateWindowPosition() {
        if (layoutParams == null) {
            layoutParams = (WindowManager.LayoutParams) getLayoutParams();
        }
        layoutParams.x = x;
        layoutParams.y = y;
        windowManager.updateViewLayout(this, layoutParams);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 根据当前状态绘制对应帧
        Bitmap currentBitmap = null;
        switch (currentState) {
            case IDLE:
                currentBitmap = idleFrames[currentFrame];
                break;
            case MOVE:
                currentBitmap = moveFrames[currentFrame];
                break;
            case TOUCH:
                currentBitmap = touchFrames[currentFrame];
                break;
            case DOUBLE_TAP:
                currentBitmap = doubleTapFrames[currentFrame];
                break;
        }
        
        if (currentBitmap != null) {
            canvas.drawBitmap(currentBitmap, 0, 0, new Paint());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int touchX = (int) event.getRawX();
        int touchY = (int) event.getRawY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 处理按下事件
                isDragging = true;
                lastTouchX = touchX;
                lastTouchY = touchY;
                
                // 切换到触摸动画
                currentState = AnimationState.TOUCH;
                currentFrame = 0;
                
                // 播放触摸音效
                soundPool.play(soundSit, 1.0f, 1.0f, 0, 0, 1.0f);
                break;
                
            case MotionEvent.ACTION_MOVE:
                // 处理移动事件
                if (isDragging) {
                    // 计算移动距离
                    int deltaX = touchX - lastTouchX;
                    int deltaY = touchY - lastTouchY;
                    
                    // 更新位置
                    x += deltaX;
                    y += deltaY;
                    
                    // 更新窗口位置
                    updateWindowPosition();
                    
                    // 更新上次触摸位置
                    lastTouchX = touchX;
                    lastTouchY = touchY;
                }
                break;
                
            case MotionEvent.ACTION_UP:
                // 处理抬起事件
                isDragging = false;
                break;
                
            case MotionEvent.ACTION_POINTER_DOWN:
                // 处理多点触摸（双击效果）
                if (event.getPointerCount() == 2) {
                    currentState = AnimationState.DOUBLE_TAP;
                    currentFrame = 0;
                    
                    // 播放双击音效
                    soundPool.play(soundDouble, 1.0f, 1.0f, 0, 0, 1.0f);
                }
                break;
        }
        
        return true;
    }
}