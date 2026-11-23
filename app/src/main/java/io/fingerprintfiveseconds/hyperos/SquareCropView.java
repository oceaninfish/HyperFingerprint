package io.fingerprintfiveseconds.hyperos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

public final class SquareCropView extends View {
    record Position(float zoom, float centerX, float centerY) {
        static final Position CENTERED = new Position(1f, 0.5f, 0.5f);
    }

    private static final float MAX_ZOOM = 8f;
    private final RectF frame = new RectF();
    private final RectF imageBounds = new RectF();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint guides = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector pinch;
    private final float density;

    private Bitmap image;
    private Position position = Position.CENTERED;
    private float lastX;
    private float lastY;
    private boolean panning;

    public SquareCropView(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                lastX = detector.getFocusX();
                lastY = detector.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                float zoom = Math.clamp(position.zoom * detector.getScaleFactor(), 1f, MAX_ZOOM);
                float ratio = zoom / position.zoom;
                float left = focusX - (lastX - imageBounds.left) * ratio;
                float top = focusY - (lastY - imageBounds.top) * ratio;
                placeImage(zoom, left, top);
                lastX = focusX;
                lastY = focusY;
                invalidate();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
    }

    void showImage(Bitmap bitmap, Position savedPosition) {
        image = bitmap;
        position = savedPosition;
        placeAtPosition();
        invalidate();
    }

    Position position() {
        return position;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float side = Math.max(0, Math.min(width, height) - 24 * density);
        float left = (width - side) / 2;
        float top = (height - side) / 2;
        frame.set(left, top, left + side, top + side);
        placeAtPosition();
    }

    private void placeAtPosition() {
        if (image != null && !frame.isEmpty()) {
            float scale = imageScale(position.zoom);
            placeImage(position.zoom, frame.centerX() - position.centerX * image.getWidth() * scale,
                    frame.centerY() - position.centerY * image.getHeight() * scale);
        }
    }

    private float imageScale(float zoom) {
        return zoom * frame.width() / Math.min(image.getWidth(), image.getHeight());
    }

    private void placeImage(float zoom, float left, float top) {
        float scale = imageScale(zoom);
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;
        left = Math.min(frame.left, Math.max(frame.right - width, left));
        top = Math.min(frame.top, Math.max(frame.bottom - height, top));
        imageBounds.set(left, top, left + width, top + height);
        position = new Position(zoom, (frame.centerX() - left) / width, (frame.centerY() - top) / height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (image == null || frame.isEmpty()) {
            return;
        }
        canvas.drawBitmap(image, null, imageBounds, imagePaint);
        guides.setStyle(Paint.Style.FILL);
        guides.setColor(0xb5000000);
        canvas.drawRect(0, 0, getWidth(), frame.top, guides);
        canvas.drawRect(0, frame.bottom, getWidth(), getHeight(), guides);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, guides);
        canvas.drawRect(frame.right, frame.top, getWidth(), frame.bottom, guides);

        guides.setStrokeWidth(density);
        guides.setColor(0x99ffffff);
        for (int line = 1; line <= 2; line++) {
            float offset = line * frame.width() / 3;
            canvas.drawLine(frame.left + offset, frame.top, frame.left + offset, frame.bottom, guides);
            canvas.drawLine(frame.left, frame.top + offset, frame.right, frame.top + offset, guides);
        }
        guides.setStyle(Paint.Style.STROKE);
        guides.setStrokeWidth(2 * density);
        guides.setColor(Color.WHITE);
        canvas.drawRect(frame, guides);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (image == null || frame.isEmpty() || !isEnabled()) {
            return false;
        }
        pinch.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            int remaining = action == MotionEvent.ACTION_DOWN ? 0 : 1 - event.getActionIndex();
            panning = remaining >= 0 && (action == MotionEvent.ACTION_DOWN || event.getPointerCount() == 2);
            if (panning) {
                lastX = event.getX(remaining);
                lastY = event.getY(remaining);
            }
        } else if (action == MotionEvent.ACTION_MOVE && panning && event.getPointerCount() == 1) {
            float x = event.getX();
            float y = event.getY();
            placeImage(position.zoom, imageBounds.left + x - lastX, imageBounds.top + y - lastY);
            lastX = x;
            lastY = y;
            invalidate();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_UP) {
            panning = false;
        }
        return true;
    }

    Bitmap makeIcon() {
        if (image == null || frame.isEmpty()) {
            return null;
        }
        int size = FingerprintAppearance.ICON_SIZE;
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        float scale = size / frame.width();
        canvas.scale(scale, scale);
        canvas.translate(-frame.left, -frame.top);
        canvas.drawBitmap(image, null, imageBounds, imagePaint);
        return result;
    }
}
