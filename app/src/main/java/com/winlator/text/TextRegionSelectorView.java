package com.winlator.text;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public final class TextRegionSelectorView extends View {
    public interface Listener {
        void onRegionSelected(RectF normalizedRegion);
    }

    private final Paint shadePaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF selection = new RectF();
    private final Listener listener;
    private float startX;
    private float startY;

    public TextRegionSelectorView(Context context, RectF initialRegion, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(Color.TRANSPARENT);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);

        shadePaint.setColor(0x99000000);
        borderPaint.setColor(0xFF4CAF50);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
        labelPaint.setFakeBoldText(true);

        post(() -> {
            if (initialRegion != null) {
                selection.set(
                        initialRegion.left * getWidth(),
                        initialRegion.top * getHeight(),
                        initialRegion.right * getWidth(),
                        initialRegion.bottom * getHeight()
                );
                invalidate();
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), shadePaint);
        if (!selection.isEmpty()) {
            canvas.save();
            canvas.clipRect(selection);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            canvas.restore();
            canvas.drawRect(selection, borderPaint);
        }
        canvas.drawText("Drag around the game text. Back cancels.", 20, 34 * getResources().getDisplayMetrics().density, labelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
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
                if (selection.width() >= getWidth() * 0.05f && selection.height() >= getHeight() * 0.05f) {
                    listener.onRegionSelected(new RectF(
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
