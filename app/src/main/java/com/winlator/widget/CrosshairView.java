package com.winlator.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.math.Mathf;

/**
 * Full-screen overlay that draws a crosshair marking the auto-clicker target.
 * When {@code editable} it can be dragged to reposition; otherwise it is a passive
 * indicator that lets touches pass through to the views below. The position is kept
 * as a fraction (0..1) of the view so it is independent of resolution.
 */
public class CrosshairView extends View {
    public interface OnPositionChangeListener {
        void onPositionChanged(float fractionX, float fractionY);
    }

    private float fractionX = 0.5f;
    private float fractionY = 0.5f;
    private boolean editable = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnPositionChangeListener positionChangeListener;

    public CrosshairView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
    }

    public void setOnPositionChangeListener(OnPositionChangeListener listener) {
        this.positionChangeListener = listener;
    }

    public void setFraction(float fractionX, float fractionY) {
        this.fractionX = Mathf.clamp(fractionX, 0.0f, 1.0f);
        this.fractionY = Mathf.clamp(fractionY, 0.0f, 1.0f);
        invalidate();
    }

    public float getFractionX() {
        return fractionX;
    }

    public float getFractionY() {
        return fractionY;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editable) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                int width = getWidth();
                int height = getHeight();
                if (width > 0 && height > 0) {
                    fractionX = Mathf.clamp(event.getX() / width, 0.0f, 1.0f);
                    fractionY = Mathf.clamp(event.getY() / height, 0.0f, 1.0f);
                    invalidate();
                    if (positionChangeListener != null)
                        positionChangeListener.onPositionChanged(fractionX, fractionY);
                }
                return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = fractionX * getWidth();
        float cy = fractionY * getHeight();
        float radius = dp(14);
        float arm = dp(10);

        paint.setColor(0xAA000000);
        paint.setStrokeWidth(dp(4));
        drawMarks(canvas, cx, cy, radius, arm);

        paint.setColor(editable ? 0xFF00E5FF : 0xFFFF1744);
        paint.setStrokeWidth(dp(2));
        drawMarks(canvas, cx, cy, radius, arm);
    }

    private void drawMarks(Canvas canvas, float cx, float cy, float radius, float arm) {
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.drawLine(cx - radius - arm, cy, cx - radius, cy, paint);
        canvas.drawLine(cx + radius, cy, cx + radius + arm, cy, paint);
        canvas.drawLine(cx, cy - radius - arm, cx, cy - radius, paint);
        canvas.drawLine(cx, cy + radius, cx, cy + radius + arm, paint);
        canvas.drawLine(cx - dp(3), cy, cx + dp(3), cy, paint);
        canvas.drawLine(cx, cy - dp(3), cx, cy + dp(3), paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
