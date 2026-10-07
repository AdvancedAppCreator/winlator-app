package com.winlator.text;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

public class GameTextOverlayView extends View {
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private GameTextConfig.Mode mode = GameTextConfig.Mode.OFF;
    private List<GameTextFrameProcessor.Item> items = Collections.emptyList();
    private String subtitle = "";

    public GameTextOverlayView(Context context) {
        this(context, null);
    }

    public GameTextOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(false);
        setFocusable(false);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setMode(GameTextConfig.Mode mode) {
        this.mode = mode == null ? GameTextConfig.Mode.OFF : mode;
        invalidate();
    }

    public void setFrame(GameTextFrameProcessor.Frame frame) {
        if (frame == null) return;
        items = frame.items;
        subtitle = frame.subtitle;
        invalidate();
    }

    public void clear() {
        items = Collections.emptyList();
        subtitle = "";
        invalidate();
    }

    public String getSubtitle() {
        return subtitle;
    }

    public int getItemCount() {
        return items.size();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mode == GameTextConfig.Mode.SUBTITLE) drawSubtitle(canvas);
        else if (mode == GameTextConfig.Mode.REPLACE) drawReplacementBoxes(canvas);
    }

    private void drawSubtitle(Canvas canvas) {
        if (subtitle.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        float horizontalMargin = 24 * density;
        float bottomMargin = 22 * density;
        float padding = 12 * density;
        int maxWidth = Math.max(1, (int)(getWidth() - horizontalMargin * 2 - padding * 2));

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(20 * density);
        StaticLayout layout = createLayout(subtitle, textPaint, maxWidth, Layout.Alignment.ALIGN_CENTER);
        float boxHeight = layout.getHeight() + padding * 2;
        float top = getHeight() - bottomMargin - boxHeight;
        RectF box = new RectF(horizontalMargin, top, getWidth() - horizontalMargin, getHeight() - bottomMargin);

        backgroundPaint.setColor(0xD9000000);
        canvas.drawRoundRect(box, 10 * density, 10 * density, backgroundPaint);
        canvas.save();
        canvas.translate(box.left + padding, box.top + padding);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawReplacementBoxes(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        for (GameTextFrameProcessor.Item item : items) {
            RectF box = new RectF(
                    item.normalizedBounds.left * getWidth(),
                    item.normalizedBounds.top * getHeight(),
                    item.normalizedBounds.right * getWidth(),
                    item.normalizedBounds.bottom * getHeight()
            );
            float padding = Math.max(3 * density, box.height() * 0.12f);
            box.inset(-padding, -padding);
            box.intersect(0, 0, getWidth(), getHeight());

            backgroundPaint.setColor(withOpaqueAlpha(item.backgroundColor));
            canvas.drawRoundRect(box, 4 * density, 4 * density, backgroundPaint);

            textPaint.setColor(item.foregroundColor);
            textPaint.setTextSize(Math.max(12 * density, Math.min(28 * density, box.height() * 0.55f)));
            int textWidth = Math.max(1, (int)(box.width() - padding * 2));
            StaticLayout layout = createLayout(item.displayText, textPaint, textWidth, Layout.Alignment.ALIGN_CENTER);
            canvas.save();
            float textTop = box.top + Math.max(padding, (box.height() - layout.getHeight()) * 0.5f);
            canvas.translate(box.left + padding, textTop);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private static StaticLayout createLayout(String text, TextPaint paint, int width, Layout.Alignment alignment) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(0, 1.0f)
                .build();
    }

    private static int withOpaqueAlpha(int color) {
        return Color.argb(235, Color.red(color), Color.green(color), Color.blue(color));
    }
}
