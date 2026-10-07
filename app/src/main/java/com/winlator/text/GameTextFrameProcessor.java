package com.winlator.text;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class GameTextFrameProcessor {
    public static final class Detection {
        public final String text;
        public final Rect bounds;

        public Detection(String text, Rect bounds) {
            this.text = text == null ? "" : text.trim();
            this.bounds = new Rect(bounds);
        }
    }

    public static final class Item {
        public final String sourceText;
        public final String displayText;
        public final RectF normalizedBounds;
        public final int backgroundColor;
        public final int foregroundColor;

        public Item(String sourceText, String displayText, RectF normalizedBounds, int backgroundColor, int foregroundColor) {
            this.sourceText = sourceText;
            this.displayText = displayText;
            this.normalizedBounds = new RectF(normalizedBounds);
            this.backgroundColor = backgroundColor;
            this.foregroundColor = foregroundColor;
        }
    }

    public static final class Frame {
        public final List<Item> items;
        public final String subtitle;

        public Frame(List<Item> items, String subtitle) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.subtitle = subtitle;
        }
    }

    private String previousFingerprint = "";
    private int consecutiveEmptyFrames;

    public Frame process(
            List<Detection> detections,
            Rect captureBounds,
            int surfaceWidth,
            int surfaceHeight,
            Bitmap capturedBitmap,
            TextReplacementRules replacementRules
    ) {
        ArrayList<Detection> ordered = new ArrayList<>();
        for (Detection detection : detections) {
            if (!detection.text.isEmpty() && detection.bounds.width() > 0 && detection.bounds.height() > 0) {
                ordered.add(detection);
            }
        }
        ordered.sort(Comparator
                .comparingInt((Detection detection) -> detection.bounds.top)
                .thenComparingInt(detection -> detection.bounds.left));

        if (ordered.isEmpty()) {
            consecutiveEmptyFrames++;
            if (consecutiveEmptyFrames < 2 || previousFingerprint.isEmpty()) return null;
            previousFingerprint = "";
            return new Frame(Collections.emptyList(), "");
        }
        consecutiveEmptyFrames = 0;

        StringBuilder fingerprint = new StringBuilder();
        StringBuilder subtitle = new StringBuilder();
        ArrayList<Item> items = new ArrayList<>();
        for (Detection detection : ordered) {
            String displayText = replacementRules.apply(detection.text);
            if (displayText.isEmpty()) continue;

            if (fingerprint.length() > 0) fingerprint.append('\n');
            fingerprint.append(detection.text);
            if (subtitle.length() > 0) subtitle.append('\n');
            subtitle.append(displayText);

            RectF normalizedBounds = new RectF(
                    (captureBounds.left + detection.bounds.left) / (float)surfaceWidth,
                    (captureBounds.top + detection.bounds.top) / (float)surfaceHeight,
                    (captureBounds.left + detection.bounds.right) / (float)surfaceWidth,
                    (captureBounds.top + detection.bounds.bottom) / (float)surfaceHeight
            );
            int background = sampleBackground(capturedBitmap, detection.bounds);
            int foreground = ColorUtils.calculateLuminance(background) < 0.5 ? Color.WHITE : Color.BLACK;
            items.add(new Item(detection.text, displayText, normalizedBounds, background, foreground));
        }

        String currentFingerprint = fingerprint.toString();
        if (currentFingerprint.equals(previousFingerprint)) return null;
        previousFingerprint = currentFingerprint;
        return new Frame(items, subtitle.toString());
    }

    public void reset() {
        previousFingerprint = "";
        consecutiveEmptyFrames = 0;
    }

    private static int sampleBackground(Bitmap bitmap, Rect bounds) {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {
            return Color.rgb(20, 20, 20);
        }

        int left = Math.max(0, bounds.left);
        int top = Math.max(0, bounds.top);
        int right = Math.min(bitmap.getWidth(), bounds.right);
        int bottom = Math.min(bitmap.getHeight(), bounds.bottom);
        if (left >= right || top >= bottom) return Color.rgb(20, 20, 20);

        long red = 0;
        long green = 0;
        long blue = 0;
        int samples = 0;
        int stepX = Math.max(1, (right - left) / 12);
        int stepY = Math.max(1, (bottom - top) / 6);
        for (int y = top; y < bottom; y += stepY) {
            for (int x = left; x < right; x += stepX) {
                int color = bitmap.getPixel(x, y);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                samples++;
            }
        }
        if (samples == 0) return Color.rgb(20, 20, 20);
        return Color.rgb((int)(red / samples), (int)(green / samples), (int)(blue / samples));
    }
}
