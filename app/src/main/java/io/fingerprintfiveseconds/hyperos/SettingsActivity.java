package io.fingerprintfiveseconds.hyperos;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    private final Runnable onSettingsChanged = this::render;

    private SettingsApplication settings;
    private SeekBar slider;
    private Button decrease;
    private Button increase;
    private TextView value;
    private TextView syncStatus;
    private Switch pickup;
    private Switch wake;
    private Switch tap;
    private Switch touch;
    private boolean dragging;
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        settings = (SettingsApplication) getApplication();
        slider = findViewById(R.id.duration_slider);
        value = findViewById(R.id.duration_value);
        decrease = findViewById(R.id.decrease_duration);
        increase = findViewById(R.id.increase_duration);
        syncStatus = findViewById(R.id.sync_status);
        pickup = findViewById(R.id.show_on_pickup);
        wake = findViewById(R.id.show_on_wake);
        tap = findViewById(R.id.show_on_tap);
        touch = findViewById(R.id.show_on_touch);

        CompoundButton.OnCheckedChangeListener triggerChanged = (button, checked) -> {
            if (!rendering) {
                boolean tapEnabled = tap.isChecked() && !(button == touch && checked);
                boolean touchEnabled = touch.isChecked() && !(button == tap && checked);
                settings.saveTriggers(pickup.isChecked(), wake.isChecked(), tapEnabled, touchEnabled);
            }
        };
        pickup.setOnCheckedChangeListener(triggerChanged);
        wake.setOnCheckedChangeListener(triggerChanged);
        tap.setOnCheckedChangeListener(triggerChanged);
        touch.setOnCheckedChangeListener(triggerChanged);

        View root = findViewById(R.id.settings_root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        root.requestApplyInsets();

        decrease.setOnClickListener(view -> settings.saveDuration(slider.getProgress() - 1));
        increase.setOnClickListener(view -> settings.saveDuration(slider.getProgress() + 1));
        slider.setMin(DurationSetting.MIN);
        slider.setMax(DurationSetting.MAX);
        slider.setKeyProgressIncrement(1);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int seconds, boolean fromUser) {
                showDuration(seconds);
                if (fromUser && !dragging) {
                    settings.saveDuration(seconds);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                settings.saveDuration(seekBar.getProgress());
            }
        });
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        settings.addListener(onSettingsChanged);
    }

    @Override
    protected void onStop() {
        settings.removeListener(onSettingsChanged);
        super.onStop();
    }

    private void render() {
        IconSettings current = settings.current();
        if (!dragging) {
            slider.setProgress(current.duration());
            showDuration(current.duration());
        }
        rendering = true;
        pickup.setChecked(current.pickup());
        wake.setChecked(current.wake());
        tap.setChecked(current.tap());
        touch.setChecked(current.touch());
        rendering = false;
        int statusText = switch (settings.status()) {
            case WAITING -> R.string.sync_waiting;
            case SAVING -> R.string.sync_saving;
            case SYNCED -> R.string.sync_synced;
            case FAILED -> R.string.sync_failed;
        };
        syncStatus.setText(statusText);
    }

    private void showDuration(int seconds) {
        value.setText(getString(R.string.duration_value, seconds));
        decrease.setEnabled(seconds > DurationSetting.MIN);
        increase.setEnabled(seconds < DurationSetting.MAX);
    }
}
