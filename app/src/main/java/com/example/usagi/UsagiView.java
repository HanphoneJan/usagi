package com.example.usagi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class UsagiView extends View {

    // 物理与状态常量
    private static final float GRAVITY = 0.6f;          // 重力加速度
    private static final float BOUNCE_DAMPING = 0.5f;   // 反弹衰减 (能量损失)
    private static final float AIR_FRICTION = 0.98f;    // 空气阻力
    private static final float GRAB_FRICTION = 0.85f;   // 抓取时的阻力
    private static final float ADHERE_SPEED = 0.1f;     // 吸附到墙面的速度
    private static final int THROW_THRESHOLD = 5;       // 判定为投掷的最小速度

    // 状态枚举
    private enum PositionState {
        AIR,        // 空中/自由落体
        FLOOR,      // 地面
        CEILING,    // 天花板
        WALL_LEFT,  // 左墙壁
        WALL_RIGHT  // 右墙壁
    }

    // 动画状态枚举
    private enum AnimationState {
        IDLE,       // 待机 (对应各位置的静止)
        MOVE,       // 移动 (对应各位置的爬行/行走)
        FALL,       // 下落
        ACTION_1,   // 动作1 (例如：扭动)
        ACTION_2    // 动作2 (例如：夹取)
    }

    private Context context;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;

    // 屏幕与尺寸
    private int screenWidth, screenHeight;
    private int characterWidth, characterHeight;

    // 物理变量
    private float x, y;           // 当前位置
    private float vx, vy;         // 当前速度
    private PositionState posState = PositionState.AIR;
    private AnimationState animState = AnimationState.FALL;

    // 资源管理
    private Bitmap[] idleFrames;    // 地面站立/爬行
    private Bitmap[] fallFrames;    // 下落
    private Bitmap[] wallFrames;   // 墙壁吸附
    private Bitmap[] ceilFrames;   // 天花板吸附
    private SoundPool soundPool;
    private List<Integer> soundIds;
    private Random random = new Random();

    // 交互控制
    private boolean isDragging = false;
    private float lastTouchX, lastTouchY;
    private long lastTouchTime;

    // 动画控制
    private int currentFrameIndex = 0;
    private long lastFrameTime = 0;
    private int frameInterval = 150; // 毫秒

    // AI 行为控制
    private long lastActionTime = 0;
    private long nextActionInterval = 2000;

    // 默认构造
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
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // 初始位置：屏幕上方
        characterWidth = 200;
        characterHeight = 200;
        x = screenWidth / 2 - characterWidth / 2;
        y = -characterHeight;
        vx = 0;
        vy = 2; // 初始微弱下落

        loadResources();
        startGameLoop();
    }

    private void loadResources() {
        String packageName = context.getPackageName();

        // 这里需要确保 drawable 中有对应的图片，如果找不到会报错，请根据实际素材调整
        idleFrames = loadFrames(new String[]{"stand_1", "walk_1", "walk_2"}, packageName);
        fallFrames = loadFrames(new String[]{"fall_1"}, packageName);
        wallFrames = loadFrames(new String[]{"climb_1", "climb_2"}, packageName); // 假设爬墙用这组
        ceilFrames = loadFrames(new String[]{"ceil_1", "ceil_2"}, packageName);   // 假设天花板用这组

        // 初始化声音
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build();

        soundIds = new ArrayList<>();
        String[] sounds = {"start", "sit", "sound_double", "puru_none", "wula_cute"}; // 示例声音
        for (String s : sounds) {
            int id = getResources().getIdentifier(s, "raw", packageName);
            if (id > 0) soundIds.add(soundPool.load(context, id, 1));
        }
    }

    private Bitmap[] loadFrames(String[] names, String pkg) {
        Bitmap[] frames = new Bitmap[names.length];
        for (int i = 0; i < names.length; i++) {
            int resId = getResources().getIdentifier(names[i], "drawable", pkg);
            if (resId != 0) {
                Bitmap bmp = BitmapFactory.decodeResource(getResources(), resId);
                frames[i] = bmp;
                if (bmp != null) {
                    characterWidth = Math.max(characterWidth, bmp.getWidth());
                    characterHeight = Math.max(characterHeight, bmp.getHeight());
                }
            } else {
                // 如果找不到图片，创建一个默认的粉色矩形代替，防止崩溃
                frames[i] = createPlaceholderBitmap(characterWidth, characterHeight);
            }
        }
        return frames;
    }

    private Bitmap createPlaceholderBitmap(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(0xFFFFC0CB); // Pink
        return bmp;
    }

    private void startGameLoop() {
        final Handler handler = new Handler();
        handler.post(new Runnable() {
            @Override
            public void run() {
                updatePhysics();
                updateAI();
                updateAnimation();
                updateWindowLayout();
                invalidate();
                handler.postDelayed(this, 16); // ~60 FPS
            }
        });
    }

    // --- 核心物理逻辑 ---
    private void updatePhysics() {
        if (isDragging) {
            // 拖拽时，速度归零（由投掷逻辑计算瞬时速度）
            vx = 0;
            vy = 0;
            return;
        }

        // 1. 应用重力
        vy += GRAVITY;

        // 2. 应用空气阻力
        vx *= AIR_FRICTION;
        vy *= AIR_FRICTION;

        // 3. 预测下一帧位置
        float nextX = x + vx;
        float nextY = y + vy;

        // --- 边缘碰撞检测与吸附逻辑 ---

        // 地面检测
        if (nextY >= screenHeight - characterHeight) {
            nextY = screenHeight - characterHeight;
            // 只有当速度足够大时才反弹，否则吸附
            if (vy > 8) {
                vy = -vy * BOUNCE_DAMPING;
                playSound("bounce");
                triggerImpact();
            } else {
                vy = 0;
                if (posState != PositionState.FLOOR) {
                    posState = PositionState.FLOOR;
                    animState = AnimationState.IDLE;
                    playSound("land");
                }
            }
        }
        // 天花板检测
        else if (nextY <= 0) {
            nextY = 0;
            if (vy < -5) {
                vy = -vy * BOUNCE_DAMPING; // 反弹
                triggerImpact();
            } else {
                vy = 0;
                if (posState != PositionState.CEILING) {
                    posState = PositionState.CEILING;
                    animState = AnimationState.IDLE;
                    playSound("land");
                }
            }
        }
        // 如果既不在地面也不在天花板，且垂直速度很小，视为在空中
        else {
            if (posState == PositionState.FLOOR || posState == PositionState.CEILING) {
                if (Math.abs(vy) > 1) {
                    posState = PositionState.AIR;
                    animState = AnimationState.FALL;
                }
            }
        }

        // 左墙壁检测
        if (nextX <= 0) {
            nextX = 0;
            if (vx < -5) {
                vx = -vx * BOUNCE_DAMPING;
                triggerImpact();
            } else {
                vx = 0;
                if (posState != PositionState.WALL_LEFT && posState != PositionState.FLOOR && posState != PositionState.CEILING) {
                    posState = PositionState.WALL_LEFT;
                    animState = AnimationState.IDLE;
                    playSound("climb");
                }
            }
        }
        // 右墙壁检测
        else if (nextX >= screenWidth - characterWidth) {
            nextX = screenWidth - characterWidth;
            if (vx > 5) {
                vx = -vx * BOUNCE_DAMPING;
                triggerImpact();
            } else {
                vx = 0;
                if (posState != PositionState.WALL_RIGHT && posState != PositionState.FLOOR && posState != PositionState.CEILING) {
                    posState = PositionState.WALL_RIGHT;
                    animState = AnimationState.IDLE;
                    playSound("climb");
                }
            }
        } else {
            // 如果在水平方向中间，且之前吸附在墙上，现在掉下来了
            if ((posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) && Math.abs(vy) > 1) {
                posState = PositionState.AIR;
                animState = AnimationState.FALL;
            }
        }

        // 4. 更新位置
        x = nextX;
        y = nextY;

        // 5. 自动吸附修正 (吸附在墙/天花板时的微调)
        if (!isDragging) {
            if (posState == PositionState.WALL_LEFT && x > 0) x += (0 - x) * ADHERE_SPEED;
            if (posState == PositionState.WALL_RIGHT && x < screenWidth - characterWidth)
                x += ((screenWidth - characterWidth) - x) * ADHERE_SPEED;
            if (posState == PositionState.CEILING && y > 0) y += (0 - y) * ADHERE_SPEED;
            if (posState == PositionState.FLOOR && y < screenHeight - characterHeight)
                y += ((screenHeight - characterHeight) - y) * ADHERE_SPEED;
        }
    }

    // --- AI 行为逻辑 ---
    private void updateAI() {
        if (isDragging) return;

        long now = System.currentTimeMillis();
        if (now - lastActionTime > nextActionInterval) {
            lastActionTime = now;
            nextActionInterval = 2000 + random.nextInt(4000); // 2-6秒随机动作

            // 只有在静止状态（非下落）才做随机动作
            if (posState != PositionState.AIR) {
                int action = random.nextInt(10);
                if (action < 5) {
                    // 保持静止/微动
                    animState = AnimationState.IDLE;
                } else if (action < 8) {
                    // 爬行/走动 (改变速度)
                    animState = AnimationState.MOVE;
                    float speed = 2 + random.nextFloat() * 3;

                    if (posState == PositionState.FLOOR) {
                        vx = random.nextBoolean() ? speed : -speed;
                    } else if (posState == PositionState.CEILING) {
                        vx = random.nextBoolean() ? speed : -speed;
                    } else if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                        vy = speed; // 沿墙上下爬
                    }
                } else {
                    // 特殊动作 (扭动等)
                    animState = AnimationState.ACTION_1;
                    playRandomSound();
                    // 动作结束后停止速度
                    new Handler().postDelayed(() -> {
                        if (!isDragging && posState != PositionState.AIR) {
                            vx = 0;
                            vy = 0;
                            animState = AnimationState.IDLE;
                        }
                    }, 1000);
                }
            }
        }

        // 移动状态下的持续逻辑
        if (animState == AnimationState.MOVE) {
            // 如果撞墙了，AI自动反向
            if (posState == PositionState.FLOOR || posState == PositionState.CEILING) {
                if ((x <= 0 && vx < 0) || (x >= screenWidth - characterWidth && vx > 0)) {
                    vx = -vx;
                }
            } else if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                // 沿墙爬到头了自动下来
                if ((y <= 0 && vy < 0) || (y >= screenHeight - characterHeight && vy > 0)) {
                    posState = PositionState.AIR; // 放弃吸附，掉下去
                    animState = AnimationState.FALL;
                    vx = (posState == PositionState.WALL_LEFT) ? 2 : -2; // 轻轻推离墙壁
                }
            }
        }
    }

    private void updateAnimation() {
        long now = System.currentTimeMillis();
        if (now - lastFrameTime > frameInterval) {
            lastFrameTime = now;
            currentFrameIndex++;

            // 获取当前动画长度
            Bitmap[] frames = getCurrentBitmaps();
            if (frames != null && frames.length > 0) {
                if (currentFrameIndex >= frames.length) {
                    currentFrameIndex = 0;
                }
            }
        }
    }

    private Bitmap[] getCurrentBitmaps() {
        // 优先根据动画状态选图，如果没有特定动画图，回退到位置图
        switch (animState) {
            case FALL: return fallFrames;
            case ACTION_1:
            case ACTION_2: return idleFrames; // 暂时复用
            case MOVE:
                if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT || posState == PositionState.CEILING) {
                    return wallFrames; // 爬行图
                }
                return idleFrames; // 走路图
            default: // IDLE
                if (posState == PositionState.CEILING) return ceilFrames;
                if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) return wallFrames;
                return idleFrames;
        }
    }

    private void updateWindowLayout() {
        if (layoutParams == null) layoutParams = (WindowManager.LayoutParams) getLayoutParams();
        if (layoutParams != null) {
            layoutParams.x = (int) x;
            layoutParams.y = (int) y;
            windowManager.updateViewLayout(this, layoutParams);
        }
    }

    // --- 绘制与触摸 ---

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap[] frames = getCurrentBitmaps();
        if (frames == null || frames.length == 0) return;

        Bitmap bitmap = frames[currentFrameIndex % frames.length];
        if (bitmap != null) {
            canvas.save();

            // 根据位置进行旋转
            float pivotX = characterWidth / 2f;
            float pivotY = characterHeight / 2f;

            if (posState == PositionState.CEILING) {
                // 天花板：倒转
                canvas.rotate(180, pivotX, pivotY);
            } else if (posState == PositionState.WALL_LEFT) {
                // 左墙：顺时针90度
                canvas.rotate(90, pivotX, pivotY);
            } else if (posState == PositionState.WALL_RIGHT) {
                // 右墙：逆时针90度
                canvas.rotate(-90, pivotX, pivotY);
            }

            // 绘制图片
            canvas.drawBitmap(bitmap, 0, 0, new Paint());
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float rawX = event.getRawX();
        float rawY = event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDragging = true;
                lastTouchX = rawX;
                lastTouchY = rawY;
                lastTouchTime = System.currentTimeMillis();
                vx = 0;
                vy = 0;
                playRandomSound();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    // 计算瞬时速度（用于投掷）
                    long now = System.currentTimeMillis();
                    float dt = now - lastTouchTime;
                    if (dt > 0) {
                        vx = (rawX - lastTouchX); // 简单的速度计算
                        vy = (rawY - lastTouchY);
                        lastTouchTime = now;
                    }

                    x = rawX;
                    y = rawY;

                    // 拖拽时脱离吸附状态
                    if (posState != PositionState.AIR) {
                        posState = PositionState.AIR;
                        animState = AnimationState.FALL;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                isDragging = false;
                // 应用投掷力度（放大一点手感更好）
                vx = vx * 1.5f;
                vy = vy * 1.5f;

                // 如果速度太小，视为点击/放下
                if (Math.abs(vx) < THROW_THRESHOLD && Math.abs(vy) < THROW_THRESHOLD) {
                    vx = 0;
                    vy = 0;
                    animState = AnimationState.IDLE;
                    // 如果在边缘松手，尝试吸附
                    checkEdgeAdhere();
                } else {
                    // 投出去了
                    posState = PositionState.AIR;
                    animState = AnimationState.FALL;
                }
                break;
        }
        return true;
    }

    // 辅助：手动检测吸附（用于拖拽后低速释放）
    private void checkEdgeAdhere() {
        if (y >= screenHeight - characterHeight - 20) {
            posState = PositionState.FLOOR;
        } else if (y <= 20) {
            posState = PositionState.CEILING;
        } else if (x <= 20) {
            posState = PositionState.WALL_LEFT;
        } else if (x >= screenWidth - characterWidth - 20) {
            posState = PositionState.WALL_RIGHT;
        } else {
            posState = PositionState.AIR;
        }
    }

    // --- 杂项 ---

    private void triggerImpact() {
        // 震动反馈
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(50);
                }
            }
        } catch (Exception e) {
            // 忽略权限错误
        }
    }

    private void playSound(String type) {
        // 简单示例，实际可扩展
        if (random.nextFloat() < 0.3f) playRandomSound();
    }

    private void playRandomSound() {
        if (soundIds == null || soundIds.isEmpty()) return;
        int soundId = soundIds.get(random.nextInt(soundIds.size()));
        soundPool.play(soundId, 1.0f, 1.0f, 0, 0, 1.0f);
    }
}