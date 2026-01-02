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
    private static final float GRAVITY = 0.6f;
    private static final float BOUNCE_DAMPING = 0.5f;
    private static final float AIR_FRICTION = 0.98f;
    private static final float ADHERE_SPEED = 0.2f;
    private static final float EDGE_SNAP_EPS = 6f;
    private static final int ADHERE_DRAW_OFFSET = 94;
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
    private Bitmap[] idleFrames, walkLeftFrames, walkRightFrames, fallFrames;
    private Bitmap[] wallLeftFrames, wallRightFrames, ceilFrames, creepFrames;
    private Bitmap[] twistFrames, tipFrames;
    private SoundPool soundPool;
    private List<Integer> soundIds;
    private Map<String, Integer> soundNameToId;
    private List<Integer> activeSoundStreams = new ArrayList<>();
    private final Object soundPlayLock = new Object();
    private long lastSoundPlayTime = 0;
    private static final long SOUND_MIN_INTERVAL_MS = 300;
    private Random random = new Random();

    // 线程控制
    private HandlerThread gameThread;
    private Handler gameHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long GAME_FRAME_MS = 16;
    private volatile boolean isRunning = false;
    private volatile boolean resourcesLoaded = false;

    // 定时音效
    private long nextScheduledSoundTime = 0;
    private static final int SCHEDULE_MIN_MS = 20 * 1000;
    private static final int SCHEDULE_MAX_MS = 30 * 1000;

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

    // 边缘检测
    private boolean isNearLeftEdge = false;
    private boolean isNearRightEdge = false;
    private boolean isNearTopEdge = false;
    private boolean isNearBottomEdge = false;

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

        // 【修复点1】预先初始化所有数组为默认占位图
        // 防止异步加载未完成时 getCurrentBitmaps 返回 null 导致粉色方块
        Bitmap def = createPlaceholderBitmap(characterWidth, characterHeight);
        idleFrames = new Bitmap[]{def};
        walkLeftFrames = new Bitmap[]{def};
        walkRightFrames = new Bitmap[]{def};
        fallFrames = new Bitmap[]{def};
        wallLeftFrames = new Bitmap[]{def};
        wallRightFrames = new Bitmap[]{def};
        ceilFrames = new Bitmap[]{def};
        creepFrames = new Bitmap[]{def};
        twistFrames = new Bitmap[]{def};
        tipFrames = new Bitmap[]{def};

        startGameLoop();
        loadResourcesAsync();
        scheduleNextSound();
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
        walkLeftFrames = loadFramesIfExists(new String[]{"walk_left_1", "walk_left_2"}, packageName);
        walkRightFrames = loadFramesIfExists(new String[]{"walk_right_1", "walk_right_2"}, packageName);
        if (walkLeftFrames == null && walkRightFrames == null) {
            Bitmap[] commonWalk = loadFramesIfExists(new String[]{"walk_1", "walk_2"}, packageName);
            if (commonWalk != null) {
                walkRightFrames = commonWalk;
                walkLeftFrames = flipBitmaps(commonWalk);
            }
        } else if (walkLeftFrames == null) walkLeftFrames = flipBitmaps(walkRightFrames);
        else if (walkRightFrames == null) walkRightFrames = flipBitmaps(walkLeftFrames);

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
        } else if (wallLeftFrames == null) wallLeftFrames = flipBitmaps(wallRightFrames);
        else if (wallRightFrames == null) wallRightFrames = flipBitmaps(wallLeftFrames);

        // 占位符回退（二次保险）
        if (idleFrames == null) idleFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (walkLeftFrames == null && walkRightFrames == null) {
            Bitmap p = createPlaceholderBitmap(characterWidth, characterHeight);
            walkRightFrames = new Bitmap[]{p}; walkLeftFrames = flipBitmaps(walkRightFrames);
        }
        if (fallFrames == null) fallFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (ceilFrames == null) ceilFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (wallLeftFrames == null && wallRightFrames == null) {
            Bitmap p = createPlaceholderBitmap(characterWidth, characterHeight);
            wallRightFrames = new Bitmap[]{p}; wallLeftFrames = flipBitmaps(wallRightFrames);
        }
        if (creepFrames == null) creepFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (twistFrames == null) twistFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};
        if (tipFrames == null) tipFrames = new Bitmap[]{createPlaceholderBitmap(characterWidth, characterHeight)};

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build();
        soundIds = new ArrayList<>();
        soundNameToId = new HashMap<>();
        try {
            Class<?> c = Class.forName(packageName + ".R$raw");
            for (Field f : c.getFields()) {
                String name = f.getName();
                int id = getResources().getIdentifier(name, "raw", packageName);
                if (id > 0) soundIds.add(soundPool.load(context, id, 1));
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

    private Bitmap[] flipBitmaps(Bitmap[] src) {
        if (src == null) return null;
        Bitmap[] out = new Bitmap[src.length];
        Matrix m = new Matrix(); m.preScale(-1, 1);
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : Bitmap.createBitmap(src[i], 0, 0, src[i].getWidth(), src[i].getHeight(), m, false);
        return out;
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
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); stopGameLoop(); }

    private void updatePhysics() {
        if (isDragging) { vx = 0; vy = 0; return; }

        updateEdgeProximity();

        // 如果处于 AI 移动或 Creep 移动状态，跳过重力和自动吸附，防止物理引擎干扰
        if (isMoving || currentState == State.CREEPING) {
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

        // 自动吸附逻辑
        if (!collisionOccurred && (Math.abs(vx) < 0.5f && Math.abs(vy) < 0.5f)) {
            checkAutoAdhere();
        }

        if (currentState == State.ADHERING && !isDragging) {
            performAdhesion();
        }
    }

    private void updateEdgeProximity() {
        float edgeThreshold = 10f;
        isNearLeftEdge = (x <= edgeThreshold);
        isNearRightEdge = (x >= screenWidth - characterWidth - edgeThreshold);
        isNearTopEdge = (y <= edgeThreshold);
        isNearBottomEdge = (y >= screenHeight - characterHeight - edgeThreshold);
    }

    private void checkAutoAdhere() {
        if (currentState == State.ADHERING || currentState == State.MOVING || currentState == State.CREEPING) return;

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

        // 【修复点2】处理 MOVING 状态的移动
        if (isMoving) {
            handleMovement();
            return;
        }

        // 【修复点3】处理 CREEPING 状态的移动 (核心修复)
        if (currentState == State.CREEPING) {
            handleCreepMovement();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastActionTime > nextActionInterval) {
            lastActionTime = now;
            nextActionInterval = 2000 + random.nextInt(4000);

            if (currentState == State.ADHERING || currentState == State.IDLE) {
                int action = random.nextInt(10);

                if (action < 3) {
                    currentState = State.IDLE;
                    vx = 0; vy = 0;
                } else if (action < 8) {
                    startMovement();
                } else {
                    performSpecialAction();
                }
            }
        }
    }

    // 新增处理 CREEPING 状态的移动逻辑
    private void handleCreepMovement() {
        // 如果不在地面，停止 Creep
        if (currentPosition != Position.FLOOR) {
            currentState = State.IDLE;
            vx = 0; vy = 0;
            return;
        }

        // 施加移动速度
        float creepSpeed = 1.5f; // 爬行速度稍慢
        if (currentDirection == Direction.RIGHT) {
            vx = creepSpeed;
        } else if (currentDirection == Direction.LEFT) {
            vx = -creepSpeed;
        } else {
            // 如果没有明确方向，根据上一次记录的方向决定，或者随机
            if (lastNonNoneDirection == Direction.RIGHT) vx = creepSpeed;
            else vx = -creepSpeed;
            currentDirection = (vx > 0) ? Direction.RIGHT : Direction.LEFT;
        }
        vy = 0; // 确保不离开地面

        // 碰撞检测（左右墙壁）
        if (x <= 0 && vx < 0) {
            x = 0;
            currentDirection = Direction.RIGHT;
            lastNonNoneDirection = Direction.RIGHT;
        } else if (x >= screenWidth - characterWidth && vx > 0) {
            x = screenWidth - characterWidth;
            currentDirection = Direction.LEFT;
            lastNonNoneDirection = Direction.LEFT;
        }

        // 随机停止逻辑：避免一直爬下去
        if (random.nextInt(200) < 1) { // 约每3-4秒有几率停止
            currentState = State.IDLE;
            vx = 0;
        }
    }

    private void startMovement() {
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
                vy = 0;
                currentDirection = moveRight ? Direction.RIGHT : Direction.LEFT;
                lastNonNoneDirection = currentDirection;
                moveStartX = x;
                targetMoveDistance = screenWidth * (0.3f + random.nextFloat() * 0.4f);
                break;

            case CEILING:
                boolean ceilingMoveRight = random.nextBoolean();
                vx = ceilingMoveRight ? moveSpeedMagnitude : -moveSpeedMagnitude;
                vy = 0;
                currentDirection = ceilingMoveRight ? Direction.RIGHT : Direction.LEFT;
                lastNonNoneDirection = currentDirection;
                moveStartX = x;
                targetMoveDistance = screenWidth * (0.3f + random.nextFloat() * 0.4f);
                break;

            case WALL_LEFT:
            case WALL_RIGHT:
                boolean moveDown = random.nextBoolean();
                vy = moveDown ? moveSpeedMagnitude : -moveSpeedMagnitude;
                vx = 0;
                currentDirection = moveDown ? Direction.DOWN : Direction.UP;
                moveStartY = y;
                targetMoveDistance = screenHeight * (0.2f + random.nextFloat() * 0.3f);
                break;
        }
    }

    private void handleMovement() {
        // 天花板/地面移动检测
        if (currentPosition == Position.CEILING || currentPosition == Position.FLOOR) {
            if (Math.abs(x - moveStartX) >= targetMoveDistance) {
                stopMovement();
                return;
            }

            if ((x <= 0 && vx < 0) || (x >= screenWidth - characterWidth && vx > 0)) {
                if (x <= 0) {
                    currentPosition = Position.WALL_LEFT;
                    x = 0;
                } else {
                    currentPosition = Position.WALL_RIGHT;
                    x = screenWidth - characterWidth;
                }
                vx = 0;
                vy = (random.nextBoolean() ? 1 : -1) * moveSpeedMagnitude;
                currentDirection = (vy > 0) ? Direction.DOWN : Direction.UP;
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

            if ((y <= 0 && vy < 0) || (y >= screenHeight - characterHeight && vy > 0)) {
                if (y <= 0) {
                    currentPosition = Position.CEILING;
                    y = 0;
                } else {
                    currentPosition = Position.FLOOR;
                    y = screenHeight - characterHeight;
                }
                vy = 0;
                vx = (random.nextBoolean() ? 1 : -1) * moveSpeedMagnitude;
                currentDirection = (vx > 0) ? Direction.RIGHT : Direction.LEFT;
                lastNonNoneDirection = currentDirection;
                moveStartX = x;
                targetMoveDistance = screenWidth * 0.5f;
            }
        }
    }

    private void stopMovement() {
        isMoving = false;
        vx = 0;
        vy = 0;
        currentState = State.IDLE;
    }

    private void performSpecialAction() {
        if (currentPosition == Position.FLOOR) {
            int action = random.nextInt(3);
            if (action == 0) {
                currentState = State.TWISTING;
                mainHandler.postDelayed(() -> {
                    if(!isDragging && currentPosition == Position.FLOOR) {
                        currentState = State.IDLE;
                    }
                }, 2000);
            } else if (action == 1) {
                currentState = State.TIPPING;
                mainHandler.postDelayed(() -> {
                    if(!isDragging && currentPosition == Position.FLOOR) {
                        currentState = State.IDLE;
                    }
                }, 1500);
            } else {
                currentState = State.CREEPING;
                // 进入 CREEPING 时，随机设定一个初始方向
                if (random.nextBoolean()) {
                    currentDirection = Direction.RIGHT;
                    lastNonNoneDirection = Direction.RIGHT;
                } else {
                    currentDirection = Direction.LEFT;
                    lastNonNoneDirection = Direction.LEFT;
                }
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
                currentState == State.FALLING);

        if (shouldAnimate && now - lastFrameTime > frameInterval) {
            lastFrameTime = now;
            currentFrameIndex++;
            Bitmap[] frames = getCurrentBitmaps();
            // 确保 frames 不为 null (init已做保障，这里双重保险)
            if (frames != null && frames.length > 0 && currentFrameIndex >= frames.length) {
                currentFrameIndex = 0;
            }
        } else if (!shouldAnimate) {
            currentFrameIndex = 0;
        }
    }

    private Bitmap[] getCurrentBitmaps() {
        switch (currentState) {
            case FALLING: return fallFrames;
            case CREEPING: return creepFrames;
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
            case WALL_LEFT: return (wallRightFrames != null) ? wallRightFrames : wallLeftFrames;
            case WALL_RIGHT: return (wallLeftFrames != null) ? wallLeftFrames : wallRightFrames;
            case CEILING: return ceilFrames;
            case FLOOR:
            case AIR:
            default:
                if (currentDirection == Direction.LEFT ||
                        (currentDirection == Direction.NONE && lastNonNoneDirection == Direction.LEFT)) {
                    return (walkRightFrames != null) ? walkRightFrames : walkLeftFrames;
                } else {
                    return (walkLeftFrames != null) ? walkLeftFrames : walkRightFrames;
                }
        }
    }

    private Bitmap[] getIdleBitmaps() {
        switch (currentPosition) {
            case CEILING: return ceilFrames;
            case WALL_LEFT: return (wallRightFrames != null) ? wallRightFrames : wallLeftFrames;
            case WALL_RIGHT: return (wallLeftFrames != null) ? wallLeftFrames : wallRightFrames;
            case FLOOR:
            case AIR:
            default: return idleFrames;
        }
    }

    private void updateWindowLayout() {
        if (layoutParams == null) layoutParams = (WindowManager.LayoutParams) getLayoutParams();
        if (layoutParams != null) {
            int offsetX = 0, offsetY = 0;
            if (currentPosition == Position.WALL_LEFT) offsetX -= ADHERE_DRAW_OFFSET;
            else if (currentPosition == Position.WALL_RIGHT) offsetX += ADHERE_DRAW_OFFSET;
            else if (currentPosition == Position.CEILING) offsetY -= ADHERE_DRAW_OFFSET;
            layoutParams.x = (int) (x + offsetX);
            layoutParams.y = (int) (y + offsetY);
            windowManager.updateViewLayout(this, layoutParams);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap[] frames = getCurrentBitmaps();
        // 双重检查 null，防止空指针
        if (frames == null || frames.length == 0) return;

        Bitmap bitmap = frames[currentFrameIndex % frames.length];
        if (bitmap != null) {
            canvas.save();
            float drawX = 0, drawY = 0;
            if (currentPosition == Position.WALL_LEFT) drawX = -ADHERE_DRAW_OFFSET;
            else if (currentPosition == Position.WALL_RIGHT) drawX = ADHERE_DRAW_OFFSET;
            if (currentPosition == Position.CEILING) drawY = -ADHERE_DRAW_OFFSET;
            else if (currentPosition == Position.FLOOR) drawY = ADHERE_DRAW_OFFSET;
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
                isMoving = false;
                lastTouchX = rawX;
                lastTouchY = rawY;
                lastTouchTime = System.currentTimeMillis();
                dragOffsetX = event.getX();
                dragOffsetY = event.getY();
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
        return true;
    }

    private void checkEdgeAdhere() {
        final float EPS = 2f;
        if (y <= EPS) {
            currentPosition = Position.CEILING;
            currentState = State.IDLE;
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(50);
                }
            }
        } catch (Exception ignored) {}
    }

    private void scheduleNextSound() {
        long now = System.currentTimeMillis();
        int delay = SCHEDULE_MIN_MS + random.nextInt(SCHEDULE_MAX_MS - SCHEDULE_MIN_MS + 1);
        nextScheduledSoundTime = now + delay;
    }
}