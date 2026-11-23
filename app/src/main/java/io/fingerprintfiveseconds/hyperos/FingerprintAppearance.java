package io.fingerprintfiveseconds.hyperos;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class FingerprintAppearance {
    static final int ICON_SIZE = 256;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final String LOCK_SCREEN_ICON = "finger_circle_image_normal";

    private final Field sourceContext;
    private final Field image;
    private final Field background;
    private final Field viewScale;
    private final Method drawMatrix;
    private final RemoteFile openFile;
    private final Executor loader = Executors.newSingleThreadExecutor();
    private final Map<Bitmap, Integer> staticFrames = Collections.synchronizedMap(new WeakHashMap<>());
    private final Paint dozePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private volatile IconSize lockScreenSize;
    private volatile String requestedFile = "";
    private volatile Style style = new Style(null, IconSettings.DEFAULTS.opacity(), IconSettings.DEFAULT_SIZE_TENTHS);
    private volatile boolean drawingFailed;

    FingerprintAppearance(Class<?> frames, Class<?> painter, RemoteFile openFile)
            throws ReflectiveOperationException {
        this.openFile = openFile;
        sourceContext = field(frames, "mContext");
        image = field(painter, "mBitmap");
        background = field(painter, "mBackgroundBitmap");
        viewScale = field(painter, "mScale");
        drawMatrix = painter.getDeclaredMethod("configureDrawMatrix", Bitmap.class, float.class);
        drawMatrix.setAccessible(true);
    }

    void captureFrame(Object source, int id, Bitmap bitmap) throws IllegalAccessException {
        Context context = (Context) sourceContext.get(source);
        Resources resources = context.getResources();
        String name = resources.getResourceEntryName(id);
        // Enrollment uses a separate asset. Animation frames use their own resources.
        switch (name) {
            case "finger_circle_image_grey", "finger_circle_image_light", LOCK_SCREEN_ICON,
                 "finger_circle_image_aod" -> staticFrames.put(bitmap, lockScreenSize(resources, id, name, bitmap));
        }
    }

    private int lockScreenSize(Resources resources, int frameId, String name, Bitmap frame) {
        int density = resources.getDisplayMetrics().densityDpi;
        IconSize cached = lockScreenSize;
        if (cached != null && cached.resources == resources && cached.density == density) {
            return cached.pixels;
        }

        // AOD has extra transparent padding. Use the bright lock-screen resource at the same density.
        if (LOCK_SCREEN_ICON.equals(name)) {
            int pixels = squareSide(frame);
            lockScreenSize = new IconSize(resources, density, pixels);
            return pixels;
        }
        int lockIconId = resources.getIdentifier(LOCK_SCREEN_ICON, "drawable",
                resources.getResourcePackageName(frameId));
        if (lockIconId != 0) {
            Bitmap lockIcon = BitmapFactory.decodeResource(resources, lockIconId);
            if (lockIcon != null) {
                int pixels = squareSide(lockIcon);
                lockIcon.recycle();
                lockScreenSize = new IconSize(resources, density, pixels);
                return pixels;
            }
        }
        return squareSide(frame);
    }

    boolean hasCustomStyle() {
        return !drawingFailed && style.active(false);
    }

    boolean drawStaticFrame(View view, Canvas canvas, boolean dozing) {
        Style current = style;
        if (!current.active(dozing)) {
            return false;
        }
        try {
            Bitmap nativeIcon = (Bitmap) image.get(view);
            if (nativeIcon == null || nativeIcon.isRecycled() || background.get(view) != null) {
                return false;
            }
            Integer size = staticFrames.get(nativeIcon);
            if (size == null) {
                return false;
            }
            Matrix matrix = (Matrix) drawMatrix.invoke(view, nativeIcon, viewScale.getFloat(view));
            Bitmap icon = current.bitmap != null ? current.bitmap : nativeIcon;
            if (icon != nativeIcon) {
                matrix.preTranslate((nativeIcon.getWidth() - size) / 2f,
                        (nativeIcon.getHeight() - size) / 2f);
                matrix.preScale((float) size / icon.getWidth(), (float) size / icon.getHeight());
            }
            if (current.sizeTenths != IconSettings.DEFAULT_SIZE_TENTHS) {
                float factor = current.sizeTenths / 10f;
                matrix.preScale(factor, factor, icon.getWidth() / 2f, icon.getHeight() / 2f);
            }
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (dozing || current.opacity > 0) {
                // The native view controls the AOD fade independently of the lock-screen slider.
                canvas.drawBitmap(icon, matrix, dozing ? dozePaint : current.paint);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            drawingFailed = true;
            Log.w(FingerprintModule.TAG, "Could not draw the custom static icon", error);
            return false;
        }
    }

    void update(IconSettings settings, Handler main, Runnable redraw) {
        String name = settings.iconFile();
        boolean changedFile = !requestedFile.equals(name);
        if (!changedFile && style.opacity == settings.opacity() && style.sizeTenths == settings.sizeTenths()) {
            return;
        }
        if (changedFile) {
            requestedFile = name;
        }
        style = new Style(changedFile ? null : style.bitmap, settings.opacity(), settings.sizeTenths());
        redraw.run();
        if (changedFile && !name.isEmpty()) {
            loader.execute(() -> {
                if (!name.equals(requestedFile)) {
                    return;
                }
                Bitmap bitmap = loadIcon(name);
                main.post(() -> {
                    if (name.equals(requestedFile) && bitmap != null) {
                        style = new Style(bitmap, style.opacity, style.sizeTenths);
                        redraw.run();
                    }
                });
            });
        }
    }

    private Bitmap loadIcon(String name) {
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(openFile.open(name))) {
            byte[] data = input.readNBytes(MAX_FILE_BYTES + 1);
            if (data.length > MAX_FILE_BYTES) {
                throw new IOException("The icon file is too large");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth != ICON_SIZE || options.outHeight != ICON_SIZE
                    || !"image/png".equals(options.outMimeType)) {
                throw new IOException("Invalid icon dimensions or format");
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | RuntimeException error) {
            Log.w(FingerprintModule.TAG, "Could not load the shared icon", error);
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static int squareSide(Bitmap bitmap) {
        return Math.min(bitmap.getWidth(), bitmap.getHeight());
    }

    private record IconSize(Resources resources, int density, int pixels) {}

    @FunctionalInterface
    interface RemoteFile {
        ParcelFileDescriptor open(String name) throws IOException;
    }

    private static final class Style {
        final Bitmap bitmap;
        final int opacity;
        final int sizeTenths;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Style(Bitmap bitmap, int opacity, int sizeTenths) {
            this.bitmap = bitmap;
            this.opacity = opacity;
            this.sizeTenths = sizeTenths;
            paint.setAlpha(Math.round(255 * opacity / 100f));
        }

        boolean active(boolean dozing) {
            return bitmap != null || sizeTenths != IconSettings.DEFAULT_SIZE_TENTHS || (!dozing && opacity != 100);
        }
    }
}
