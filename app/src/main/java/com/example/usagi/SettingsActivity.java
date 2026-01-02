package com.example.usagi;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class SettingsActivity extends AppCompatActivity {

    private static final int PICK_IMAGE_REQUEST = 1;
    
    private SeekBar sbVolume, sbSpeed;
    private Switch swAutoStart, swShowUsagi;
    private TextView tvVolume, tvSpeed;
    private Button btnSelectBackground, btnClearBackground;
    private ImageView ivBackgroundPreview;
    
    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor editor;
    private File backgroundImageFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences("usagi_settings", MODE_PRIVATE);
        editor = sharedPreferences.edit();
        
        // 初始化背景文件路径
        backgroundImageFile = new File(getFilesDir(), "background_image.jpg");
        
        // 初始化控件
        sbVolume = findViewById(R.id.sb_volume);
        sbSpeed = findViewById(R.id.sb_speed);
        swAutoStart = findViewById(R.id.sw_auto_start);
        swShowUsagi = findViewById(R.id.sw_show_usagi);
        tvVolume = findViewById(R.id.tv_volume);
        tvSpeed = findViewById(R.id.tv_speed);
        btnSelectBackground = findViewById(R.id.btn_select_background);
        btnClearBackground = findViewById(R.id.btn_clear_background);
        ivBackgroundPreview = findViewById(R.id.iv_background_preview);
        
        // 加载保存的设置
        loadSettings();
        loadBackgroundPreview();
        
        // 设置音量滑块监听（添加空指针检查）
        if (sbVolume != null && tvVolume != null) {
            sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvVolume.setText("音量: " + progress + "%");
                    editor.putInt("volume", progress);
                    editor.apply();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        // 设置速度滑块监听（添加空指针检查）
        if (sbSpeed != null && tvSpeed != null) {
            sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvSpeed.setText("动画速度: " + progress + "%");
                    editor.putInt("speed", progress);
                    editor.apply();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        // 设置开机自启开关监听（添加空指针检查）
        if (swAutoStart != null) {
            swAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
                editor.putBoolean("auto_start", isChecked);
                editor.apply();
            });
        }

        // 设置显示/隐藏开关监听（添加空指针检查）
        if (swShowUsagi != null) {
            swShowUsagi.setOnCheckedChangeListener((buttonView, isChecked) -> {
                editor.putBoolean("show_usagi", isChecked);
                editor.apply();
            });
        }
        
        // 选择背景按钮监听
        if (btnSelectBackground != null) {
            btnSelectBackground.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "选择背景图片"), PICK_IMAGE_REQUEST);
            });
        }
        
        // 清除背景按钮监听
        if (btnClearBackground != null) {
            btnClearBackground.setOnClickListener(v -> {
                clearBackground();
            });
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri selectedImage = data.getData();
            try {
                // 从选择的图片中获取Bitmap
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), selectedImage);
                
                // 保存图片到本地文件
                FileOutputStream fos = new FileOutputStream(backgroundImageFile);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.flush();
                fos.close();
                
                // 保存背景设置
                editor.putBoolean("has_background", true);
                editor.apply();
                
                // 更新预览
                ivBackgroundPreview.setImageBitmap(bitmap);
                
                Toast.makeText(this, "背景已设置", Toast.LENGTH_SHORT).show();
                
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "设置背景失败", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    // 加载背景预览
    private void loadBackgroundPreview() {
        if (ivBackgroundPreview == null) return;
        
        boolean hasBackground = sharedPreferences.getBoolean("has_background", false);
        if (hasBackground && backgroundImageFile.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(backgroundImageFile.getAbsolutePath());
            if (bitmap != null) {
                ivBackgroundPreview.setImageBitmap(bitmap);
            }
        }
    }
    
    // 清除背景
    private void clearBackground() {
        if (backgroundImageFile.exists()) {
            backgroundImageFile.delete();
        }
        editor.putBoolean("has_background", false);
        editor.apply();
        
        if (ivBackgroundPreview != null) {
            ivBackgroundPreview.setImageBitmap(null);
        }
        
        Toast.makeText(this, "背景已清除", Toast.LENGTH_SHORT).show();
    }
    
    // 加载保存的设置
    private void loadSettings() {
        int volume = sharedPreferences.getInt("volume", 50);
        int speed = sharedPreferences.getInt("speed", 50);
        boolean autoStart = sharedPreferences.getBoolean("auto_start", false);
        boolean showUsagi = sharedPreferences.getBoolean("show_usagi", true);
        
        // 添加空指针检查
        if (sbVolume != null) {
            sbVolume.setProgress(volume);
        }
        if (sbSpeed != null) {
            sbSpeed.setProgress(speed);
        }
        if (swAutoStart != null) {
            swAutoStart.setChecked(autoStart);
        }
        if (swShowUsagi != null) {
            swShowUsagi.setChecked(showUsagi);
        }
        if (tvVolume != null) {
            tvVolume.setText("音量: " + volume + "%");
        }
        if (tvSpeed != null) {
            tvSpeed.setText("动画速度: " + speed + "%");
        }
    }
}