package io.fingerprintfiveseconds.hyperos;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class IconCropActivity extends Activity {
    private static final int MAX_IMAGE_EDGE = 2048;
    private static final String CROP_ZOOM = "crop_zoom";
    private static final String CROP_X = "crop_x";
    private static final String CROP_Y = "crop_y";

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private SquareCropView crop;
    private TextView status;
    private Button apply;
    private Button cancel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.icon_crop_activity);
        crop = findViewById(R.id.crop_image);
        status = findViewById(R.id.crop_status);
        apply = findViewById(R.id.apply_crop);
        cancel = findViewById(R.id.cancel_crop);
        apply.setEnabled(false);
        apply.setOnClickListener(view -> save());
        cancel.setOnClickListener(view -> finish());

        View root = findViewById(R.id.crop_root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        root.requestApplyInsets();

        Uri image = getIntent().getData();
        if (image == null) {
            status.setText(R.string.icon_load_failed);
            return;
        }
        SquareCropView.Position position = savedInstanceState == null ? SquareCropView.Position.CENTERED
                : new SquareCropView.Position(savedInstanceState.getFloat(CROP_ZOOM, 1f),
                        savedInstanceState.getFloat(CROP_X, 0.5f), savedInstanceState.getFloat(CROP_Y, 0.5f));
        decoder.execute(() -> {
            Bitmap bitmap = decode(image);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (bitmap == null) {
                    status.setText(R.string.icon_load_failed);
                    return;
                }
                crop.showImage(bitmap, position);
                status.setText(R.string.crop_hint);
                apply.setEnabled(true);
            });
        });
    }

    private Bitmap decode(Uri uri) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, header, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int width = header.getSize().getWidth();
                        int height = header.getSize().getHeight();
                        int edge = Math.max(width, height);
                        if (edge > MAX_IMAGE_EDGE) {
                            float scale = (float) MAX_IMAGE_EDGE / edge;
                            decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                    Math.max(1, Math.round(height * scale)));
                        }
                    });
        } catch (IOException | RuntimeException error) {
            Log.w(FingerprintModule.TAG, "Could not open the selected image", error);
            return null;
        }
    }

    private void save() {
        Bitmap image = crop.makeIcon();
        if (image == null) {
            return;
        }
        crop.setEnabled(false);
        apply.setEnabled(false);
        cancel.setEnabled(false);
        status.setText(R.string.icon_saving);
        ((SettingsApplication) getApplication()).saveCroppedIcon(image, saved -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (saved) {
                finish();
            } else {
                crop.setEnabled(true);
                apply.setEnabled(true);
                cancel.setEnabled(true);
                status.setText(R.string.icon_save_failed);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        SquareCropView.Position position = crop.position();
        state.putFloat(CROP_ZOOM, position.zoom());
        state.putFloat(CROP_X, position.centerX());
        state.putFloat(CROP_Y, position.centerY());
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }
}
