package com.example.usagi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class UsagiView extends View {

    // 物理与状态常量
    private static final float GRAVITY = 0.6f;          // 重力加速度
    private static final float BOUNCE_DAMPING = 0.5f;   // 反弹衰减 (能量损失)
    private static final float AIR_FRICTION = 0.98f;    // 空气阻力
    private static final float GRAB_FRICTION = 0.85f;   // 抓取时的阻力
    private static final float ADHERE_SPEED = 0.1f;     // 吸附到墙面的速度
    private static final float EDGE_SNAP_EPS = 6f;      // 靠边自动吸附的阈值（像素）
    private static final int ADHERE_DRAW_OFFSET = 94;   // 吸附时贴图的绘制偏移（像素）
    private static final int THROW_THRESHOLD = 5;       // 判定为投掷的最小速度
    private static final String TAG = "UsagiView";

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
        CREEP,      // 吸附在地面（爬行姿态）
        TWIST,      // 在地面上转身一圈（站立状态触发）
        TIP,        // 在地面上脚交叉站立
        ACTION_1,   // 动作1 (例如：夹取)
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
    private AnimationState prevAnimState = null; // 用于检测动画状态变化，从而重置帧索引

    // 资源管理
    private Bitmap[] idleFrames;    // 地面站立
    private Bitmap[] walkFrames;    // 行走动画
    private Bitmap[] fallFrames;    // 下落
    private Bitmap[] wallFrames;   // 墙壁吸附
    private Bitmap[] ceilFrames;   // 天花板吸附
    private Bitmap[] creepFrames;   // 地面爬行
    private Bitmap[] twistFrames;   // 转身一圈
    private Bitmap[] tipFrames;     // 脚交叉站立
    private SoundPool soundPool;
    private List<Integer> soundIds;
    private Map<String, Integer> soundNameToId;
    private List<Integer> activeSoundStreams = new ArrayList<>();
    private final Object soundPlayLock = new Object();
    private long lastSoundPlayTime = 0;
    private static final long SOUND_MIN_INTERVAL_MS = 300; // ms
    private Random random = new Random();

    // 后台线程：游戏循环、资源加载、音效播放
    private HandlerThread gameThread;
    private Handler gameHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long GAME_FRAME_MS = 16; // ~60 FPS
    private volatile boolean isRunning = false;
    private volatile boolean resourcesLoaded = false;

    // 定时播放控制
    private long nextScheduledSoundTime = 0;
    private static final int SCHEDULE_MIN_MS = 20 * 1000; // 20秒
    private static final int SCHEDULE_MAX_MS = 30 * 1000; // 30秒

    // 交互控制
    private boolean isDragging = false;
    private float lastTouchX, lastTouchY;
    private long lastTouchTime;
    // 拖拽时的触点相对视图偏移（用于精确定位，避免贴边判定偏差）
    private float dragOffsetX = 0;
    private float dragOffsetY = 0;

    // 动画控制
    private int currentFrameIndex = 0;
    private long lastFrameTime = 0;
    private int frameInterval = 120; // 毫秒（稍快的帧率让行走更流畅）

    // 方向枚举（用于区分左右贴图）
    private enum Direction {LEFT, RIGHT, NONE}

    // 区分左右的贴图资源
    private Bitmap[] walkLeftFrames;    // 向左走
    private Bitmap[] walkRightFrames;   // 向右走
    private Bitmap[] wallLeftFrames;    // 靠左吸附
    private Bitmap[] wallRightFrames;   // 靠右吸附
    private boolean useFlipForLeft = false;

    // AI 行为控制
    private long lastActionTime = 0;
    private long nextActionInterval = 2000;
    private boolean isMoving = false; // 标记是否正在进行强制移动
    private float moveStartX = 0;
    private float moveStartY = 0;
    private float targetMoveDistance = 0;
    private float moveSpeed = 0;
    private Direction lastMoveDirection = Direction.NONE;

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

        characterWidth = 128;
        characterHeight = 128;
        x = screenWidth / 2 - characterWidth / 2;
        y = screenHeight / 2 - characterHeight / 2;
        vx = 0;
        vy = 0;

        startGameLoop();
        loadResourcesAsync();
        scheduleNextSound();

        if (layoutParams == null) {
            layoutParams = (WindowManager.LayoutParams) getLayoutParams();
        }
        if (layoutParams != null) {
            layoutParams.x = (int) x;
            layoutParams.y = (int) y;
            windowManager.updateViewLayout(this, layoutParams);
        }
        startGameLoop();
    }

    // ... (loadResources, createPlaceholderBitmap 等辅助函数保持不变) ...
    // 为节省篇幅，省略未变动的资源加载代码，实际使用时请保留原代码中的 loadResources 相关方法

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
                frames[i] = createPlaceholderBitmap(characterWidth, characterHeight);
            }
        }
        return frames;
    }

    // 这里的 loadResources, loadResourcesAsync, loadFramesIfExists, flipBitmaps 保持原样
    // 假设这些方法已经正确定义在类中，此处不再重复以突出修改点

    private Bitmap createPlaceholderBitmap(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(0xFFFFC0CB);
        return bmp;
    }

    // 插入原代码中缺失的资源加载逻辑片段 (为了完整性)
    private void loadResources() {
        String packageName = context.getPackageName();
        idleFrames = loadFrames(new String[]{"stand_1"}, packageName);
        walkLeftFrames = loadFramesIfExists(new String[]{"walk_left_1", "walk_left_2"}, packageName);
        walkRightFrames = loadFramesIfExists(new String[]{"walk_right_1", "walk_right_2"}, packageName);
        if (walkLeftFrames == null && walkRightFrames == null) {
            Bitmap[] commonWalk = loadFramesIfExists(new String[]{"walk_1", "walk_2"}, packageName);
            if (commonWalk != null) {
                walkRightFrames = commonWalk;
                walkLeftFrames = flipBitmaps(commonWalk);
            }
        } else if (walkLeftFrames == null && walkRightFrames != null) {
            walkLeftFrames = flipBitmaps(walkRightFrames);
        } else if (walkRightFrames == null && walkLeftFrames != null) {
            walkRightFrames = flipBitmaps(walkLeftFrames);
        }
        fallFrames = loadFramesIfExists(new String[]{"fall_1"}, packageName);
        ceilFrames = loadFramesIfExists(new String[]{"ceil_1", "ceil_2"}, packageName);
        creepFrames = loadFramesIfExists(new String[]{"creep_1", "creep_2"}, packageName);
        twistFrames = loadFramesIfExists(new String[]{"twist_1", "twist_2", "twist_3", "twist_4"}, packageName);
        tipFrames = loadFramesIfExists(new String[]{"tip_1"}, packageName);
        wallLeftFrames = loadFramesIfExists(new String[]{"climb_left_1", "climb_left_2"}, packageName);
        wallRightFrames = loadFramesIfExists(new String[]{"climb_right_1", "climb_right_2"}, packageName);
        if (wallLeftFrames == null && wallRightFrames == null) {
            Bitmap[] commonWall = loadFramesIfExists(new String[]{"climb_1", "climb_2"}, packageName);
            if (commonWall != null) {
                wallRightFrames = commonWall;
                wallLeftFrames = flipBitmaps(commonWall);
            }
        } else if (wallLeftFrames == null && wallRightFrames != null) {
            wallLeftFrames = flipBitmaps(wallRightFrames);
        } else if (wallRightFrames == null && wallLeftFrames != null) {
            wallRightFrames = flipBitmaps(wallLeftFrames);
        }
        if (idleFrames == null) idleFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (walkLeftFrames == null && walkRightFrames == null) {
            Bitmap placeholder = createPlaceholderBitmap(characterWidth, characterHeight);
            walkRightFrames = new Bitmap[]{placeholder};
            walkLeftFrames = flipBitmaps(walkRightFrames);
        }
        if (fallFrames == null) fallFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (ceilFrames == null) ceilFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (wallLeftFrames == null && wallRightFrames == null) {
            Bitmap placeholder = createPlaceholderBitmap(characterWidth, characterHeight);
            wallRightFrames = new Bitmap[]{placeholder};
            wallLeftFrames = flipBitmaps(wallRightFrames);
        }
        if (creepFrames == null) creepFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (twistFrames == null) twistFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (tipFrames == null) tipFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(audioAttributes)
                .build();
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status != 0) {
                Log.w(TAG, "SoundPool failed to load sampleId=" + sampleId);
                if (soundIds != null) soundIds.remove(Integer.valueOf(sampleId));
                if (soundNameToId != null) {
                    List<String> toRemove = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : soundNameToId.entrySet()) {
                        if (e.getValue() == sampleId) toRemove.add(e.getKey());
                    }
                    for (String k : toRemove) soundNameToId.remove(k);
                }
            }
        });
        soundIds = new ArrayList<>();
        soundNameToId = new HashMap<>();
        try {
            Class<?> rawClass = Class.forName(packageName + ".R$raw");
            Field[] fields = rawClass.getFields();
            for (Field f : fields) {
                String name = f.getName();
                int resId = getResources().getIdentifier(name, "raw", packageName);
                if (resId != 0) {
                    boolean ok = false;
                    java.io.InputStream is = null;
                    try {
                        is = getResources().openRawResource(resId);
                        byte[] header = new byte[12];
                        int read = is.read(header);
                        if (read >= 4) {
                            String s = new String(header, 0, Math.min(read, 12));
                            if (s.startsWith("RIFF") || s.startsWith("OggS") || s.startsWith("fLaC") || s.startsWith("ID3") || (header[0] == (byte)0xFF)) {
                                ok = true;
                            }
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "Error reading header", ex);
                    } finally {
                        if (is != null) try { is.close(); } catch (Exception ignored) {}
                    }
                    if (ok) {
                        int spId = soundPool.load(context, resId, 1);
                        soundIds.add(spId);
                        soundNameToId.put(name, spId);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Reflection load sounds failed", e);
        }
    }

    private void loadResourcesAsync() {
        if (gameHandler != null) {
            gameHandler.post(() -> {
                loadResources();
                resourcesLoaded = true;
                mainHandler.post(this::postInvalidate);
            });
        } else {
            new Thread(() -> {
                loadResources();
                resourcesLoaded = true;
                mainHandler.post(this::postInvalidate);
            }, "UsagiResourceLoader").start();
        }
    }

    private Bitmap[] loadFramesIfExists(String[] names, String pkg) {
        int resId = getResources().getIdentifier(names[0], "drawable", pkg);
        if (resId == 0) return null;
        return loadFrames(names, pkg);
    }

    private Bitmap[] flipBitmaps(Bitmap[] src) {
        if (src == null) return null;
        Bitmap[] out = new Bitmap[src.length];
        Matrix m = new Matrix();
        m.preScale(-1, 1);
        for (int i = 0; i < src.length; i++) {
            Bitmap s = src[i];
            out[i] = s != null ? Bitmap.createBitmap(s, 0, 0, s.getWidth(), s.getHeight(), m, false) : null;
        }
        return out;
    }

    private void startGameLoop() {
        if (isRunning) return;
        gameThread = new HandlerThread("UsagiGameThread");
        gameThread.start();
        gameHandler = new Handler(gameThread.getLooper());
        isRunning = true;

        final Runnable gameLoop = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                updatePhysics();
                updateAI();
                updateAnimation();
                mainHandler.post(() -> {
                    updateWindowLayout();
                    postInvalidateOnAnimation();
                });
                if (isRunning && gameHandler != null) {
                    gameHandler.postDelayed(this, GAME_FRAME_MS);
                }
            }
        };
        gameHandler.post(gameLoop);
    }

    private void stopGameLoop() {
        isRunning = false;
        if (gameHandler != null) {
            gameHandler.removeCallbacksAndMessages(null);
        }
        if (gameThread != null) {
            gameThread.quitSafely();
            try { gameThread.join(); } catch (InterruptedException ignored) {}
            gameThread = null;
            gameHandler = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopGameLoop();
    }

    // --- 核心物理逻辑 (已修正) ---
    private void updatePhysics() {
        if (isDragging) {
            vx = 0;
            vy = 0;
            return;
        }

        // 1. 应用重力
        boolean isAdhered = (posState == PositionState.FLOOR ||
                posState == PositionState.CEILING ||
                posState == PositionState.WALL_LEFT ||
                posState == PositionState.WALL_RIGHT);

        if (!isAdhered) {
            vy += GRAVITY;
        }

        // 2. 应用空气阻力
        vx *= AIR_FRICTION;
        vy *= AIR_FRICTION;

        // 3. 预测下一帧位置
        float nextX = x + vx;
        float nextY = y + vy;

        // --- 边缘碰撞检测与吸附逻辑 ---

        // 天花板检测：优先于吸附判定，确保从墙壁爬上来时能抓住天花板
        if (nextY <= 0) {
            nextY = 0;
            if (vy < -2) { // 只有速度足够大才反弹
                vy = -vy * BOUNCE_DAMPING;
                triggerImpact();
            } else {
                vy = 0;
                if (posState != PositionState.CEILING) {
                    posState = PositionState.CEILING;
                    // 切换状态时如果正在移动，保持移动状态，但物理速度需清零由AI接管
                    if (animState == AnimationState.FALL) animState = AnimationState.IDLE;
                }
            }
        }
        // 地面检测
        else if (nextY >= screenHeight - characterHeight) {
            nextY = screenHeight - characterHeight;
            if (vy > 8) {
                vy = -vy * BOUNCE_DAMPING;
                triggerImpact();
            } else {
                vy = 0;
                if (posState != PositionState.FLOOR) {
                    posState = PositionState.FLOOR;
                    if (animState == AnimationState.FALL) animState = AnimationState.IDLE;
                }
            }
        }
        // 中间区域处理：如果之前在地面或天花板，现在有明显的垂直速度，则进入空中
        else if (Math.abs(vy) > 1.0f) {
            if (posState == PositionState.FLOOR || posState == PositionState.CEILING) {
                posState = PositionState.AIR;
                animState = AnimationState.FALL;
            }
        }

        // 左墙壁检测
        if (nextX <= 0) {
            nextX = 0;
            if (vx < -2) {
                vx = -vx * BOUNCE_DAMPING;
                triggerImpact();
            } else {
                vx = 0;
                if (posState != PositionState.WALL_LEFT) {
                    posState = PositionState.WALL_LEFT;
                    if (animState == AnimationState.FALL) animState = AnimationState.IDLE;
                }
            }
        }
        // 右墙壁检测
        else if (nextX >= screenWidth - characterWidth) {
            nextX = screenWidth - characterWidth;
            if (vx > 2) {
                vx = -vx * BOUNCE_DAMPING;
                triggerImpact();
            } else {
                vx = 0;
                if (posState != PositionState.WALL_RIGHT) {
                    posState = PositionState.WALL_RIGHT;
                    if (animState == AnimationState.FALL) animState = AnimationState.IDLE;
                }
            }
        }
        // 水平中间区域处理
        else {
            if (Math.abs(vx) > 1.0f) {
                // 如果之前在墙壁，现在水平速度变大（通常是被弹开），进入空中
                if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                    posState = PositionState.AIR;
                    animState = AnimationState.FALL;
                }
            }
        }

        // 4. 更新位置
        x = nextX;
        y = nextY;

        // 5. 自动吸附修正
        if (!isDragging) {
            if (posState == PositionState.WALL_LEFT && x > 0) x += (0 - x) * ADHERE_SPEED;
            if (posState == PositionState.WALL_RIGHT && x < screenWidth - characterWidth)
                x += ((screenWidth - characterWidth) - x) * ADHERE_SPEED;
            if (posState == PositionState.CEILING && y > 0) y += (0 - y) * ADHERE_SPEED;
            if (posState == PositionState.FLOOR && y < screenHeight - characterHeight)
                y += ((screenHeight - characterHeight) - y) * ADHERE_SPEED;
        }
    }

    // --- AI 行为逻辑 (已修正) ---
    // --- AI 行为逻辑 (已修正：修复墙壁切天花板速度方向错误) ---
    private void updateAI() {
        if (isDragging) return;

        // 处理正在进行的移动
        if (isMoving) {
            // 1. 天花板移动逻辑
            if (posState == PositionState.CEILING) {
                float distanceMoved = Math.abs(x - moveStartX);
                if (distanceMoved < targetMoveDistance) {
                    // 使用绝对值补速度，避免方向判断错误
                    if (Math.abs(vx) < Math.abs(moveSpeed) * 0.8f) {
                        vx = (moveSpeed > 0) ? Math.abs(moveSpeed) : -Math.abs(moveSpeed);
                    }
                } else {
                    isMoving = false;
                    vx = 0;
                    animState = AnimationState.IDLE;
                }
                return;
            }

            // 2. 墙壁移动逻辑
            if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                // 记录当前是哪一面墙，因为在切换状态后 posState 会变，需要提前记录
                final boolean wasLeftWall = (posState == PositionState.WALL_LEFT);

                float distanceMoved = Math.abs(y - moveStartY);
                if (distanceMoved < targetMoveDistance) {
                    // 修正墙壁移动时的垂直速度：确保方向正确（负数为上，正数为下）
                    // 如果是向上移动(moveSpeed < 0)，且速度衰减了，重置为向上
                    // 如果是向下移动(moveSpeed > 0)，且速度衰减了，重置为向下
                    float desiredVy = moveSpeed;
                    // 简单的修正逻辑：保持当前移动意图的方向
                    if (Math.abs(vy) < Math.abs(moveSpeed) * 0.8f) {
                        vy = desiredVy;
                    }

                    // --- 关键修正：墙壁顶部切换天花板 ---
                    if (y <= 0 && vy < 0) {
                        y = 0;
                        posState = PositionState.CEILING;
                        vy = 0; // 重置垂直速度

                        // 修正：强制使用绝对速度大小，并根据墙面手动分配方向
                        // 左墙上来 -> 向右 (vx > 0)
                        // 右墙上来 -> 向左 (vx < 0)
                        float absSpeed = Math.abs(moveSpeed);
                        vx = wasLeftWall ? absSpeed : -absSpeed;

                        // 重置移动起点和目标
                        moveStartX = x;
                        targetMoveDistance = screenWidth * 0.5f;
                        lastMoveDirection = wasLeftWall ? Direction.RIGHT : Direction.LEFT;
                        return;
                    }

                    // --- 墙壁底部切换地面 ---
                    if (y >= screenHeight - characterHeight && vy > 0) {
                        y = screenHeight - characterHeight;
                        posState = PositionState.FLOOR;
                        vy = 0;

                        // 修正：同上，根据墙面分配方向
                        float absSpeed = Math.abs(moveSpeed);
                        vx = wasLeftWall ? absSpeed : -absSpeed;

                        moveStartX = x;
                        targetMoveDistance = screenWidth * 0.5f;
                        lastMoveDirection = wasLeftWall ? Direction.RIGHT : Direction.LEFT;
                        return;
                    }

                } else {
                    isMoving = false;
                    vy = 0;
                    animState = AnimationState.IDLE;
                }
                return;
            }

            // 3. 地面移动逻辑
            if (posState == PositionState.FLOOR) {
                float distanceMoved = Math.abs(x - moveStartX);
                if (distanceMoved < targetMoveDistance) {
                    if (Math.abs(vx) < Math.abs(moveSpeed) * 0.8f) {
                        vx = (moveSpeed > 0) ? Math.abs(moveSpeed) : -Math.abs(moveSpeed);
                    }
                } else {
                    isMoving = false;
                    vx = 0;
                    animState = AnimationState.IDLE;
                }
            }

            // 处理地面撞墙转天花板
            if (posState == PositionState.FLOOR && animState == AnimationState.MOVE) {
                if (x <= 0 && vx < 0) {
                    x = 0;
                    posState = PositionState.WALL_LEFT;
                    vx = 0;
                    moveSpeed = -Math.abs(moveSpeed); // 强制向上（负数）
                    vy = moveSpeed;
                    moveStartY = y;
                    targetMoveDistance = screenHeight * 0.3f;
                } else if (x >= screenWidth - characterWidth && vx > 0) {
                    x = screenWidth - characterWidth;
                    posState = PositionState.WALL_RIGHT;
                    vx = 0;
                    moveSpeed = -Math.abs(moveSpeed); // 强制向上（负数）
                    vy = moveSpeed;
                    moveStartY = y;
                    targetMoveDistance = screenHeight * 0.3f;
                }
            }
            return;
        }

        // --- AI 决策逻辑 ---
        long now = System.currentTimeMillis();
        if (now - lastActionTime > nextActionInterval) {
            lastActionTime = now;
            nextActionInterval = 2000 + random.nextInt(4000);

            if (posState != PositionState.AIR) {
                int action = random.nextInt(10);
                if (action < 5) {
                    animState = AnimationState.IDLE;
                    vx = 0;
                    vy = 0;
                } else if (action < 8) {
                    // 移动
                    animState = AnimationState.MOVE;
                    float speed = 2 + random.nextFloat() * 3;
                    isMoving = true;

                    if (posState == PositionState.FLOOR) {
                        boolean moveRight = random.nextBoolean();
                        moveSpeed = moveRight ? speed : -speed;
                        vx = moveSpeed;
                        lastMoveDirection = moveRight ? Direction.RIGHT : Direction.LEFT;
                        moveStartX = x;
                        targetMoveDistance = screenWidth * 0.5f;
                    } else if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                        boolean moveDown = random.nextBoolean();
                        moveSpeed = moveDown ? speed : -speed;
                        vy = moveSpeed;
                        moveStartY = y;
                        targetMoveDistance = screenHeight * 0.3f;
                    } else if (posState == PositionState.CEILING) {
                        boolean moveRight = random.nextBoolean();
                        moveSpeed = moveRight ? speed : -speed;
                        vx = moveSpeed;
                        lastMoveDirection = moveRight ? Direction.RIGHT : Direction.LEFT;
                        moveStartX = x;
                        targetMoveDistance = screenWidth * 0.5f;
                    }
                } else {
                    // 特殊动作
                    if (posState == PositionState.FLOOR && animState == AnimationState.IDLE) {
                        int floorAction = random.nextInt(3);
                        if (floorAction == 0) {
                            animState = AnimationState.TWIST;
                            mainHandler.postDelayed(() -> { if(!isDragging && posState==PositionState.FLOOR) animState = AnimationState.IDLE; }, 2000);
                        } else if (floorAction == 1) {
                            animState = AnimationState.TIP;
                            mainHandler.postDelayed(() -> { if(!isDragging && posState==PositionState.FLOOR) animState = AnimationState.IDLE; }, 1500);
                        } else {
                            animState = AnimationState.CREEP;
                        }
                    } else {
                        animState = AnimationState.ACTION_1;
                        mainHandler.postDelayed(() -> {
                            if (!isDragging && posState != PositionState.AIR) {
                                vx = 0; vy = 0; animState = AnimationState.IDLE; isMoving = false;
                            }
                        }, 1000);
                    }
                }
            }
        }

        // --- 补充：天花板撞墙逻辑 ---
        if (isMoving && posState == PositionState.CEILING && animState == AnimationState.MOVE) {
            if (x <= 0 && vx < 0) {
                x = 0;
                posState = PositionState.WALL_LEFT;
                vx = 0;
                moveSpeed = Math.abs(moveSpeed); // 正数，向下
                vy = moveSpeed;
                moveStartY = y;
                targetMoveDistance = screenHeight * 0.3f;
            } else if (x >= screenWidth - characterWidth && vx > 0) {
                x = screenWidth - characterWidth;
                posState = PositionState.WALL_RIGHT;
                vx = 0;
                moveSpeed = Math.abs(moveSpeed); // 正数，向下
                vy = moveSpeed;
                moveStartY = y;
                targetMoveDistance = screenHeight * 0.3f;
            }
        }
    }

    private void updateAnimation() {
        long now = System.currentTimeMillis();
        boolean shouldAnimate = (animState == AnimationState.MOVE ||
                animState == AnimationState.CREEP ||
                animState == AnimationState.TWIST ||
                animState == AnimationState.TIP ||
                animState == AnimationState.FALL ||
                animState == AnimationState.ACTION_1 ||
                animState == AnimationState.ACTION_2);

        if (prevAnimState != animState) {
            currentFrameIndex = 0;
            lastFrameTime = now;
            prevAnimState = animState;
        }

        if (shouldAnimate && now - lastFrameTime > frameInterval) {
            lastFrameTime = now;
            currentFrameIndex++;
            Bitmap[] frames = getCurrentBitmaps();
            if (frames != null && frames.length > 0) {
                if (currentFrameIndex >= frames.length) currentFrameIndex = 0;
            }
        } else if (!shouldAnimate) {
            currentFrameIndex = 0;
        }
    }

    private Bitmap[] getCurrentBitmaps() {
        switch (animState) {
            case FALL: return fallFrames;
            case CREEP: return creepFrames;
            case TWIST: return twistFrames;
            case TIP: return tipFrames;
            case ACTION_1:
            case ACTION_2: return idleFrames;
            case MOVE:
                if (posState == PositionState.WALL_LEFT) return (wallRightFrames != null) ? wallRightFrames : wallLeftFrames;
                if (posState == PositionState.WALL_RIGHT) return (wallLeftFrames != null) ? wallLeftFrames : wallRightFrames;
                if (posState == PositionState.CEILING) return ceilFrames;

                Direction effectiveDir = lastMoveDirection;
                if (effectiveDir == Direction.NONE) {
                    if (vx < 0) effectiveDir = Direction.LEFT;
                    else if (vx > 0) effectiveDir = Direction.RIGHT;
                }
                if (effectiveDir == Direction.LEFT) return (walkRightFrames != null) ? walkRightFrames : walkLeftFrames;
                if (effectiveDir == Direction.RIGHT) return (walkLeftFrames != null) ? walkLeftFrames : walkRightFrames;
                return walkRightFrames != null ? walkRightFrames : walkLeftFrames;
            default:
                if (posState == PositionState.CEILING) return ceilFrames;
                if (posState == PositionState.WALL_LEFT) return (wallRightFrames != null) ? wallRightFrames : wallLeftFrames;
                if (posState == PositionState.WALL_RIGHT) return (wallLeftFrames != null) ? wallLeftFrames : wallRightFrames;
                return idleFrames;
        }
    }

    private void updateWindowLayout() {
        if (layoutParams == null) layoutParams = (WindowManager.LayoutParams) getLayoutParams();
        if (layoutParams != null) {
            int offsetX = 0;
            int offsetY = 0;
            if (posState == PositionState.WALL_LEFT) offsetX -= ADHERE_DRAW_OFFSET;
            else if (posState == PositionState.WALL_RIGHT) offsetX += ADHERE_DRAW_OFFSET;
            if (posState == PositionState.FLOOR) offsetY += ADHERE_DRAW_OFFSET;
            else if (posState == PositionState.CEILING) offsetY -= ADHERE_DRAW_OFFSET;

            layoutParams.x = (int) (x + offsetX);
            layoutParams.y = (int) (y + offsetY);
            windowManager.updateViewLayout(this, layoutParams);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap[] frames = getCurrentBitmaps();
        if (frames == null || frames.length == 0) return;

        Bitmap bitmap;
        float drawX = 0, drawY = 0;
        PositionState localPosState;
        AnimationState localAnimState;
        int localFrameIndex;

        synchronized (this) {
            localPosState = this.posState;
            localAnimState = this.animState;
            localFrameIndex = this.currentFrameIndex % frames.length;
            bitmap = frames[localFrameIndex];
        }

        if (bitmap != null) {
            canvas.save();
            if (localPosState == PositionState.WALL_LEFT) drawX = -ADHERE_DRAW_OFFSET;
            else if (localPosState == PositionState.WALL_RIGHT) drawX = ADHERE_DRAW_OFFSET;

            if (localPosState == PositionState.CEILING) {
                if (localAnimState == AnimationState.MOVE || localAnimState == AnimationState.IDLE) drawY = -ADHERE_DRAW_OFFSET - 16;
                else drawY = -ADHERE_DRAW_OFFSET;
            } else if (localPosState == PositionState.FLOOR) {
                if (localAnimState == AnimationState.MOVE || localAnimState == AnimationState.IDLE) drawY = -16;
                else drawY = ADHERE_DRAW_OFFSET;
            }

            canvas.drawBitmap(bitmap, drawX, drawY, new Paint());
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
                lastTouchX = rawX; lastTouchY = rawY;
                lastTouchTime = System.currentTimeMillis();
                dragOffsetX = event.getX(); dragOffsetY = event.getY();
                vx = 0; vy = 0;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    long now = System.currentTimeMillis();
                    float dt = now - lastTouchTime;
                    if (dt > 0) {
                        vx = (rawX - lastTouchX);
                        vy = (rawY - lastTouchY);
                        lastTouchTime = now;
                    }
                    x = rawX - dragOffsetX;
                    y = rawY - dragOffsetY;
                    if (posState != PositionState.AIR) {
                        posState = PositionState.AIR;
                        animState = AnimationState.FALL;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                isDragging = false;
                vx = vx * 1.5f; vy = vy * 1.5f;
                if (Math.abs(vx) < THROW_THRESHOLD && Math.abs(vy) < THROW_THRESHOLD) {
                    vx = 0; vy = 0; animState = AnimationState.IDLE;
                    checkEdgeAdhere();
                } else {
                    posState = PositionState.AIR;
                    animState = AnimationState.FALL;
                }
                break;
        }
        return true;
    }

    private void checkEdgeAdhere() {
        final float EPS = 2f; // 稍微宽容一点的吸附阈值
        // 优先判断天花板（防止被墙壁判定拦截）
        if (y <= EPS) {
            posState = PositionState.CEILING;
            y = 0;
        } else if (y >= screenHeight - characterHeight - EPS) {
            posState = PositionState.FLOOR;
            y = screenHeight - characterHeight;
        } else if (x <= EPS) {
            posState = PositionState.WALL_LEFT;
            x = 0;
        } else if (x >= screenWidth - characterWidth - EPS) {
            posState = PositionState.WALL_RIGHT;
            x = screenWidth - characterWidth;
        } else {
            posState = PositionState.AIR;
        }
    }

    private void triggerImpact() {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(50);
                }
            }
        } catch (Exception e) {}
    }

    private void playSound(final String type) {
        if (gameHandler != null && Looper.myLooper() != gameHandler.getLooper()) {
            gameHandler.post(() -> playSoundInternal(type));
        } else {
            playSoundInternal(type);
        }
    }

    private void playSoundInternal(String type) {
        if (soundPool == null) return;
        if (soundNameToId != null) {
            Integer id = soundNameToId.get(type);
            if (id != null) { playSoundOnce(id); return; }
        }
        playRandomSoundInternal();
    }

    private void playRandomSound() {
        if (gameHandler != null && Looper.myLooper() != gameHandler.getLooper()) {
            gameHandler.post(this::playRandomSoundInternal);
        } else {
            playRandomSoundInternal();
        }
    }

    private void playRandomSoundInternal() {
        if (soundIds == null || soundIds.isEmpty()) return;
        int soundId = soundIds.get(random.nextInt(soundIds.size()));
        playSoundOnce(soundId);
    }

    private void playSoundOnce(final int resId) {
        if (gameHandler != null && Looper.myLooper() != gameHandler.getLooper()) {
            gameHandler.post(() -> playSoundOnceInternal(resId));
        } else {
            playSoundOnceInternal(resId);
        }
    }

    private void playSoundOnceInternal(int resId) {
        if (soundPool == null) return;
        long now = System.currentTimeMillis();
        synchronized (soundPlayLock) {
            if (now - lastSoundPlayTime < SOUND_MIN_INTERVAL_MS) return;
            for (int sid : new ArrayList<>(activeSoundStreams)) {
                try { soundPool.stop(sid); } catch (Exception ignored) {}
            }
            activeSoundStreams.clear();
            int streamId = soundPool.play(resId, 1.0f, 1.0f, 0, 0, 1.0f);
            if (streamId != 0) activeSoundStreams.add(streamId);
            lastSoundPlayTime = now;
        }
    }

    private void scheduleNextSound() {
        long now = System.currentTimeMillis();
        int delay = SCHEDULE_MIN_MS + random.nextInt(SCHEDULE_MAX_MS - SCHEDULE_MIN_MS + 1);
        nextScheduledSoundTime = now + delay;
    }
}