package com.example.usagi;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends AppCompatActivity {

    private SeekBar sbVolume, sbSpeed;
    private Switch swAutoStart, swShowUsagi;
    private TextView tvVolume, tvSpeed;
    
    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences("usagi_settings", MODE_PRIVATE);
        editor = sharedPreferences.edit();
        
        // 初始化控件
        sbVolume = findViewById(R.id.sb_volume);
        sbSpeed = findViewById(R.id.sb_speed);
        swAutoStart = findViewById(R.id.sw_auto_start);
        swShowUsagi = findViewById(R.id.sw_show_usagi);
        tvVolume = findViewById(R.id.tv_volume);
        tvSpeed = findViewById(R.id.tv_speed);
        
        // 加载保存的设置
        loadSettings();
        
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