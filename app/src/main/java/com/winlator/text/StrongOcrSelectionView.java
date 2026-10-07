package com.winlator.text;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public final class StrongOcrSelectionView extends View {
    public interface Listener {
        void onScanRequested(RectF normalizedRegion);
    }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF selection = new RectF();
    private final RectF fullScreenButton = new RectF();
    private final RectF destination = new RectF();
    private final Listener listener;
    private Bitmap snapshot;
    private float startX;
    private float startY;
    private boolean completed;

    public StrongOcrSelectionView(Context context, Bitmap snapshot, Listener listener) {
        super(context);
        if (snapshot == null || snapshot.isRecycled()) {
            throw new IllegalArgumentException("A valid snapshot is required");
        }
        this.snapshot = snapshot;
        this.listener = listener;
        setClickable(true);

        float density = getResources().getDisplayMetrics().density;
        shadePaint.setColor(0x99000000);
        borderPaint.setColor(0xFF4CAF50);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3 * density);
        buttonPaint.setColor(0xE61B5E20);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public Bitmap takeSnapshot() {
        Bitmap result = snapshot;
        snapshot = null;
        return result;
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
        super.onDraw(canvas);
        if (snapshot == null || snapshot.isRecycled()) return;

        float density = getResources().getDisplayMetrics().density;
        destination.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(snapshot, null, destination, bitmapPaint);
        canvas.drawRect(destination, shadePaint);

        if (!selection.isEmpty()) {
            canvas.save();
            canvas.clipRect(selection);
            canvas.drawBitmap(snapshot, null, destination, bitmapPaint);
            canvas.restore();
            canvas.drawRect(selection, borderPaint);
        }

        fullScreenButton.set(16 * density, 16 * density, 210 * density, 64 * density);
        canvas.drawRoundRect(fullScreenButton, 8 * density, 8 * density, buttonPaint);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float textBaseline = fullScreenButton.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(
                getContext().getString(com.winlator.R.string.game_text_strong_full_screen),
                fullScreenButton.centerX(),
                textBaseline,
                textPaint
        );

        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(
                getContext().getString(com.winlator.R.string.game_text_strong_drag_hint),
                18 * density,
                92 * density,
                textPaint
        );
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (completed) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (fullScreenButton.contains(event.getX(), event.getY())) {
                    completed = true;
                    listener.onScanRequested(new RectF(0.0f, 0.0f, 1.0f, 1.0f));
                    return true;
                }
                startX = event.getX();
                startY = event.getY();
                selection.set(startX, startY, startX, startY);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                updateSelection(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                updateSelection(event.getX(), event.getY());
                if (selection.width() >= getWidth() * 0.03f &&
                        selection.height() >= getHeight() * 0.03f) {
                    completed = true;
                    listener.onScanRequested(new RectF(
                            selection.left / getWidth(),
                            selection.top / getHeight(),
                            selection.right / getWidth(),
                            selection.bottom / getHeight()
                    ));
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateSelection(float x, float y) {
        selection.set(
                Math.max(0, Math.min(startX, x)),
                Math.max(0, Math.min(startY, y)),
                Math.min(getWidth(), Math.max(startX, x)),
                Math.min(getHeight(), Math.max(startY, y))
        );
        invalidate();
    }
}
