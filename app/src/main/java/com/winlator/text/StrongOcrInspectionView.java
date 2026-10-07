package com.winlator.text;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.R;

import java.util.ArrayList;
import java.util.List;

public final class StrongOcrInspectionView extends View {
    public interface Listener {
        void onLineRequested(RectF normalizedRegion);
        void onCloseRequested();
    }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> regions = new ArrayList<>();
    private final RectF closeButton = new RectF();
    private final RectF destination = new RectF();
    private final Listener listener;
    private Bitmap snapshot;

    public StrongOcrInspectionView(
            Context context,
            Bitmap snapshot,
            List<RectF> regions,
            Listener listener
    ) {
        super(context);
        if (snapshot == null || snapshot.isRecycled()) {
            throw new IllegalArgumentException("A valid OCR snapshot is required.");
        }
        this.snapshot = snapshot;
        if (regions != null) {
            for (RectF region : regions) this.regions.add(new RectF(region));
        }
        this.listener = listener;
        setClickable(true);
        float density = getResources().getDisplayMetrics().density;
        boxPaint.setColor(0xFF4CAF50);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3 * density);
        closePaint.setColor(0xDD202124);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public Bitmap copySnapshot() {
        if (snapshot == null || snapshot.isRecycled()) return null;
        return snapshot.copy(Bitmap.Config.ARGB_8888, false);
    }

    public void releaseSnapshot() {
        if (snapshot != null && !snapshot.isRecycled()) snapshot.recycle();
        snapshot = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseSnapshot();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (snapshot == null || snapshot.isRecycled()) return;
        destination.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(snapshot, null, destination, bitmapPaint);
        for (RectF region : regions) {
            canvas.drawRect(
                    region.left * getWidth(),
                    region.top * getHeight(),
                    region.right * getWidth(),
                    region.bottom * getHeight(),
                    boxPaint
            );
        }
        float density = getResources().getDisplayMetrics().density;
        closeButton.set(
                getWidth() - 112 * density,
                16 * density,
                getWidth() - 16 * density,
                62 * density
        );
        canvas.drawRoundRect(closeButton, 8 * density, 8 * density, closePaint);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = closeButton.centerY() -
                (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(
                getContext().getString(R.string.close),
                closeButton.centerX(),
                baseline,
                textPaint
        );
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(
                getContext().getString(R.string.game_text_inspection_hint),
                18 * density,
                42 * density,
                textPaint
        );
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        if (closeButton.contains(event.getX(), event.getY())) {
            listener.onCloseRequested();
            return true;
        }
        float normalizedX = event.getX() / getWidth();
        float normalizedY = event.getY() / getHeight();
        for (RectF region : regions) {
            if (region.contains(normalizedX, normalizedY)) {
                listener.onLineRequested(expand(region));
                return true;
            }
        }
        return true;
    }

    private static RectF expand(RectF region) {
        float horizontal = Math.max(0.01f, region.width() * 0.05f);
        float vertical = Math.max(0.01f, region.height() * 0.25f);
        return new RectF(
                Math.max(0, region.left - horizontal),
                Math.max(0, region.top - vertical),
                Math.min(1, region.right + horizontal),
                Math.min(1, region.bottom + vertical)
        );
    }
}
