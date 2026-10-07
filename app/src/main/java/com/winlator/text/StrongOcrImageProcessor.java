package com.winlator.text;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class StrongOcrImageProcessor implements AutoCloseable {
    private static final float MAX_SCALE = 2.5f;
    private static final int MAX_DIMENSION = 3200;
    private static final float OUTLINED_NATIVE_SCALE = 0.40f;
    private static final float OUTLINED_ENHANCED_SCALE = 0.85f;
    private static final byte MASK_OUTLINE = 0;
    private static final byte MASK_BACKGROUND = 1;
    private static final byte MASK_EXTERIOR_BACKGROUND = 2;
    private static final byte MASK_EDGE_NOISE = 3;

    public final Rect captureBounds;

    private final Bitmap croppedBitmap;
    private final int targetWidth;
    private final int targetHeight;
    private final float scaleX;
    private final float scaleY;

    private StrongOcrImageProcessor(
            Bitmap croppedBitmap,
            Rect captureBounds,
            int targetWidth,
            int targetHeight
    ) {
        this.croppedBitmap = croppedBitmap;
        this.captureBounds = captureBounds;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        scaleX = targetWidth / (float)croppedBitmap.getWidth();
        scaleY = targetHeight / (float)croppedBitmap.getHeight();
    }

    public static StrongOcrImageProcessor prepare(Bitmap frame, RectF normalizedRegion) {
        if (frame == null || frame.isRecycled()) {
            throw new IllegalArgumentException("A valid captured frame is required");
        }

        RectF region = normalizedRegion != null
                ? new RectF(normalizedRegion)
                : new RectF(0.0f, 0.0f, 1.0f, 1.0f);
        sortAndClamp(region);

        int surfaceWidth = frame.getWidth();
        int surfaceHeight = frame.getHeight();
        int left = Math.max(0, Math.min(surfaceWidth - 1, Math.round(region.left * surfaceWidth)));
        int top = Math.max(0, Math.min(surfaceHeight - 1, Math.round(region.top * surfaceHeight)));
        int right = Math.max(left + 1, Math.min(surfaceWidth, Math.round(region.right * surfaceWidth)));
        int bottom = Math.max(top + 1, Math.min(surfaceHeight, Math.round(region.bottom * surfaceHeight)));
        Rect captureBounds = new Rect(left, top, right, bottom);

        Bitmap crop = Bitmap.createBitmap(frame, left, top, captureBounds.width(), captureBounds.height());
        if (crop == frame) crop = frame.copy(Bitmap.Config.ARGB_8888, false);

        float dimensionScale = Math.min(
                MAX_DIMENSION / (float)crop.getWidth(),
                MAX_DIMENSION / (float)crop.getHeight()
        );
        float scale = Math.max(1.0f, Math.min(MAX_SCALE, dimensionScale));
        int targetWidth = Math.max(crop.getWidth(), Math.round(crop.getWidth() * scale));
        int targetHeight = Math.max(crop.getHeight(), Math.round(crop.getHeight() * scale));
        return new StrongOcrImageProcessor(
                crop,
                captureBounds,
                targetWidth,
                targetHeight
        );
    }

    public Bitmap createColorVariant() {
        return renderVariant(null);
    }

    public Bitmap createHighContrastVariant() {
        ColorMatrix grayscale = new ColorMatrix();
        grayscale.setSaturation(0.0f);
        float contrast = 1.45f;
        float translate = (1.0f - contrast) * 128.0f;
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, translate,
                0, contrast, 0, 0, translate,
                0, 0, contrast, 0, translate,
                0, 0, 0, 1, 0
        });
        grayscale.postConcat(contrastMatrix);
        return renderVariant(new ColorMatrixColorFilter(grayscale));
    }

    public Bitmap createBrightTextVariant() {
        Bitmap variant = renderVariant(null);
        int[] pixels = new int[variant.getWidth()];
        for (int y = 0; y < variant.getHeight(); y++) {
            variant.getPixels(pixels, 0, pixels.length, 0, y, pixels.length, 1);
            for (int x = 0; x < pixels.length; x++) {
                pixels[x] = isolateBrightText(pixels[x]);
            }
            variant.setPixels(pixels, 0, pixels.length, 0, y, pixels.length, 1);
        }
        return variant;
    }

    public Bitmap createOutlinedTextVariant(boolean enhanced) {
        float variantScale = enhanced ? OUTLINED_ENHANCED_SCALE : OUTLINED_NATIVE_SCALE;
        int width = Math.max(
                croppedBitmap.getWidth(),
                Math.round(targetWidth * variantScale)
        );
        int height = Math.max(
                croppedBitmap.getHeight(),
                Math.round(targetHeight * variantScale)
        );
        Bitmap variant = renderVariant(null, width, height);
        reconstructWarmOutlinedText(variant);
        return variant;
    }

    static int isolateBrightText(int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        int luminance = luma(color);
        int chroma = Math.max(red, Math.max(green, blue))
                - Math.min(red, Math.min(green, blue));
        return luminance >= 175 && chroma <= 90 ? Color.BLACK : Color.WHITE;
    }

    static boolean isWarmOutline(int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        int luminance = luma(color);
        return red >= green + 24
                && green >= blue + 10
                && luminance <= 145;
    }

    public List<GameTextFrameProcessor.Detection> mapDetections(
            List<GameTextFrameProcessor.Detection> detections
    ) {
        return mapDetections(detections, scaleX, scaleY);
    }

    public List<GameTextFrameProcessor.Detection> mapDetections(
            List<GameTextFrameProcessor.Detection> detections,
            int imageWidth,
            int imageHeight
    ) {
        return mapDetections(
                detections,
                imageWidth / (float)croppedBitmap.getWidth(),
                imageHeight / (float)croppedBitmap.getHeight()
        );
    }

    private List<GameTextFrameProcessor.Detection> mapDetections(
            List<GameTextFrameProcessor.Detection> detections,
            float detectionScaleX,
            float detectionScaleY
    ) {
        if (detections == null || detections.isEmpty()) return Collections.emptyList();

        ArrayList<GameTextFrameProcessor.Detection> mapped = new ArrayList<>();
        for (GameTextFrameProcessor.Detection detection : detections) {
            Rect bounds = detection.bounds;
            Rect mappedBounds = new Rect(
                    clamp(Math.round(bounds.left / detectionScaleX), 0, croppedBitmap.getWidth()),
                    clamp(Math.round(bounds.top / detectionScaleY), 0, croppedBitmap.getHeight()),
                    clamp(Math.round(bounds.right / detectionScaleX), 0, croppedBitmap.getWidth()),
                    clamp(Math.round(bounds.bottom / detectionScaleY), 0, croppedBitmap.getHeight())
            );
            if (mappedBounds.width() > 0 && mappedBounds.height() > 0) {
                mapped.add(new GameTextFrameProcessor.Detection(detection.text, mappedBounds));
            }
        }
        return mapped;
    }

    public List<GameTextFrameProcessor.Detection> mapDetectionsToSurface(
            List<GameTextFrameProcessor.Detection> detections
    ) {
        ArrayList<GameTextFrameProcessor.Detection> result = new ArrayList<>();
        for (GameTextFrameProcessor.Detection detection : mapDetections(detections)) {
            Rect bounds = new Rect(detection.bounds);
            bounds.offset(captureBounds.left, captureBounds.top);
            result.add(new GameTextFrameProcessor.Detection(detection.text, bounds));
        }
        return result;
    }

    public List<GameTextFrameProcessor.Detection> mapDetectionsToSurface(
            List<GameTextFrameProcessor.Detection> detections,
            int imageWidth,
            int imageHeight
    ) {
        ArrayList<GameTextFrameProcessor.Detection> result = new ArrayList<>();
        for (GameTextFrameProcessor.Detection detection :
                mapDetections(detections, imageWidth, imageHeight)) {
            Rect bounds = new Rect(detection.bounds);
            bounds.offset(captureBounds.left, captureBounds.top);
            result.add(new GameTextFrameProcessor.Detection(detection.text, bounds));
        }
        return result;
    }

    static List<RectF> tileRegions(RectF selectedRegion, boolean tiled) {
        RectF region = selectedRegion != null
                ? new RectF(selectedRegion)
                : new RectF(0, 0, 1, 1);
        sortAndClamp(region);
        if (!tiled) return Collections.singletonList(region);

        int columns = region.width() >= 0.35f ? 2 : 1;
        int rows = region.height() >= 0.35f ? 2 : 1;
        if (columns == 1 && rows == 1) return Collections.singletonList(region);
        float cellWidth = region.width() / columns;
        float cellHeight = region.height() / rows;
        float overlapX = columns > 1 ? cellWidth * 0.15f : 0;
        float overlapY = rows > 1 ? cellHeight * 0.15f : 0;
        ArrayList<RectF> result = new ArrayList<>(columns * rows);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                result.add(new RectF(
                        Math.max(region.left, region.left + column * cellWidth - overlapX),
                        Math.max(region.top, region.top + row * cellHeight - overlapY),
                        Math.min(region.right, region.left + (column + 1) * cellWidth + overlapX),
                        Math.min(region.bottom, region.top + (row + 1) * cellHeight + overlapY)
                ));
            }
        }
        return result;
    }

    public static List<GameTextFrameProcessor.Detection> chooseBetter(
            List<GameTextFrameProcessor.Detection> first,
            List<GameTextFrameProcessor.Detection> second
    ) {
        List<GameTextFrameProcessor.Detection> safeFirst =
                first != null ? first : Collections.emptyList();
        List<GameTextFrameProcessor.Detection> safeSecond =
                second != null ? second : Collections.emptyList();
        return score(safeSecond) > score(safeFirst) ? safeSecond : safeFirst;
    }

    public static List<GameTextFrameProcessor.Detection> mergeDetections(
            List<GameTextFrameProcessor.Detection> first,
            List<GameTextFrameProcessor.Detection> second
    ) {
        ArrayList<GameTextFrameProcessor.Detection> merged = new ArrayList<>();
        if (first != null) merged.addAll(first);
        if (second == null) return merged;

        for (GameTextFrameProcessor.Detection candidate : second) {
            int duplicateIndex = findDuplicate(merged, candidate);
            if (duplicateIndex < 0) {
                merged.add(candidate);
            }
            else if (textScore(candidate.text) > textScore(merged.get(duplicateIndex).text)) {
                merged.set(duplicateIndex, candidate);
            }
        }
        return merged;
    }

    @Override
    public void close() {
        if (!croppedBitmap.isRecycled()) croppedBitmap.recycle();
    }

    private Bitmap renderVariant(ColorMatrixColorFilter colorFilter) {
        return renderVariant(colorFilter, targetWidth, targetHeight);
    }

    private Bitmap renderVariant(
            ColorMatrixColorFilter colorFilter,
            int width,
            int height
    ) {
        Bitmap variant = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(colorFilter);
        Canvas canvas = new Canvas(variant);
        canvas.drawBitmap(
                croppedBitmap,
                null,
                new Rect(0, 0, width, height),
                paint
        );
        return variant;
    }

    static void reconstructWarmOutlinedText(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        byte[] mask = new byte[width * height];
        int[] row = new int[width];

        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                mask[rowOffset + x] = isWarmOutline(row[x])
                        ? MASK_OUTLINE
                        : MASK_BACKGROUND;
            }
        }

        markConnectedFromEdges(
                mask,
                width,
                height,
                false,
                MASK_EXTERIOR_BACKGROUND
        );
        markConnectedFromEdges(mask, width, height, true, MASK_EDGE_NOISE);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                byte value = mask[rowOffset + x];
                row[x] = value == MASK_OUTLINE || value == MASK_BACKGROUND
                        ? Color.BLACK
                        : Color.WHITE;
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
    }

    private static void markConnectedFromEdges(
            byte[] mask,
            int width,
            int height,
            boolean markInk,
            byte markedValue
    ) {
        IntStack seeds = new IntStack(Math.max(64, (width + height) * 2));
        for (int x = 0; x < width; x++) {
            pushIfMatching(seeds, mask, x, markInk);
            pushIfMatching(seeds, mask, (height - 1) * width + x, markInk);
        }
        for (int y = 1; y < height - 1; y++) {
            pushIfMatching(seeds, mask, y * width, markInk);
            pushIfMatching(seeds, mask, y * width + width - 1, markInk);
        }

        while (!seeds.isEmpty()) {
            int seed = seeds.pop();
            if (!matches(mask[seed], markInk)) continue;

            int y = seed / width;
            int left = seed % width;
            int right = left;
            int rowOffset = y * width;
            while (left > 0 && matches(mask[rowOffset + left - 1], markInk)) left--;
            while (right + 1 < width &&
                    matches(mask[rowOffset + right + 1], markInk)) {
                right++;
            }
            Arrays.fill(mask, rowOffset + left, rowOffset + right + 1, markedValue);

            if (y > 0) {
                pushRuns(seeds, mask, (y - 1) * width, left, right, markInk);
            }
            if (y + 1 < height) {
                pushRuns(seeds, mask, (y + 1) * width, left, right, markInk);
            }
        }
    }

    private static void pushRuns(
            IntStack seeds,
            byte[] mask,
            int rowOffset,
            int left,
            int right,
            boolean ink
    ) {
        int x = left;
        while (x <= right) {
            while (x <= right && !matches(mask[rowOffset + x], ink)) x++;
            if (x > right) return;
            seeds.push(rowOffset + x);
            while (x <= right && matches(mask[rowOffset + x], ink)) x++;
        }
    }

    private static void pushIfMatching(
            IntStack seeds,
            byte[] mask,
            int index,
            boolean ink
    ) {
        if (matches(mask[index], ink)) seeds.push(index);
    }

    private static boolean matches(byte value, boolean ink) {
        return ink
                ? value == MASK_OUTLINE || value == MASK_BACKGROUND
                : value == MASK_BACKGROUND;
    }

    private static final class IntStack {
        private int[] values;
        private int size;

        IntStack(int initialCapacity) {
            values = new int[initialCapacity];
        }

        void push(int value) {
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = value;
        }

        int pop() {
            return values[--size];
        }

        boolean isEmpty() {
            return size == 0;
        }
    }

    private static int score(List<GameTextFrameProcessor.Detection> detections) {
        int score = detections.size() * 4;
        for (GameTextFrameProcessor.Detection detection : detections) score += alnumCount(detection.text);
        return score;
    }

    private static int findDuplicate(
            List<GameTextFrameProcessor.Detection> detections,
            GameTextFrameProcessor.Detection candidate
    ) {
        for (int index = 0; index < detections.size(); index++) {
            Rect existing = detections.get(index).bounds;
            int intersectionWidth = Math.max(
                    0,
                    Math.min(existing.right, candidate.bounds.right)
                            - Math.max(existing.left, candidate.bounds.left)
            );
            int intersectionHeight = Math.max(
                    0,
                    Math.min(existing.bottom, candidate.bounds.bottom)
                            - Math.max(existing.top, candidate.bounds.top)
            );
            int smallerArea = Math.min(
                    existing.width() * existing.height(),
                    candidate.bounds.width() * candidate.bounds.height()
            );
            if (smallerArea > 0 &&
                    intersectionWidth * intersectionHeight >= smallerArea * 0.55f) {
                return index;
            }
        }
        return -1;
    }

    private static int textScore(String text) {
        return alnumCount(text);
    }

    private static int alnumCount(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (Character.isLetterOrDigit(text.charAt(index))) count++;
        }
        return count;
    }

    private static int luma(int color) {
        return (77 * Color.red(color) + 150 * Color.green(color) + 29 * Color.blue(color)) >> 8;
    }

    private static void sortAndClamp(RectF region) {
        region.sort();
        region.left = clamp(region.left);
        region.top = clamp(region.top);
        region.right = clamp(region.right);
        region.bottom = clamp(region.bottom);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
