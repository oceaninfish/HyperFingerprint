package io.fingerprintfiveseconds.hyperos;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final int PICK_ICON = 1;

    private final Runnable onSettingsChanged = this::render;

    private SettingsApplication settings;
    private SeekBar slider;
    private Button decrease;
    private Button increase;
    private TextView value;
    private TextView syncStatus;
    private ImageButton restartScopes;
    private TextView restartHint;
    private Switch pickup;
    private Switch wake;
    private Switch tap;
    private Switch touch;
    private ImageView iconPreview;
    private TextView iconSource;
    private Button restoreIcon;
    private SeekBar sizeSlider;
    private TextView sizeValue;
    private SeekBar opacitySlider;
    private TextView opacityValue;
    private String previewFile;
    private boolean draggingSize;
    private boolean draggingOpacity;
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
        restartScopes = findViewById(R.id.restart_scopes);
        restartHint = findViewById(R.id.restart_hint);
        restartScopes.setOnClickListener(view -> settings.restartScopes());
        pickup = findViewById(R.id.show_on_pickup);
        wake = findViewById(R.id.show_on_wake);
        tap = findViewById(R.id.show_on_tap);
        touch = findViewById(R.id.show_on_touch);
        iconPreview = findViewById(R.id.icon_preview);
        iconSource = findViewById(R.id.icon_source);
        restoreIcon = findViewById(R.id.restore_icon);
        sizeSlider = findViewById(R.id.icon_size_slider);
        sizeValue = findViewById(R.id.icon_size_value);
        opacitySlider = findViewById(R.id.opacity_slider);
        opacityValue = findViewById(R.id.opacity_value);
        findViewById(R.id.choose_icon).setOnClickListener(view -> chooseIcon());
        restoreIcon.setOnClickListener(view -> settings.restoreSystemIcon());
        sizeSlider.setMin(IconSettings.MIN_SIZE_TENTHS);
        sizeSlider.setMax(IconSettings.MAX_SIZE_TENTHS);
        sizeSlider.setKeyProgressIncrement(1);
        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int tenths, boolean fromUser) {
                showIconSize(tenths);
                if (fromUser && !draggingSize) {
                    settings.saveSize(tenths);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingSize = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingSize = false;
                settings.saveSize(bar.getProgress());
            }
        });
        opacitySlider.setKeyProgressIncrement(1);
        opacitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int opacity, boolean fromUser) {
                showOpacity(opacity);
                if (fromUser && !draggingOpacity) {
                    settings.saveOpacity(opacity);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingOpacity = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingOpacity = false;
                settings.saveOpacity(bar.getProgress());
            }
        });

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
        boolean restarting = settings.isRestartingScopes();
        restartScopes.setEnabled(!restarting);
        restartHint.setText(restarting ? R.string.restarting_scopes : R.string.restart_hint);
        if (!current.iconFile().equals(previewFile)) {
            previewFile = current.iconFile();
            Bitmap preview = settings.iconPreview(previewFile);
            iconPreview.setImageBitmap(preview);
            findViewById(R.id.icon_preview_background).setVisibility(preview == null ? View.GONE : View.VISIBLE);
            iconSource.setText(preview == null ? R.string.icon_system : R.string.icon_custom);
            restoreIcon.setEnabled(!previewFile.isEmpty());
        }
        if (!draggingOpacity) {
            opacitySlider.setProgress(current.opacity());
            showOpacity(current.opacity());
        }
        if (!draggingSize) {
            sizeSlider.setProgress(current.sizeTenths());
            showIconSize(current.sizeTenths());
        }
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

    private void showOpacity(int opacity) {
        opacityValue.setText(getString(R.string.opacity_value, opacity));
        iconPreview.setAlpha(opacity / 100f);
    }

    private void showIconSize(int tenths) {
        sizeValue.setText(getString(R.string.icon_size_value, tenths / 10f));
    }

    private void chooseIcon() {
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PICK_ICON);
        } catch (ActivityNotFoundException pickerUnavailable) {
            try {
                Intent documents = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(documents, PICK_ICON);
            } catch (ActivityNotFoundException documentsUnavailable) {
                Toast.makeText(this, R.string.icon_picker_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_ICON && result == RESULT_OK && data != null && data.getData() != null) {
            startActivity(new Intent(this, IconCropActivity.class).setData(data.getData())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }
    }
}
