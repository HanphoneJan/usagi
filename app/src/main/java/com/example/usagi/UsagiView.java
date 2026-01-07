package com.example.usagi;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class UsagiView extends View {

    // 物理与状态常量
    private static final float GRAVITY = 0.6f;
    private static final float BOUNCE_DAMPING = 0.5f;
    private static final float AIR_FRICTION = 0.98f;
    private static final float ADHERE_SPEED = 0.2f;
    private static final float EDGE_SNAP_EPS = 6f;
    private static final int ADHERE_DRAW_OFFSET_Y = 44;
    private static final int ADHERE_DRAW_OFFSET_X = 68;
    private static final int THROW_THRESHOLD = 5;

    // 状态枚举
    public enum State {
        FALLING, ADHERING, MOVING, IDLE, TWISTING, TIPPING, CREEPING
    }

    // 位置枚举
    public enum Position {
        AIR, FLOOR, CEILING, WALL_LEFT, WALL_RIGHT
    }

    private Context context;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private int screenWidth, screenHeight;
    private int characterWidth, characterHeight;
    private float x, y;
    private float vx, vy;

    private State currentState = State.FALLING;
    private Position currentPosition = Position.AIR;
    private Position targetAdherePosition = Position.AIR;

    private enum Direction {LEFT, RIGHT, UP, DOWN, NONE}
    private Direction currentDirection = Direction.NONE;
    private Direction lastNonNoneDirection = Direction.RIGHT;

    // 资源管理
    private Bitmap[] idleFrames, sitFrames, walkLeftFrames, walkRightFrames, fallFrames;
    private Bitmap[] wallLeftFrames, wallRightFrames, ceilLeftFrames, ceilRightFrames;
    private Bitmap[] creepLeftFrames, creepRightFrames;
    private Bitmap[] pinchLeftFrames, pinchRightFrames;
    private Bitmap[] bounceFrames, jumpFrames;
    private Bitmap[] twistFrames, tipFrames;
    private SoundPool soundPool;
    private List<Integer> soundIds;
    private Map<String, Integer> soundNameToId;
    private Map<String, List<Integer>> actionSounds = new HashMap<>();
    private List<Integer> activeSoundStreams = new ArrayList<>();
    private final Object soundPlayLock = new Object();
    private long lastSoundPlayTime = 0;
    private static final long SOUND_MIN_INTERVAL_MS = 30000;
    private Random random = new Random();

    // 线程控制
    private HandlerThread gameThread;
    private Handler gameHandler;
    private HandlerThread soundThread;
    private Handler soundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long GAME_FRAME_MS = 16;
    private volatile boolean isRunning = false;
    private volatile boolean resourcesLoaded = false;

    // 设置相关
    private SharedPreferences sharedPreferences;
    private int volume = 50;
    private int speed = 50;
    private boolean showUsagi = true;

    // 定时音效
    private static final int SCHEDULE_MIN_MS = 10 * 1000;
    private static final int SCHEDULE_MAX_MS = 25 * 1000;

    // 交互控制
    private boolean isDragging = false;
    private float lastTouchX, lastTouchY;
    private long lastTouchTime;
    private float dragOffsetX = 0, dragOffsetY = 0;

    // 动画控制
    private int currentFrameIndex = 0;
    private long lastFrameTime = 0;
    private int frameInterval = 120;

    // AI 行为控制
    private long lastActionTime = 0;
    private long nextActionInterval = 2000;
    private boolean isMoving = false; // 关键标志：是否正在执行AI移动指令
    private float moveStartX = 0, moveStartY = 0;
    private float targetMoveDistance = 0;
    private float moveSpeedMagnitude = 0;


    // Creep状态移动相关
    private long creepStartTime = 0;
    private static final long CREEP_DURATION_MS = 2000;
    private float creepSpeed = 1.5f;

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
        // 获取物理屏幕尺寸（包含状态栏、导航栏等整个屏幕区域）
        WindowMetrics windowMetrics = windowManager.getMaximumWindowMetrics();
        Rect bounds = windowMetrics.getBounds();
        screenWidth = bounds.width();
        screenHeight = bounds.height();

        characterWidth = 128;
        characterHeight = 128;
        x = screenWidth / 2 - characterWidth / 2;
        y = screenHeight / 2 - characterHeight / 2;
        vx = 0;
        vy = 0;

        loadSettings();
        startGameLoop();
        startSoundScheduler();
        loadResourcesAsync();
    }

    // --- 设置加载 ---
    private void loadSettings() {
        sharedPreferences = context.getSharedPreferences("usagi_settings", Context.MODE_PRIVATE);
        volume = sharedPreferences.getInt("volume", 50);
        speed = sharedPreferences.getInt("speed", 50);
        showUsagi = sharedPreferences.getBoolean("show_usagi", true);

        // 注册 SharedPreferences 变化监听器
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsListener);

        // 应用动画速度设置
        updateAnimationSpeed();

        // 应用显示/隐藏设置
        updateVisibility();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (sharedPreferences, key) -> {
                if ("volume".equals(key)) {
                    volume = sharedPreferences.getInt(key, 50);
                } else if ("speed".equals(key)) {
                    speed = sharedPreferences.getInt(key, 50);
                    updateAnimationSpeed();
                } else if ("show_usagi".equals(key)) {
                    showUsagi = sharedPreferences.getBoolean(key, true);
                    updateVisibility();
                }
            };

    private void updateAnimationSpeed() {
        // frameInterval 基础值120，速度50%时为120ms，速度0%时为240ms，速度100%时为60ms
        frameInterval = 240 - (speed * 180 / 100);
    }

    private void updateVisibility() {
        if (mainHandler != null) {
            mainHandler.post(() -> {
                if (layoutParams != null) {
                    if (showUsagi) {
                        setVisibility(VISIBLE);
                    } else {
                        setVisibility(GONE);
                    }
                } else {
                    setVisibility(showUsagi ? VISIBLE : GONE);
                }
            });
        } else {
            setVisibility(showUsagi ? VISIBLE : GONE);
        }
    }

    public void reloadSettings() {
        loadSettings();
    }

    // --- 资源加载 ---
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

    private Bitmap createPlaceholderBitmap(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(bmp).drawColor(0xFFFFC0CB);
        return bmp;
    }

    private void loadResources() {
        String packageName = context.getPackageName();
        idleFrames = loadFrames(new String[]{"stand_1"}, packageName);
        sitFrames = loadFramesIfExists(new String[]{"sit_1"}, packageName);
        
        walkLeftFrames = loadFramesIfExists(new String[]{"walk_1", "walk_2"}, packageName);
        walkRightFrames = loadFramesIfExists(new String[]{"walk_right_1", "walk_right_2"}, packageName);
        fallFrames = loadFramesIfExists(new String[]{"fall_1"}, packageName);
        bounceFrames = loadFramesIfExists(new String[]{"bounce_1", "bounce_2"}, packageName);
        jumpFrames = loadFramesIfExists(new String[]{"jump_1"}, packageName);
        
        ceilLeftFrames = loadFramesIfExists(new String[]{"ceil_1", "ceil_2", "ceil_3"}, packageName);
        ceilRightFrames = loadFramesIfExists(new String[]{"ceil_right_1", "ceil_right_2", "ceil_right_3"}, packageName);

        creepLeftFrames = loadFramesIfExists(new String[]{"creep_1", "creep_2"}, packageName);
        creepRightFrames = loadFramesIfExists(new String[]{"creep_right_1", "creep_right_2"}, packageName);
        pinchLeftFrames = loadFramesIfExists(new String[]{"pinch_left_1", "pinch_left_2", "pinch_left_3"}, packageName);
        pinchRightFrames = loadFramesIfExists(new String[]{"pinch_right_1", "pinch_right_2", "pinch_right_3"}, packageName);

        wallLeftFrames = loadFramesIfExists(new String[]{"climb_1", "climb_2", "climb_3"}, packageName);
        wallRightFrames = loadFramesIfExists(new String[]{"climb_right_1", "climb_right_2", "climb_right_3"}, packageName);

        twistFrames = loadFramesIfExists(new String[]{"twist_1", "twist_2", "twist_3", "twist_4"}, packageName);
        tipFrames = loadFramesIfExists(new String[]{"tip_1", "tip_2"}, packageName);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build();
        soundIds = new ArrayList<>();
        soundNameToId = new HashMap<>();
        
        // 初始化动作音效映射
        actionSounds.put("idle", new ArrayList<>());
        actionSounds.put("walk", new ArrayList<>());
        actionSounds.put("twist", new ArrayList<>());
        actionSounds.put("tip", new ArrayList<>());
        actionSounds.put("adhere", new ArrayList<>());
        actionSounds.put("ceil", new ArrayList<>());
        
        try {
            Class<?> c = Class.forName(packageName + ".R$raw");
            for (Field f : c.getFields()) {
                String name = f.getName();
                int id = getResources().getIdentifier(name, "raw", packageName);
                if (id > 0) {
                    int soundId = soundPool.load(context, id, 1);
                    soundIds.add(soundId);
                    soundNameToId.put(name, soundId);
                    
                    // 按动作类型分类
                    String actionType = getActionType(name);
                    if (actionType != null) {
                        actionSounds.get(actionType).add(soundId);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void loadResourcesAsync() {
        if (gameHandler != null) {
            gameHandler.post(() -> { loadResources(); resourcesLoaded = true; mainHandler.post(this::postInvalidate); });
        } else {
            new Thread(() -> { loadResources(); resourcesLoaded = true; mainHandler.post(this::postInvalidate); }).start();
        }
    }

    private Bitmap[] loadFramesIfExists(String[] names, String pkg) {
        return getResources().getIdentifier(names[0], "drawable", pkg) == 0 ? null : loadFrames(names, pkg);
    }

    // 根据文件名获取动作类型
    private String getActionType(String soundName) {
        if (soundName.startsWith("idle")) return "idle";
        if (soundName.startsWith("walk")) return "walk";
        if (soundName.startsWith("twist")) return "twist";
        if (soundName.startsWith("tip")) return "tip";
        if (soundName.startsWith("adhere")) return "adhere";
        if (soundName.startsWith("ceil")) return "ceil";
        return null;
    }

    // 根据当前状态获取对应的音效类型
    private String getSoundTypeForCurrentState() {
        switch (currentState) {
            case IDLE:
            case ADHERING:
                return "idle";
            case MOVING:
                // 根据当前位置判断
                if (currentPosition == Position.CEILING) return "ceil";
                if (currentPosition == Position.FLOOR) return "walk";
                return null; // 墙壁移动暂无对应音效
            case TWISTING:
                return "twist";
            case TIPPING:
                return "tip";
            case CREEPING:
                return "walk"; // 爬行使用行走音效
            default:
                return null;
        }
    }

    // 播放对应动作的音效
    private void playActionSound() {
        String soundType = getSoundTypeForCurrentState();
        if (soundType == null) return;
        
        List<Integer> sounds = actionSounds.get(soundType);
        if (sounds == null || sounds.isEmpty()) return;
        
        // 随机选择一个音效
        int randomSoundId = sounds.get(random.nextInt(sounds.size()));
        playSound(randomSoundId);
    }

    private void playSound(int soundId) {
        if (soundPool == null) return;
        
        mainHandler.post(() -> {
            synchronized (soundPlayLock) {
                long now = System.currentTimeMillis();
                if (now - lastSoundPlayTime < SOUND_MIN_INTERVAL_MS) {
                    return;
                }
                lastSoundPlayTime = now;
                
                // 应用音量设置
                float volumeValue = volume / 100.0f;
                
                int streamId = soundPool.play(soundId, volumeValue, volumeValue, 1, 0, 1.0f);
                
                if (streamId > 0) {
                    activeSoundStreams.add(streamId);
                    soundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
                        if (status == 0) {
                            // 声音加载完成，清理已播放的stream
                            new Handler().postDelayed(() -> {
                                synchronized (soundPlayLock) {
                                    activeSoundStreams.remove(Integer.valueOf(streamId));
                                }
                            }, 3000);
                        }
                    });
                }
            }
        });
    }

    // --- 核心逻辑 ---

    private void startGameLoop() {
        if (isRunning) return;
        gameThread = new HandlerThread("UsagiGameThread");
        gameThread.start();
        gameHandler = new Handler(gameThread.getLooper());
        isRunning = true;
        gameHandler.post(new Runnable() {
            @Override public void run() {
                if (!isRunning) return;
                updatePhysics();
                updateAI();
                updateAnimation();
                mainHandler.post(() -> { updateWindowLayout(); postInvalidateOnAnimation(); });
                gameHandler.postDelayed(this, GAME_FRAME_MS);
            }
        });
    }

    private void stopGameLoop() {
        isRunning = false;
        if (gameHandler != null) gameHandler.removeCallbacksAndMessages(null);
        if (gameThread != null) { gameThread.quitSafely(); try { gameThread.join(); } catch (Exception ignored) {} }
        if (soundHandler != null) soundHandler.removeCallbacksAndMessages(null);
        if (soundThread != null) { soundThread.quitSafely(); try { soundThread.join(); } catch (Exception ignored) {} }
        // 注销 SharedPreferences 监听器
        if (sharedPreferences != null && settingsListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        }
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); stopGameLoop(); }

    private void updatePhysics() {
        if (isDragging) { vx = 0; vy = 0; return; }

        // 【修复点2：添加CREEPING状态的移动处理】
        if (currentState == State.CREEPING) {
            // Creep状态在地面移动
            if (currentPosition == Position.FLOOR) {
                long now = System.currentTimeMillis();
                if (now - creepStartTime > CREEP_DURATION_MS) {
                    // Creep时间结束，回到IDLE状态
                    currentState = State.IDLE;
                    vx = 0;
                } else {
                    // 根据最后的方向移动
                    if (lastNonNoneDirection == Direction.LEFT) {
                        vx = -creepSpeed;
                    } else {
                        vx = creepSpeed;
                    }
                }
                vy = 0;
            }
        }

        // 如果处于AI移动状态，完全跳过重力、摩擦和自动吸附逻辑
        if (isMoving) {
            x += vx;
            y += vy;
            return;
        }

        // 只有在非移动状态才应用物理规则
        if (currentPosition == Position.AIR) {
            vy += GRAVITY;
        } else if (currentState == State.FALLING) {
            vy += GRAVITY;
        }

        vx *= AIR_FRICTION;
        vy *= AIR_FRICTION;

        float nextX = x + vx;
        float nextY = y + vy;

        boolean collisionOccurred = false;

        // 天花板碰撞
        if (nextY <= 0) {
            nextY = 0;
            if (vy < -2) {
                vy = -vy * BOUNCE_DAMPING;
                triggerImpact();
                currentState = State.FALLING;
                currentPosition = Position.AIR;
            } else {
                vy = 0;
                if (currentPosition != Position.CEILING) {
                    currentPosition = Position.CEILING;
                    targetAdherePosition = Position.CEILING;
                    if (Math.abs(vx) < 0.5f) currentState = State.ADHERING;
                }
            }
            collisionOccurred = true;
        }

        // 地面碰撞
        else if (nextY >= screenHeight - characterHeight) {
            nextY = screenHeight - characterHeight;
            if (vy > 8) {
                vy = -vy * BOUNCE_DAMPING;
                triggerImpact();
                currentState = State.FALLING;
                currentPosition = Position.AIR;
            } else {
                vy = 0;
                if (currentPosition != Position.FLOOR) {
                    currentPosition = Position.FLOOR;
                    targetAdherePosition = Position.FLOOR;
                    if (Math.abs(vx) < 0.5f) currentState = State.ADHERING;
                }
            }
            collisionOccurred = true;
        }

        // 左墙碰撞
        if (nextX <= 0) {
            nextX = 0;
            if (vx < -2) {
                vx = -vx * BOUNCE_DAMPING;
                triggerImpact();
                currentState = State.FALLING;
                currentPosition = Position.AIR;
            } else {
                vx = 0;
                if (currentPosition != Position.WALL_LEFT) {
                    currentPosition = Position.WALL_LEFT;
                    targetAdherePosition = Position.WALL_LEFT;
                    if (Math.abs(vy) < 0.5f) currentState = State.ADHERING;
                }
            }
            collisionOccurred = true;
        }

        // 右墙碰撞
        else if (nextX >= screenWidth - characterWidth) {
            nextX = screenWidth - characterWidth;
            if (vx > 2) {
                vx = -vx * BOUNCE_DAMPING;
                triggerImpact();
                currentState = State.FALLING;
                currentPosition = Position.AIR;
            } else {
                vx = 0;
                if (currentPosition != Position.WALL_RIGHT) {
                    currentPosition = Position.WALL_RIGHT;
                    targetAdherePosition = Position.WALL_RIGHT;
                    if (Math.abs(vy) < 0.5f) currentState = State.ADHERING;
                }
            }
            collisionOccurred = true;
        }

        x = nextX;
        y = nextY;

        // 自动吸附逻辑：仅当静止且非移动状态时触发
        if (!collisionOccurred && (Math.abs(vx) < 0.5f && Math.abs(vy) < 0.5f)) {
            checkAutoAdhere();
        }

        if (currentState == State.ADHERING && !isDragging) {
            performAdhesion();
        }
    }


    private void checkAutoAdhere() {
        if (currentState == State.ADHERING || currentState == State.MOVING) return;

        float minDist = Float.MAX_VALUE;
        Position closestPos = Position.AIR;

        float distToFloor = Math.abs(screenHeight - characterHeight - y);
        float distToCeiling = Math.abs(y);
        float distToLeftWall = Math.abs(x);
        float distToRightWall = Math.abs(screenWidth - characterWidth - x);

        if (distToFloor < EDGE_SNAP_EPS && distToFloor < minDist) {
            minDist = distToFloor; closestPos = Position.FLOOR;
        }
        if (distToCeiling < EDGE_SNAP_EPS && distToCeiling < minDist) {
            minDist = distToCeiling; closestPos = Position.CEILING;
        }
        if (distToLeftWall < EDGE_SNAP_EPS && distToLeftWall < minDist) {
            minDist = distToLeftWall; closestPos = Position.WALL_LEFT;
        }
        if (distToRightWall < EDGE_SNAP_EPS && distToRightWall < minDist) {
            minDist = distToRightWall; closestPos = Position.WALL_RIGHT;
        }

        if (closestPos != Position.AIR && closestPos != currentPosition) {
            currentPosition = closestPos;
            targetAdherePosition = closestPos;
            currentState = State.ADHERING;
            vx = 0; vy = 0;
            playActionSound(); // 播放吸附音效
        }
    }

    private void performAdhesion() {
        if (targetAdherePosition == Position.AIR) return;

        float targetX = x;
        float targetY = y;

        switch (targetAdherePosition) {
            case FLOOR:
                targetY = screenHeight - characterHeight;
                y += (targetY - y) * ADHERE_SPEED;
                if (Math.abs(targetY - y) < 1) y = targetY;
                break;
            case CEILING:
                targetY = 0;
                y += (targetY - y) * ADHERE_SPEED;
                if (Math.abs(targetY - y) < 1) y = targetY;
                break;
            case WALL_LEFT:
                targetX = 0;
                x += (targetX - x) * ADHERE_SPEED;
                if (Math.abs(targetX - x) < 1) x = targetX;
                break;
            case WALL_RIGHT:
                targetX = screenWidth - characterWidth;
                x += (targetX - x) * ADHERE_SPEED;
                if (Math.abs(targetX - x) < 1) x = targetX;
                break;
        }

        if (Math.abs(targetX - x) < 1 && Math.abs(targetY - y) < 1) {
            currentPosition = targetAdherePosition;
            if (!isMoving) currentState = State.IDLE;
        }
    }

    // --- AI 行为逻辑 ---
    private void updateAI() {
        if (isDragging) return;

        // 如果正在移动，只处理移动逻辑，跳过随机决策
        if (isMoving) {
            handleMovement();
            return;
        }

        long now = System.currentTimeMillis();
        // 增加时间间隔判断，避免状态刚切换就被打断
        if (now - lastActionTime > nextActionInterval) {
            lastActionTime = now;
            nextActionInterval = 2000 + random.nextInt(4000);

            // 只有在稳定状态下才执行新动作
            if (currentState == State.ADHERING || currentState == State.IDLE) {
                int action = random.nextInt(10);

                if (action < 3) {
                    currentState = State.IDLE;
                    vx = 0; vy = 0;
                } else if (action < 8) {
                    startMovement(); // 启动移动
                } else {
                    performSpecialAction();
                }
            }
        }
    }

    private void startMovement() {
        // 只有当前位置不是 AIR（即在某个表面上）时才开始移动
        if (currentPosition == Position.AIR) {
            currentState = State.IDLE;
            return;
        }

        currentState = State.MOVING;
        isMoving = true;
        moveSpeedMagnitude = 2 + random.nextFloat() * 3;

        switch (currentPosition) {
            case FLOOR:
                boolean moveRight = random.nextBoolean();
                vx = moveRight ? moveSpeedMagnitude : -moveSpeedMagnitude;
                vy = 0; // 强制垂直速度为0
                currentDirection = moveRight ? Direction.RIGHT : Direction.LEFT;
                lastNonNoneDirection = currentDirection;
                moveStartX = x;
                targetMoveDistance = screenWidth * (0.3f + random.nextFloat() * 0.4f);
                break;

            case CEILING:
                boolean ceilingMoveRight = random.nextBoolean();
                vx = ceilingMoveRight ? moveSpeedMagnitude : -moveSpeedMagnitude;
                vy = 0; // 强制垂直速度为0
                currentDirection = ceilingMoveRight ? Direction.RIGHT : Direction.LEFT;
                lastNonNoneDirection = currentDirection;
                moveStartX = x;
                targetMoveDistance = screenWidth * (0.3f + random.nextFloat() * 0.4f);
                break;

            case WALL_LEFT:
            case WALL_RIGHT:
                boolean moveDown = random.nextBoolean();
                vy = moveDown ? moveSpeedMagnitude : -moveSpeedMagnitude;
                vx = 0; // 强制水平速度为0
                currentDirection = moveDown ? Direction.DOWN : Direction.UP;
                moveStartY = y;
                targetMoveDistance = screenHeight * (0.2f + random.nextFloat() * 0.3f);
                break;
        }
    }

    private void handleMovement() {
        // 边界碰撞检测与状态转换（移动时）

        // 天花板/地面移动检测
        if (currentPosition == Position.CEILING || currentPosition == Position.FLOOR) {
            // 距离检测
            if (Math.abs(x - moveStartX) >= targetMoveDistance) {
                stopMovement(); // 到达目标距离，停止
                return;
            }

            // 撞墙检测
            if ((x <= 0 && vx < 0) || (x >= screenWidth - characterWidth && vx > 0)) {
                // 撞墙，转向墙壁移动
                if (x <= 0) {
                    currentPosition = Position.WALL_LEFT;
                    x = 0;
                } else {
                    currentPosition = Position.WALL_RIGHT;
                    x = screenWidth - characterWidth;
                }
                // 重置速度向量：沿墙移动
                vx = 0;
                vy = (random.nextBoolean() ? 1 : -1) * moveSpeedMagnitude;
                currentDirection = (vy > 0) ? Direction.DOWN : Direction.UP;
                // 重置移动起点和距离
                moveStartY = y;
                targetMoveDistance = screenHeight * 0.3f;
            }
        }
        // 墙壁移动检测
        else if (currentPosition == Position.WALL_LEFT || currentPosition == Position.WALL_RIGHT) {
            if (Math.abs(y - moveStartY) >= targetMoveDistance) {
                stopMovement();
                return;
            }

            // 撞顶/底检测
            if ((y <= 0 && vy < 0) || (y >= screenHeight - characterHeight && vy > 0)) {
                if (y <= 0) {
                    currentPosition = Position.CEILING;
                    y = 0;
                } else {
                    currentPosition = Position.FLOOR;
                    y = screenHeight - characterHeight;
                }
                // 重置速度向量：沿天花板/地面移动
                vy = 0;
                vx = (random.nextBoolean() ? 1 : -1) * moveSpeedMagnitude;
                currentDirection = (vx > 0) ? Direction.RIGHT : Direction.LEFT;
                lastNonNoneDirection = currentDirection;
                // 重置移动起点和距离
                moveStartX = x;
                targetMoveDistance = screenWidth * 0.5f;
            }
        }
    }

    private void stopMovement() {
        isMoving = false;
        vx = 0;
        vy = 0;
        currentState = State.IDLE; // 停止后进入 IDLE，而非 ADHERING
        // currentPosition 保持不变
    }

    private void performSpecialAction() {
        if (currentPosition == Position.FLOOR) {
            int action = random.nextInt(3);
            if (action == 0) {
                currentState = State.TWISTING;
                playActionSound(); // 播放扭动音效
                mainHandler.postDelayed(() -> {
                    if(!isDragging && currentPosition == Position.FLOOR && currentState == State.TWISTING) {
                        currentState = State.IDLE;
                    }
                }, 2000);
            } else if (action == 1) {
                currentState = State.TIPPING;
                playActionSound(); // 播放摔倒音效
                mainHandler.postDelayed(() -> {
                    if(!isDragging && currentPosition == Position.FLOOR && currentState == State.TIPPING) {
                        currentState = State.IDLE;
                    }
                }, 1500);
            } else {
                // 【修复点3：开始Creep状态时记录开始时间】
                currentState = State.CREEPING;
                creepStartTime = System.currentTimeMillis();
                mainHandler.postDelayed(() -> {
                    if(!isDragging && currentPosition == Position.FLOOR && currentState == State.CREEPING) {
                        currentState = State.IDLE;
                    }
                }, CREEP_DURATION_MS);
            }
        } else {
            currentState = State.IDLE;
        }
    }

    private void updateAnimation() {
        long now = System.currentTimeMillis();

        boolean shouldAnimate = (currentState == State.MOVING ||
                currentState == State.CREEPING ||
                currentState == State.TWISTING ||
                currentState == State.TIPPING ||
                currentState == State.FALLING ||
                (currentState == State.IDLE && isMoving));

        if (shouldAnimate && now - lastFrameTime > frameInterval) {
            lastFrameTime = now;
            currentFrameIndex++;
            Bitmap[] frames = getCurrentBitmaps();
            if (frames != null && frames.length > 0 && currentFrameIndex >= frames.length) {
                currentFrameIndex = 0;
            }
        } else if (!shouldAnimate) {
            currentFrameIndex = 0;
        }
    }

    private Bitmap[] getCeilBitmaps() {
        if (currentDirection == Direction.LEFT ||
                (currentDirection == Direction.NONE && lastNonNoneDirection == Direction.LEFT)) {
            return ceilLeftFrames;
        } else {
            return ceilRightFrames;
        }
    }

    private Bitmap[] getCurrentBitmaps() {
        switch (currentState) {
            case FALLING: return fallFrames;
            case CREEPING: return getCreepBitmaps();
            case TWISTING: return twistFrames;
            case TIPPING: return tipFrames;
            case MOVING: return getMovingBitmaps();
            case ADHERING:
            case IDLE:
            default: return getIdleBitmaps();
        }
    }

    private Bitmap[] getMovingBitmaps() {
        switch (currentPosition) {
            case WALL_LEFT: return wallLeftFrames;
            case WALL_RIGHT: return wallRightFrames;
            case CEILING: return (currentDirection == Direction.LEFT) ? ceilLeftFrames : ceilRightFrames;
            case FLOOR:
            case AIR:
            default:
                if (currentDirection == Direction.LEFT ||
                        (currentDirection == Direction.NONE && lastNonNoneDirection == Direction.LEFT)) {
                    return walkLeftFrames;
                } else {
                    return walkRightFrames;
                }
        }
    }

    private Bitmap[] getIdleBitmaps() {
        switch (currentPosition) {
            case CEILING: return (lastNonNoneDirection == Direction.LEFT) ? ceilLeftFrames : ceilRightFrames;
            case WALL_LEFT: return wallLeftFrames;
            case WALL_RIGHT: return wallRightFrames;
            case FLOOR:
            case AIR:
            default: return idleFrames;
        }
    }

    private Bitmap[] getCreepBitmaps() {
        if (currentPosition == Position.FLOOR) {
            if (lastNonNoneDirection == Direction.LEFT) {
                return creepLeftFrames;
            } else {
                return creepRightFrames;
            }
        }
        return creepLeftFrames;
    }

    private void updateWindowLayout() {
        if (layoutParams == null) layoutParams = (WindowManager.LayoutParams) getLayoutParams();
        if (layoutParams != null) {
            int offsetX = 0, offsetY = 0;
            if (currentPosition == Position.WALL_LEFT) offsetX -= ADHERE_DRAW_OFFSET_X;
            else if (currentPosition == Position.WALL_RIGHT) offsetX += ADHERE_DRAW_OFFSET_X;
            else if (currentPosition == Position.CEILING) offsetY -= ADHERE_DRAW_OFFSET_Y;
            layoutParams.x = (int) (x + offsetX);
            layoutParams.y = (int) (y + offsetY);
            windowManager.updateViewLayout(this, layoutParams);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 绘制角色
        Bitmap[] frames = getCurrentBitmaps();
        if (frames == null || frames.length == 0) return;
        Bitmap bitmap = frames[currentFrameIndex % frames.length];
        if (bitmap != null) {
            canvas.save();
            float drawX = 0, drawY = 0;
            if (currentPosition == Position.WALL_LEFT) drawX = -ADHERE_DRAW_OFFSET_X;
            else if (currentPosition == Position.WALL_RIGHT) drawX = ADHERE_DRAW_OFFSET_X;
            else if (currentPosition == Position.CEILING) drawY = -ADHERE_DRAW_OFFSET_Y;
            canvas.drawBitmap(bitmap, drawX, drawY, new Paint());
            canvas.restore();
        }
    }

    // 检查触摸点是否在角色区域内
    private boolean isTouchOnCharacter(float rawTouchX, float rawTouchY) {
        // 角色在屏幕上的实际绘制区域（考虑layoutParams偏移）
        float characterScreenX = x;
        float characterScreenY = y;
        
        // updateWindowLayout中应用的偏移量
        if (layoutParams != null) {
            int offsetX = 0, offsetY = 0;
            if (currentPosition == Position.WALL_LEFT) offsetX -= ADHERE_DRAW_OFFSET_X;
            else if (currentPosition == Position.WALL_RIGHT) offsetX += ADHERE_DRAW_OFFSET_X;
            else if (currentPosition == Position.CEILING) offsetY -= ADHERE_DRAW_OFFSET_Y;
            characterScreenX += offsetX;
            characterScreenY += offsetY;
        }
        
        // 角色实际绘制偏移（与onDraw中的drawX/drawY对应）
        if (currentPosition == Position.WALL_LEFT) characterScreenX -= ADHERE_DRAW_OFFSET_X;
        else if (currentPosition == Position.WALL_RIGHT) characterScreenX += ADHERE_DRAW_OFFSET_X;
        else if (currentPosition == Position.CEILING) characterScreenY -= ADHERE_DRAW_OFFSET_Y;

        // 判断触摸点是否在角色矩形内
        return rawTouchX >= characterScreenX && rawTouchX <= characterScreenX + characterWidth &&
               rawTouchY >= characterScreenY && rawTouchY <= characterScreenY + characterHeight;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float rawX = event.getRawX();
        float rawY = event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 只有点击角色区域内才开始拖动（使用原始屏幕坐标）
                if (!isTouchOnCharacter(rawX, rawY)) {
                    return false;
                }
                isDragging = true;
                isMoving = false; // 拖动打断AI移动
                lastTouchX = rawX;
                lastTouchY = rawY;
                lastTouchTime = System.currentTimeMillis();
                dragOffsetX = rawX - x;
                dragOffsetY = rawY - y;
                vx = 0;
                vy = 0;
                currentState = State.FALLING;
                currentPosition = Position.AIR;
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
                    currentState = State.FALLING;
                    currentPosition = Position.AIR;
                }
                break;

            case MotionEvent.ACTION_UP:
                isDragging = false;
                vx *= 1.5f;
                vy *= 1.5f;
                if (Math.abs(vx) < THROW_THRESHOLD && Math.abs(vy) < THROW_THRESHOLD) {
                    vx = 0;
                    vy = 0;
                    checkEdgeAdhere();
                } else {
                    currentState = State.FALLING;
                    currentPosition = Position.AIR;
                }
                break;
        }
        return isDragging;
    }

    private void checkEdgeAdhere() {
        final float EPS = 2f;
        if (y <= EPS) {
            currentPosition = Position.CEILING;
            currentState = State.IDLE; // 修复：直接设为IDLE，避免ADHERING卡顿
            y = 0;
        } else if (y >= screenHeight - characterHeight - EPS) {
            currentPosition = Position.FLOOR;
            currentState = State.IDLE;
            y = screenHeight - characterHeight;
        } else if (x <= EPS) {
            currentPosition = Position.WALL_LEFT;
            currentState = State.IDLE;
            x = 0;
        } else if (x >= screenWidth - characterWidth - EPS) {
            currentPosition = Position.WALL_RIGHT;
            currentState = State.IDLE;
            x = screenWidth - characterWidth;
        } else {
            currentPosition = Position.AIR;
            currentState = State.FALLING;
        }
    }

    private void triggerImpact() {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {}
    }

    // --- 后台音效播放线程 ---
    private void startSoundScheduler() {
        soundThread = new HandlerThread("UsagiSoundThread");
        soundThread.start();
        soundHandler = new Handler(soundThread.getLooper());
        scheduleNextSound();
    }

    private void scheduleNextSound() {
        if (soundHandler == null) return;
        soundHandler.postDelayed(new Runnable() {
            @Override public void run() {
                playActionSound(); // 播放对应动作的音效
                int delay = SCHEDULE_MIN_MS + random.nextInt(SCHEDULE_MAX_MS - SCHEDULE_MIN_MS + 1);
                soundHandler.postDelayed(this, delay);
            }
        }, SCHEDULE_MIN_MS + random.nextInt(SCHEDULE_MAX_MS - SCHEDULE_MIN_MS + 1));
    }
}