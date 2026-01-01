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
    private static final float EDGE_SNAP_EPS = 6f;      // 靠边自动吸附的阈值（像素）
    private static final int ADHERE_DRAW_OFFSET = 94;   // 吸附时贴图的绘制偏移（像素）
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
    private Random random = new Random();

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

    // 区分左右的贴图资源（优先使用左右专用资源，若不存在则回退或镜像）
    private Bitmap[] walkLeftFrames;    // 向左走
    private Bitmap[] walkRightFrames;   // 向右走
    private Bitmap[] wallLeftFrames;    // 靠左吸附
    private Bitmap[] wallRightFrames;   // 靠右吸附
    private boolean useFlipForLeft = false; // 若没有左右贴图，是否使用镜像

    // AI 行为控制
    private long lastActionTime = 0;
    private long nextActionInterval = 2000;
    private boolean isMoving = false; // 标记是否正在进行强制移动
    private float moveStartX = 0; // 移动起始位置
    private float moveStartY = 0; // 移动起始位置
    private float targetMoveDistance = 0; // 目标移动距离
    private float moveSpeed = 0; // 行走时保持的速度（正为向右，负为向左）
    private Direction lastMoveDirection = Direction.NONE; // 最近一次移动方向

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

        // 初始位置：屏幕正中央，从屏幕中心开始
        characterWidth = 128;
        characterHeight = 128;
        x = screenWidth / 2 - characterWidth / 2;
        y = screenHeight / 2 - characterHeight / 2;
        vx = 0;
        vy = 0; // 初始无速度

        loadResources();
        // 立即更新窗口位置到屏幕中央
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

    private void loadResources() {
        String packageName = context.getPackageName();

        // 这里需要确保 drawable 中有对应的图片，如果找不到会报错，请根据实际素材调整
        idleFrames = loadFrames(new String[]{"stand_1"}, packageName); // 只有站立帧

        // 优先寻找左右专用的行走贴图
        walkLeftFrames = loadFramesIfExists(new String[]{"walk_left_1", "walk_left_2"}, packageName);
        walkRightFrames = loadFramesIfExists(new String[]{"walk_right_1", "walk_right_2"}, packageName);

        // 如果没有左右专用贴图，尝试加载通用行走贴图并生成左右镜像
        if (walkLeftFrames == null && walkRightFrames == null) {
            Bitmap[] commonWalk = loadFramesIfExists(new String[]{"walk_1", "walk_2"}, packageName);
            if (commonWalk != null) {
                walkRightFrames = commonWalk;
                walkLeftFrames = flipBitmaps(commonWalk);
                useFlipForLeft = false; // 我们有实际的左帧（镜像居然也是具体帧）
            }
        } else if (walkLeftFrames == null && walkRightFrames != null) {
            walkLeftFrames = flipBitmaps(walkRightFrames);
        } else if (walkRightFrames == null && walkLeftFrames != null) {
            walkRightFrames = flipBitmaps(walkLeftFrames);
        }

        // 下落、天花板、墙的贴图
        fallFrames = loadFramesIfExists(new String[]{"fall_1"}, packageName);
        ceilFrames = loadFramesIfExists(new String[]{"ceil_1", "ceil_2"}, packageName);
        
        // 特殊动作贴图
        creepFrames = loadFramesIfExists(new String[]{"creep_1", "creep_2"}, packageName);
        twistFrames = loadFramesIfExists(new String[]{"twist_1", "twist_2", "twist_3", "twist_4"}, packageName);
        tipFrames = loadFramesIfExists(new String[]{"tip_1"}, packageName);

        // 墙面优先区分左右
        wallLeftFrames = loadFramesIfExists(new String[]{"climb_left_1", "climb_left_2"}, packageName);
        wallRightFrames = loadFramesIfExists(new String[]{"climb_right_1", "climb_right_2"}, packageName);
        if (wallLeftFrames == null && wallRightFrames == null) {
            // 回退到通用爬墙贴图，如果存在则产生左右帧
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

        // 如果某些资源都为空，确保不会崩溃：回退到占位图
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

    // 尝试加载贴图，但如果资源不存在则返回 null（便于决定是否使用镜像或回退）
    private Bitmap[] loadFramesIfExists(String[] names, String pkg) {
        // 简单判断第一个资源是否存在
        int resId = getResources().getIdentifier(names[0], "drawable", pkg);
        if (resId == 0) return null;
        return loadFrames(names, pkg);
    }

    // 镜像一套贴图（左右互换）
    private Bitmap[] flipBitmaps(Bitmap[] src) {
        if (src == null) return null;
        Bitmap[] out = new Bitmap[src.length];
        Matrix m = new Matrix();
        m.preScale(-1, 1);
        for (int i = 0; i < src.length; i++) {
            Bitmap s = src[i];
            if (s != null) {
                out[i] = Bitmap.createBitmap(s, 0, 0, s.getWidth(), s.getHeight(), m, false);
            } else {
                out[i] = null;
            }
        }
        return out;
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

        // 1. 应用重力（仅在非吸附状态下应用）
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

        // 地面检测
        if (nextY >= screenHeight - characterHeight) {
            nextY = screenHeight - characterHeight;
            // 只有当速度足够大时才反弹，否则吸附
            if (vy > 8) {
                vy = -vy * BOUNCE_DAMPING;
                playSound("bounce");
                triggerImpact();
            } else {
                // 保持动画状态，如果是MOVE则继续播放动画
                if (animState != AnimationState.MOVE && animState != AnimationState.FALL) {
                    vy = 0;
                }
                if (posState != PositionState.FLOOR) {
                    posState = PositionState.FLOOR;
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
                // 保持动画状态，如果是MOVE则继续播放动画
                if (animState != AnimationState.MOVE && animState != AnimationState.FALL) {
                    vy = 0;
                }
                if (posState != PositionState.CEILING) {
                    posState = PositionState.CEILING;
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
                // 保持动画状态，如果是MOVE则继续播放动画
                if (animState != AnimationState.MOVE && animState != AnimationState.FALL) {
                    vx = 0;
                }
                // 优化：当走到左边缘时，切换到左墙状态
                if (posState != PositionState.WALL_LEFT) {
                    posState = PositionState.WALL_LEFT;
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
                // 保持动画状态，如果是MOVE则继续播放动画
                if (animState != AnimationState.MOVE && animState != AnimationState.FALL) {
                    vx = 0;
                }
                // 优化：当走到右边缘时，切换到右墙状态
                if (posState != PositionState.WALL_RIGHT) {
                    posState = PositionState.WALL_RIGHT;
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

            // 额外小阈值自动贴边：当速度很小且接近屏幕边缘时强制吸附，提升边缘判定稳定性
            if (Math.abs(vx) < 1f && Math.abs(vy) < 1f) {
                // 优先左右吸附
                if (x <= EDGE_SNAP_EPS) {
                    x = 0;
                    posState = PositionState.WALL_LEFT;
                    animState = AnimationState.IDLE;
                } else if (x >= screenWidth - characterWidth - EDGE_SNAP_EPS) {
                    x = screenWidth - characterWidth;
                    posState = PositionState.WALL_RIGHT;
                    animState = AnimationState.IDLE;
                } else {
                    // 若不贴墙再判断上下边
                    if (y <= EDGE_SNAP_EPS) {
                        y = 0;
                        posState = PositionState.CEILING;
                        animState = AnimationState.IDLE;
                    } else if (y >= screenHeight - characterHeight - EDGE_SNAP_EPS) {
                        y = screenHeight - characterHeight;
                        posState = PositionState.FLOOR;
                        animState = AnimationState.IDLE;
                    }
                }
            }
        }
    }

    // --- AI 行为逻辑 ---
    private void updateAI() {
        if (isDragging) return;

        // 处理正在进行的移动
        if (isMoving) {
            // 天花板的水平移动
            if (posState == PositionState.CEILING) {
                // 计算已经移动的距离
                float distanceMoved = Math.abs(x - moveStartX);

                // 如果还没到目标距离，继续移动，且保持速度以抵消空气阻力
                if (distanceMoved < targetMoveDistance) {
                    if (Math.abs(vx) < Math.abs(moveSpeed) * 0.6f) {
                        // 如果速度被空气阻力减太多，则补回到移动速度的一个比例
                        vx = (moveSpeed >= 0) ? Math.abs(moveSpeed) : -Math.abs(moveSpeed);
                    }
                    // 保持移动状态，等待完成
                    return;
                } else {
                    // 已达到目标距离，结束移动
                    isMoving = false;
                    vx = 0;
                    moveSpeed = 0;
                    lastMoveDirection = Direction.NONE;
                    animState = AnimationState.IDLE;
                }
                return;
            }

            // 墙壁的垂直移动
            if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                // 计算已经移动的距离
                float distanceMoved = Math.abs(y - moveStartY);

                // 如果还没到目标距离，继续移动，且保持速度以抵消空气阻力
                if (distanceMoved < targetMoveDistance) {
                    if (Math.abs(vy) < Math.abs(moveSpeed) * 0.6f) {
                        // 如果速度被空气阻力减太多，则补回到移动速度的一个比例
                        vy = (moveSpeed >= 0) ? Math.abs(moveSpeed) : -Math.abs(moveSpeed);
                    }
                    // 保持移动状态，等待完成
                    return;
                } else {
                    // 已达到目标距离，结束移动
                    isMoving = false;
                    vy = 0;
                    moveSpeed = 0;
                    animState = AnimationState.IDLE;
                }
                return;
            }

            // 地面的水平移动
            // 计算已经移动的距离
            float distanceMoved = Math.abs(x - moveStartX);

            // 如果还没到目标距离，继续移动，且保持速度以抵消空气阻力
            if (distanceMoved < targetMoveDistance) {
                if (Math.abs(vx) < Math.abs(moveSpeed) * 0.6f) {
                    // 如果速度被空气阻力减太多，则补回到移动速度的一个比例
                    vx = (moveSpeed >= 0) ? Math.abs(moveSpeed) : -Math.abs(moveSpeed);
                }
                // 保持移动状态，等待完成
                return;
            } else {
                // 已达到目标距离，结束移动
                isMoving = false;
                vx = 0;
                moveSpeed = 0;
                lastMoveDirection = Direction.NONE;
                animState = AnimationState.IDLE;
            }
        }

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
                    vx = 0;
                    vy = 0;
                } else if (action < 8) {
                    // 爬行/走动 (改变速度)
                    animState = AnimationState.MOVE;
                    float speed = 2 + random.nextFloat() * 3;

                    if (posState == PositionState.FLOOR) {
                        // 地面：水平左右移动
                        boolean moveRight = random.nextBoolean();
                        moveSpeed = moveRight ? speed : -speed;
                        vx = moveSpeed;
                        lastMoveDirection = moveRight ? Direction.RIGHT : Direction.LEFT;

                        isMoving = true;
                        moveStartX = x;
                        targetMoveDistance = screenWidth * 0.5f;
                    } else if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                        // 墙壁：垂直上下移动
                        boolean moveDown = random.nextBoolean();
                        moveSpeed = moveDown ? speed : -speed;
                        vy = moveSpeed;

                        isMoving = true;
                        moveStartY = y;
                        targetMoveDistance = screenHeight * 0.3f;
                    } else if (posState == PositionState.CEILING) {
                        // 天花板：水平左右移动
                        boolean moveRight = random.nextBoolean();
                        moveSpeed = moveRight ? speed : -speed;
                        vx = moveSpeed;
                        lastMoveDirection = moveRight ? Direction.RIGHT : Direction.LEFT;

                        isMoving = true;
                        moveStartX = x;
                        targetMoveDistance = screenWidth * 0.5f;
                    }
                } else {
                    // 特殊动作：根据当前位置选择
                    if (posState == PositionState.FLOOR && animState == AnimationState.IDLE) {
                        // 在地面站立时，可以选择转身或脚交叉
                        int floorAction = random.nextInt(3);
                        if (floorAction == 0) {
                            // 转身一圈
                            animState = AnimationState.TWIST;
                            playRandomSound();
                            new Handler().postDelayed(() -> {
                                if (!isDragging && posState == PositionState.FLOOR) {
                                    animState = AnimationState.IDLE;
                                    vx = 0;
                                    vy = 0;
                                }
                            }, 2000); // 转身需要2秒
                        } else if (floorAction == 1) {
                            // 脚交叉站立
                            animState = AnimationState.TIP;
                            playRandomSound();
                            new Handler().postDelayed(() -> {
                                if (!isDragging && posState == PositionState.FLOOR) {
                                    animState = AnimationState.IDLE;
                                    vx = 0;
                                    vy = 0;
                                }
                            }, 1500); // 脚交叉需要1.5秒
                        } else {
                            // 转换为爬行状态
                            animState = AnimationState.CREEP;
                            vx = 0;
                            vy = 0;
                        }
                    } else {
                        // 其他位置执行通用动作
                        animState = AnimationState.ACTION_1;
                        playRandomSound();
                        new Handler().postDelayed(() -> {
                            if (!isDragging && posState != PositionState.AIR) {
                                vx = 0;
                                vy = 0;
                                animState = AnimationState.IDLE;
                                isMoving = false;
                            }
                        }, 1000);
                    }
                }
            }
        }

        // 移动状态下的持续逻辑
        if (animState == AnimationState.MOVE) {
            if (posState == PositionState.FLOOR) {
                // 地面移动：碰到左边缘，切换到左墙并继续向上移动
                if (x <= 0 && vx < 0) {
                    x = 0;
                    posState = PositionState.WALL_LEFT;
                    // 继续向上移动
                    moveSpeed = Math.abs(moveSpeed); // 保持速度大小，方向向上
                    vy = -moveSpeed;
                    vx = 0;
                    moveStartY = y;
                    targetMoveDistance = screenHeight * 0.3f;
                }
                // 碰到右边缘，切换到右墙并继续向上移动
                else if (x >= screenWidth - characterWidth && vx > 0) {
                    x = screenWidth - characterWidth;
                    posState = PositionState.WALL_RIGHT;
                    // 继续向上移动
                    moveSpeed = Math.abs(moveSpeed); // 保持速度大小，方向向上
                    vy = -moveSpeed;
                    vx = 0;
                    moveStartY = y;
                    targetMoveDistance = screenHeight * 0.3f;
                }
            } else if (posState == PositionState.CEILING) {
                // 天花板移动：碰到左边缘，切换到左墙并继续向下移动
                if (x <= 0 && vx < 0) {
                    x = 0;
                    posState = PositionState.WALL_LEFT;
                    // 继续向下移动
                    moveSpeed = Math.abs(moveSpeed); // 保持速度大小，方向向下
                    vy = moveSpeed;
                    vx = 0;
                    moveStartY = y;
                    targetMoveDistance = screenHeight * 0.3f;
                }
                // 碰到右边缘，切换到右墙并继续向下移动
                else if (x >= screenWidth - characterWidth && vx > 0) {
                    x = screenWidth - characterWidth;
                    posState = PositionState.WALL_RIGHT;
                    // 继续向下移动
                    moveSpeed = Math.abs(moveSpeed); // 保持速度大小，方向向下
                    vy = moveSpeed;
                    vx = 0;
                    moveStartY = y;
                    targetMoveDistance = screenHeight * 0.3f;
                }
            } else if (posState == PositionState.WALL_LEFT || posState == PositionState.WALL_RIGHT) {
                // 墙壁移动：碰到上/下边缘时，切换为天花板/地面并沿远离墙面的方向继续水平移动
                boolean wasLeftWall = (posState == PositionState.WALL_LEFT);
                // 碰到上边缘，切换到天花板并继续水平移动（朝远离墙面的方向）
                if (y <= 0 && vy < 0) {
                    y = 0;
                    posState = PositionState.CEILING;
                    moveSpeed = Math.abs(moveSpeed);
                    vx = wasLeftWall ? moveSpeed : -moveSpeed;
                    vy = 0;
                    moveStartX = x;
                    targetMoveDistance = screenWidth * 0.5f;
                    lastMoveDirection = wasLeftWall ? Direction.RIGHT : Direction.LEFT;
                }
                // 碰到下边缘，切换到地面并继续水平移动（朝远离墙面的方向）
                else if (y >= screenHeight - characterHeight && vy > 0) {
                    y = screenHeight - characterHeight;
                    posState = PositionState.FLOOR;
                    moveSpeed = Math.abs(moveSpeed);
                    vx = wasLeftWall ? moveSpeed : -moveSpeed;
                    vy = 0;
                    moveStartX = x;
                    targetMoveDistance = screenWidth * 0.5f;
                    lastMoveDirection = wasLeftWall ? Direction.RIGHT : Direction.LEFT;
                }
            }
        }
    }

    private void updateAnimation() {
        long now = System.currentTimeMillis();

        // 判断是否需要播放动画
        boolean shouldAnimate = false;
        
        // 如果是移动状态，总是播放动画
        if (animState == AnimationState.MOVE) {
            shouldAnimate = true;
        }
        // 特殊动作状态也播放动画
        else if (animState == AnimationState.CREEP || 
                 animState == AnimationState.TWIST || 
                 animState == AnimationState.TIP) {
            shouldAnimate = true;
        }
        // 如果是待机状态但在吸附位置，且没有移动，则保持第一帧
        else if (animState == AnimationState.IDLE) {
            shouldAnimate = false;
            currentFrameIndex = 0; // 保持第一帧
        }
        // 其他状态（FALL, ACTION等）播放动画
        else {
            shouldAnimate = true;
        }

        // 如果动画状态发生变化，重置帧索引以确保动画从第一帧开始循环
        if (prevAnimState != animState) {
            currentFrameIndex = 0;
            lastFrameTime = now;
            prevAnimState = animState;
        }

        // 只有需要播放动画时才更新帧索引
        if (shouldAnimate && now - lastFrameTime > frameInterval) {
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
            case CREEP: return creepFrames;
            case TWIST: return twistFrames;
            case TIP: return tipFrames;
            case ACTION_1:
            case ACTION_2: return idleFrames; // 暂时复用
            case MOVE:
                // 爬墙/天花板使用对应帧（修正：靠左吸附使用右侧贴图，靠右吸附使用左侧贴图，以匹配素材方向）
                if (posState == PositionState.WALL_LEFT) return (wallRightFrames != null) ? wallRightFrames : wallLeftFrames;
                if (posState == PositionState.WALL_RIGHT) return (wallLeftFrames != null) ? wallLeftFrames : wallRightFrames;
                if (posState == PositionState.CEILING) return ceilFrames;
                // 地面行走：根据最近行走方向选择帧
                // 如果没有明确的 lastMoveDirection，则基于当前 vx 作为后备检测
                Direction effectiveDir = lastMoveDirection;
                if (effectiveDir == Direction.NONE) {
                    if (vx < 0) effectiveDir = Direction.LEFT;
                    else if (vx > 0) effectiveDir = Direction.RIGHT;
                }
                // 注意：修正映射 —— 若素材命名/朝向导致左右贴图对调，这里通过交换选择来修正（左走使用 walkRightFrames，右走使用 walkLeftFrames）
                if (effectiveDir == Direction.LEFT) return (walkRightFrames != null) ? walkRightFrames : walkLeftFrames;
                if (effectiveDir == Direction.RIGHT) return (walkLeftFrames != null) ? walkLeftFrames : walkRightFrames;
                // 回退
                return walkRightFrames != null ? walkRightFrames : walkLeftFrames;
            default: // IDLE
                if (posState == PositionState.CEILING) return ceilFrames;
                // 修正墙面贴图映射：左墙显示右侧贴图，右墙显示左侧贴图
                if (posState == PositionState.WALL_LEFT) return (wallRightFrames != null) ? wallRightFrames : wallLeftFrames;
                if (posState == PositionState.WALL_RIGHT) return (wallLeftFrames != null) ? wallLeftFrames : wallRightFrames;
                return idleFrames;
        }
    }

    private void updateWindowLayout() {
        if (layoutParams == null) layoutParams = (WindowManager.LayoutParams) getLayoutParams();
        if (layoutParams != null) {
            // 如果处于吸附状态，则把绘制偏移应用到布局坐标，这样贴图不会被 view 边界裁剪掉
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

    // --- 绘制与触摸 ---

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap[] frames = getCurrentBitmaps();
        if (frames == null || frames.length == 0) return;

        Bitmap bitmap = frames[currentFrameIndex % frames.length];
        if (bitmap != null) {
            canvas.save();

            // 已移除所有基于位置的旋转，贴图方向由左右贴图区分处理

            // 计算绘制偏移
            float drawX = 0;
            float drawY = 0;

            // 左右墙壁吸附时，贴图向该方向多移动px
            if (posState == PositionState.WALL_LEFT) {
                drawX = -ADHERE_DRAW_OFFSET;
            } else if (posState == PositionState.WALL_RIGHT) {
                drawX = ADHERE_DRAW_OFFSET;
            }

            // 竖直方向偏移：行走和站立时向上偏移16px，吸附时多偏移94px
            if (posState == PositionState.CEILING) {
                if (animState == AnimationState.MOVE || animState == AnimationState.IDLE) {
                    drawY = -ADHERE_DRAW_OFFSET - 16; // 天花板移动/站立时向上偏移94px+16px
                } else {
                    drawY = -ADHERE_DRAW_OFFSET; // 天花板吸附时向上偏移94px
                }
            } else if (posState == PositionState.FLOOR) {
                if (animState == AnimationState.MOVE || animState == AnimationState.IDLE) {
                    drawY = -16; // 地面行走和站立时向上偏移16px
                } else {
                    drawY = ADHERE_DRAW_OFFSET; // 地面吸附时向下偏移94px
                }
            }

            // 绘制图片
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
                lastTouchX = rawX;
                lastTouchY = rawY;
                lastTouchTime = System.currentTimeMillis();
                // 使用事件的本地坐标记录触点在视图内的偏移，避免不同窗口坐标系带来的误差
                dragOffsetX = event.getX();
                dragOffsetY = event.getY();
                vx = 0;
                vy = 0;
                playRandomSound();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    // 计算瞬时速度（用于投掷），使用原始触点差值以保留手感
                    long now = System.currentTimeMillis();
                    float dt = now - lastTouchTime;
                    if (dt > 0) {
                        vx = (rawX - lastTouchX); // 简单的速度计算
                        vy = (rawY - lastTouchY);
                        lastTouchTime = now;
                    }

                    // 使用触点偏移来设置视图左上角坐标，这样触点位置与视图内部位置一致，避免贴边判断偏移
                    x = rawX - dragOffsetX;
                    y = rawY - dragOffsetY;

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
        // 使用更严格的边缘判定（接近实际屏幕边缘），并在吸附时把坐标钳位到边缘，避免出现可见间距
        final float EPS = 1f;
        if (y >= screenHeight - characterHeight - EPS) {
            posState = PositionState.FLOOR;
            y = screenHeight - characterHeight; // 钳位
        } else if (y <= EPS) {
            posState = PositionState.CEILING;
            y = 0;
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