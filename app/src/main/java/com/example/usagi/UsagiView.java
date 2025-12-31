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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class UsagiView extends View {

    // 动画状态枚举 - 基于物理逻辑
    private enum AnimationState {
        FALL,           // 下落（出场）
        STAND,          // 站立
        WALK,           // 行走
        BOUNCE,         // 弹跳
        SIT,            // 坐下
        TIP,            // 倾斜
        TWIST,          // 扭动
        JUMP,           // 跳跃
        CREEP,          // 爬行
        PINCH_LEFT,     // 向左夹取
        PINCH_RIGHT,    // 向右夹取
        CLIMB,          // 攀爬（到天花板）
        CEIL            // 在天花板
    }

    // 位置状态枚举
    private enum PositionState {
        FLOOR,      // 地面（屏幕底部）
        AIR,        // 空中
        CEILING     // 天花板（屏幕顶部）
    }

    private Context context;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    
    // 屏幕尺寸
    private int screenWidth, screenHeight;
    
    // 角色位置和速度
    private int x, y;
    private float velocityX = 0;
    private float velocityY = 0;
    private float gravity = 0.5f;
    private float bounceFactor = 0.7f;
    
    // 动画相关
    private AnimationState currentState = AnimationState.FALL;
    private PositionState currentPosition = PositionState.AIR;
    private int currentFrame = 0;
    private long lastFrameTime = 0;
    private int frameDelay = 100;
    private long lastActionTime = 0;
    private long actionInterval = 5000; // 随机动作间隔
    
    // 图片资源
    private Bitmap[] fallFrames;
    private Bitmap[] standFrames;
    private Bitmap[] walkFrames;
    private Bitmap[] bounceFrames;
    private Bitmap[] sitFrames;
    private Bitmap[] tipFrames;
    private Bitmap[] twistFrames;
    private Bitmap[] jumpFrames;
    private Bitmap[] creepFrames;
    private Bitmap[] pinchLeftFrames;
    private Bitmap[] pinchRightFrames;
    private Bitmap[] climbFrames;
    private Bitmap[] ceilFrames;
    
    // 音频资源
    private SoundPool soundPool;
    private List<Integer> soundIds;
    
    // 随机数生成器
    private Random random = new Random();
    
    // 拖拽相关
    private boolean isDragging = false;
    private int lastTouchX, lastTouchY;
    private int touchDownTime = 0;
    
    // 动画完成后回调
    private Runnable onAnimationComplete;
    
    // 角色尺寸
    private int characterWidth = 200;
    private int characterHeight = 200;

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
        
        // 初始化位置（屏幕顶部中心）
        x = screenWidth / 2 - characterWidth / 2;
        y = -characterHeight; // 从屏幕外开始
        velocityY = 2; // 初始下落速度
        
        loadImages();
        loadSounds();
        startAnimation();
    }

    private void loadImages() {
        String packageName = context.getPackageName();
        
        fallFrames = loadFrameArray(new String[]{"fall_1"}, packageName);
        standFrames = loadFrameArray(new String[]{"stand_1"}, packageName);
        walkFrames = loadFrameArray(new String[]{"walk_1", "walk_2"}, packageName);
        bounceFrames = loadFrameArray(new String[]{"bounce_1", "bounce_2"}, packageName);
        sitFrames = loadFrameArray(new String[]{"sit_1"}, packageName);
        tipFrames = loadFrameArray(new String[]{"tip_1", "tip_2"}, packageName);
        twistFrames = loadFrameArray(new String[]{"twist_1", "twist_2"}, packageName);
        jumpFrames = loadFrameArray(new String[]{"jump_1"}, packageName);
        creepFrames = loadFrameArray(new String[]{"creep_1", "creep_2"}, packageName);
        pinchLeftFrames = loadFrameArray(new String[]{"pinch_left_1", "pinch_left_2", "pinch_left_3"}, packageName);
        pinchRightFrames = loadFrameArray(new String[]{"pinch_right_1", "pinch_right_2", "pinch_right_3"}, packageName);
        climbFrames = loadFrameArray(new String[]{"climb_1", "climb_2", "climb_3"}, packageName);
        ceilFrames = loadFrameArray(new String[]{"ceil_1", "ceil_2", "ceil_3"}, packageName);
    }
    
    private Bitmap[] loadFrameArray(String[] names, String packageName) {
        Bitmap[] frames = new Bitmap[names.length];
        for (int i = 0; i < names.length; i++) {
            int resourceId = getResources().getIdentifier(names[i], "drawable", packageName);
            if (resourceId > 0) {
                frames[i] = BitmapFactory.decodeResource(getResources(), resourceId);
                if (frames[i] != null) {
                    characterWidth = Math.max(characterWidth, frames[i].getWidth());
                    characterHeight = Math.max(characterHeight, frames[i].getHeight());
                }
            }
        }
        return frames;
    }

    private void loadSounds() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(audioAttributes)
                .build();
        
        soundIds = new ArrayList<>();
        String packageName = context.getPackageName();
        
        // 加载所有声音资源
        String[] soundNames = {
            "start", "sit", "sound_double", "sound_5",
            "ha_doubt", "ha_doubt_2", "ha_doubt_3", "ha_doubt_4", "ha_doubt_5", "ha_excited",
            "hum_doubt", "hum_none", "hum_none_2",
            "puru_excited_2", "puru_none", "purupuru_excited",
            "wula_cute", "wula_loud", "wula_none", "wula_none_2", "wula_none_3", "wula_sing",
            "wula_wula_wula_easy", "wula_yahayaha_happy", "wulayahayahawula_happy",
            "yaha_excited", "yaha_excited_2", "yaha_excited_4", "yaha_none", "yahaha_none", "ya_ha_none",
            "wu_ya_yi_ha_none"
        };
        
        for (String name : soundNames) {
            int resourceId = getResources().getIdentifier(name, "raw", packageName);
            if (resourceId > 0) {
                int soundId = soundPool.load(context, resourceId, 1);
                soundIds.add(soundId);
            }
        }
        
        // 播放启动音效
        if (!soundIds.isEmpty()) {
            soundPool.play(soundIds.get(0), 1.0f, 1.0f, 0, 0, 1.0f);
        }
    }
    
    private void playRandomSound() {
        if (!soundIds.isEmpty()) {
            int soundId = soundIds.get(random.nextInt(soundIds.size()));
            soundPool.play(soundId, 0.8f, 0.8f, 0, 0, 1.0f);
        }
    }

    private void startAnimation() {
        final Handler handler = new Handler();
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateAnimation();
                updatePosition();
                invalidate();
                handler.postDelayed(this, 16);
            }
        });
    }

    private void updateAnimation() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastFrameTime > frameDelay) {
            currentFrame++;
            lastFrameTime = currentTime;
            
            Bitmap[] currentFrames = getCurrentFrames();
            if (currentFrames != null && currentFrames.length > 0) {
                if (currentFrame >= currentFrames.length) {
                    // 动画完成，处理状态切换
                    handleAnimationComplete();
                }
            }
        }
        
        // 检查是否需要触发随机动作
        if (!isDragging && currentTime - lastActionTime > actionInterval) {
            triggerRandomAction();
            lastActionTime = currentTime;
            actionInterval = 3000 + random.nextInt(5000); // 3-8秒随机间隔
        }
    }
    
    private Bitmap[] getCurrentFrames() {
        switch (currentState) {
            case FALL: return fallFrames;
            case STAND: return standFrames;
            case WALK: return walkFrames;
            case BOUNCE: return bounceFrames;
            case SIT: return sitFrames;
            case TIP: return tipFrames;
            case TWIST: return twistFrames;
            case JUMP: return jumpFrames;
            case CREEP: return creepFrames;
            case PINCH_LEFT: return pinchLeftFrames;
            case PINCH_RIGHT: return pinchRightFrames;
            case CLIMB: return climbFrames;
            case CEIL: return ceilFrames;
            default: return standFrames;
        }
    }
    
    private void handleAnimationComplete() {
        currentFrame = 0;
        
        switch (currentState) {
            case FALL:
                // 下落完成后检查位置
                if (y >= screenHeight - characterHeight - 50) {
                    currentPosition = PositionState.FLOOR;
                    changeState(AnimationState.STAND);
                }
                break;
                
            case BOUNCE:
            case JUMP:
                changeState(AnimationState.STAND);
                break;
                
            case PINCH_LEFT:
            case PINCH_RIGHT:
                // 夹取动画完成后，如果在地面上则攀爬到天花板
                if (currentPosition == PositionState.FLOOR) {
                    changeState(AnimationState.CLIMB);
                } else {
                    changeState(AnimationState.STAND);
                }
                break;
                
            case CLIMB:
                // 攀爬完成，到达天花板
                currentPosition = PositionState.CEILING;
                changeState(AnimationState.CEIL);
                break;
                
            case CEIL:
                // 在天花板可以执行各种动作
                triggerCeilingAction();
                break;
                
            case WALK:
            case CREEP:
            case TIP:
            case TWIST:
            case SIT:
                // 这些动作完成后返回站立
                if (currentPosition == PositionState.CEILING) {
                    changeState(AnimationState.CEIL);
                } else {
                    changeState(AnimationState.STAND);
                }
                break;
                
            case STAND:
                // 站立状态下可以保持或切换
                break;
        }
    }
    
    private void triggerCeilingAction() {
        AnimationState[] ceilingActions = {
            AnimationState.CEIL, AnimationState.TIP, AnimationState.TWIST, AnimationState.WALK
        };
        AnimationState newAction = ceilingActions[random.nextInt(ceilingActions.length)];
        changeState(newAction);
    }
    
    private void changeState(AnimationState newState) {
        currentState = newState;
        currentFrame = 0;
        
        // 根据动作播放音效
        if (newState != AnimationState.STAND && newState != AnimationState.CEIL) {
            playRandomSound();
        }
    }
    
    private void triggerRandomAction() {
        if (isDragging) return;
        
        AnimationState possibleAction;
        
        switch (currentPosition) {
            case FLOOR:
                AnimationState[] floorActions = {
                    AnimationState.WALK, AnimationState.BOUNCE, AnimationState.JUMP,
                    AnimationState.SIT, AnimationState.TIP, AnimationState.TWIST, AnimationState.CREEP,
                    AnimationState.PINCH_LEFT, AnimationState.PINCH_RIGHT
                };
                possibleAction = floorActions[random.nextInt(floorActions.length)];
                break;
                
            case CEILING:
                AnimationState[] ceilingActions = {
                    AnimationState.WALK, AnimationState.TIP, AnimationState.TWIST
                };
                possibleAction = ceilingActions[random.nextInt(ceilingActions.length)];
                break;
                
            case AIR:
                possibleAction = AnimationState.FALL;
                break;
                
            default:
                possibleAction = AnimationState.STAND;
        }
        
        changeState(possibleAction);
    }

    private void updatePosition() {
        if (isDragging) return;
        
        // 根据动画状态更新位置
        switch (currentState) {
            case FALL:
                velocityY += gravity;
                y += (int)velocityY;
                x += (int)velocityX;
                
                // 落地检测
                if (currentPosition != PositionState.CEILING && y >= screenHeight - characterHeight - 50) {
                    y = screenHeight - characterHeight - 50;
                    if (velocityY > 5) {
                        changeState(AnimationState.BOUNCE);
                    } else {
                        changeState(AnimationState.STAND);
                    }
                }
                break;
                
            case BOUNCE:
                velocityY = -15 * bounceFactor; // 向上弹跳
                y += (int)velocityY;
                x += velocityX;
                
                if (y <= screenHeight - characterHeight - 50) {
                    // 弹跳到最高点
                    velocityY = 0;
                    changeState(AnimationState.FALL);
                }
                break;
                
            case WALK:
            case CREEP:
                // 水平移动
                int direction = (currentPosition == PositionState.CEILING) ? -1 : 1;
                x += direction * 2;
                
                // 边界检测
                if (x <= 0) {
                    x = 0;
                } else if (x >= screenWidth - characterWidth) {
                    x = screenWidth - characterWidth;
                }
                break;
                
            case JUMP:
                // 跳跃
                if (currentPosition == PositionState.FLOOR) {
                    y -= 50;
                    if (y < screenHeight - characterHeight - 150) {
                        changeState(AnimationState.FALL);
                    }
                }
                break;
                
            case PINCH_LEFT:
            case PINCH_RIGHT:
                // 夹取动作时向两侧移动
                int pinchDirection = (currentState == AnimationState.PINCH_LEFT) ? -1 : 1;
                x += pinchDirection * 5;
                break;
                
            case CLIMB:
                // 攀爬到天花板
                y -= 3;
                if (y <= 0) {
                    y = 0;
                    handleAnimationComplete();
                }
                break;
                
            case CEIL:
            case TIP:
            case TWIST:
            case STAND:
            case SIT:
                // 在当前位置保持
                if (currentPosition == PositionState.CEILING && y > 0) {
                    y = 0;
                } else if (currentPosition == PositionState.FLOOR && y < screenHeight - characterHeight - 50) {
                    y = screenHeight - characterHeight - 50;
                }
                break;
        }
        
        updateWindowPosition();
    }

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
        
        Bitmap[] currentFrames = getCurrentFrames();
        if (currentFrames != null && currentFrames.length > 0) {
            Bitmap bitmap = currentFrames[currentFrame % currentFrames.length];
            if (bitmap != null) {
                // 在天花板时需要翻转角色
                if (currentPosition == PositionState.CEILING) {
                    canvas.save();
                    canvas.scale(1, -1, characterWidth / 2, characterHeight / 2);
                    canvas.drawBitmap(bitmap, 0, 0, new Paint());
                    canvas.restore();
                } else {
                    canvas.drawBitmap(bitmap, 0, 0, new Paint());
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int touchX = (int) event.getRawX();
        int touchY = (int) event.getRawY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDragging = true;
                lastTouchX = touchX;
                lastTouchY = touchY;
                touchDownTime = (int) System.currentTimeMillis();
                playRandomSound();
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    int deltaX = touchX - lastTouchX;
                    int deltaY = touchY - lastTouchY;
                    x += deltaX;
                    y += deltaY;
                    updateWindowPosition();
                    lastTouchX = touchX;
                    lastTouchY = touchY;
                }
                break;
                
            case MotionEvent.ACTION_UP:
                isDragging = false;
                int touchDuration = (int) System.currentTimeMillis() - touchDownTime;
                
                if (touchDuration < 300) {
                    // 短按 - 点击交互
                    handleClick();
                } else {
                    // 拖拽释放 - 根据位置决定状态
                    handleDragRelease();
                }
                break;
                
            case MotionEvent.ACTION_POINTER_DOWN:
                // 双指捏合 - 触发夹取动作
                if (event.getPointerCount() == 2) {
                    if (currentPosition == PositionState.FLOOR) {
                        // 随机选择左或右夹取
                        AnimationState pinchAction = random.nextBoolean() ? 
                            AnimationState.PINCH_LEFT : AnimationState.PINCH_RIGHT;
                        changeState(pinchAction);
                    } else if (currentPosition == PositionState.CEILING) {
                        // 从天花板跳下
                        changeState(AnimationState.FALL);
                        currentPosition = PositionState.AIR;
                        velocityY = 5;
                    }
                }
                break;
        }
        
        return true;
    }
    
    private void handleClick() {
        // 点击触发随机动作
        playRandomSound();
        
        switch (currentPosition) {
            case FLOOR:
                AnimationState[] clickActions = {
                    AnimationState.JUMP, AnimationState.BOUNCE, AnimationState.TIP, AnimationState.TWIST
                };
                changeState(clickActions[random.nextInt(clickActions.length)]);
                break;
                
            case CEILING:
                AnimationState[] ceilingClickActions = {
                    AnimationState.FALL, AnimationState.TIP, AnimationState.TWIST
                };
                AnimationState action = ceilingClickActions[random.nextInt(ceilingClickActions.length)];
                if (action == AnimationState.FALL) {
                    currentPosition = PositionState.AIR;
                    velocityY = 5;
                }
                changeState(action);
                break;
                
            case AIR:
                // 空中被点击，加速下落
                velocityY += 5;
                break;
        }
    }
    
    private void handleDragRelease() {
        // 根据拖拽到的位置决定状态
        if (y <= screenHeight * 0.3) {
            // 拖到屏幕上方1/3，尝试爬到天花板
            currentPosition = PositionState.AIR;
            changeState(AnimationState.CLIMB);
        } else if (y >= screenHeight * 0.7) {
            // 拖到屏幕下方1/3，到地面
            currentPosition = PositionState.FLOOR;
            changeState(AnimationState.STAND);
        } else {
            // 中间区域，保持当前状态
            changeState(AnimationState.STAND);
        }
    }
}
