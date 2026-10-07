package com.winlator.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class StrongOcrImageProcessorTest {
    @Test
    public void cropsUpscalesAndMapsDetectionsBackToCapturedPixels() {
        Bitmap frame = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        StrongOcrImageProcessor processor = StrongOcrImageProcessor.prepare(
                frame,
                new RectF(0.25f, 0.25f, 0.75f, 0.75f)
        );

        assertEquals(new Rect(25, 20, 75, 60), processor.captureBounds);
        Bitmap variant = processor.createColorVariant();
        assertEquals(125, variant.getWidth());
        assertEquals(100, variant.getHeight());

        List<GameTextFrameProcessor.Detection> mapped = processor.mapDetections(
                Collections.singletonList(new GameTextFrameProcessor.Detection(
                        "Mapped",
                        new Rect(25, 25, 75, 75)
                ))
        );
        assertEquals(new Rect(10, 10, 30, 30), mapped.get(0).bounds);

        variant.recycle();
        processor.close();
        assertFalse(frame.isRecycled());
        frame.recycle();
    }

    @Test
    public void choosesPassWithMoreRecognizedContent() {
        List<GameTextFrameProcessor.Detection> shortResult = Collections.singletonList(
                new GameTextFrameProcessor.Detection("Hi", new Rect(0, 0, 10, 10))
        );
        List<GameTextFrameProcessor.Detection> strongerResult = Arrays.asList(
                new GameTextFrameProcessor.Detection("Hello", new Rect(0, 0, 20, 10)),
                new GameTextFrameProcessor.Detection("world", new Rect(0, 12, 20, 22))
        );

        assertSame(
                strongerResult,
                StrongOcrImageProcessor.chooseBetter(shortResult, strongerResult)
        );
    }

    @Test
    public void mergesComplementaryPassesWithoutDuplicatingSameLine() {
        List<GameTextFrameProcessor.Detection> first = Arrays.asList(
                new GameTextFrameProcessor.Detection("First", new Rect(0, 0, 50, 20)),
                new GameTextFrameProcessor.Detection("Second", new Rect(0, 30, 60, 50))
        );
        List<GameTextFrameProcessor.Detection> second = Arrays.asList(
                new GameTextFrameProcessor.Detection("Second improved", new Rect(1, 30, 61, 50)),
                new GameTextFrameProcessor.Detection("Third", new Rect(0, 60, 50, 80))
        );

        List<GameTextFrameProcessor.Detection> merged =
                StrongOcrImageProcessor.mergeDetections(first, second);

        assertEquals(3, merged.size());
        assertEquals("Second improved", merged.get(1).text);
        assertEquals("Third", merged.get(2).text);
    }

    @Test
    public void brightTextVariantProducesDarkTextOnLightBackground() {
        assertEquals(
                Color.BLACK,
                StrongOcrImageProcessor.isolateBrightText(Color.rgb(245, 245, 245))
        );
        assertEquals(
                Color.WHITE,
                StrongOcrImageProcessor.isolateBrightText(Color.rgb(80, 50, 40))
        );
    }

    @Test
    public void outlinedTextVariantFillsGlyphAndRemovesEdgeConnectedNoise() {
        Bitmap frame = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888);
        frame.eraseColor(Color.WHITE);
        int outline = Color.rgb(110, 60, 30);
        for (int x = 6; x <= 13; x++) {
            frame.setPixel(x, 3, outline);
            frame.setPixel(x, 8, outline);
        }
        for (int y = 3; y <= 8; y++) {
            frame.setPixel(6, y, outline);
            frame.setPixel(13, y, outline);
            frame.setPixel(0, y, outline);
        }

        Bitmap variant = frame.copy(Bitmap.Config.ARGB_8888, true);
        StrongOcrImageProcessor.reconstructWarmOutlinedText(variant);

        assertEquals(20, variant.getWidth());
        assertEquals(Color.BLACK, variant.getPixel(6, 3));
        assertEquals(Color.BLACK, variant.getPixel(9, 5));
        assertEquals(Color.WHITE, variant.getPixel(0, 5));
        assertEquals(Color.WHITE, variant.getPixel(2, 5));
        variant.recycle();
        frame.recycle();
    }

    @Test
    public void mapsDetectionsUsingActualVariantDimensions() {
        Bitmap frame = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        StrongOcrImageProcessor processor = StrongOcrImageProcessor.prepare(
                frame,
                new RectF(0.25f, 0.25f, 0.75f, 0.75f)
        );

        List<GameTextFrameProcessor.Detection> mapped = processor.mapDetections(
                Collections.singletonList(new GameTextFrameProcessor.Detection(
                        "Mapped",
                        new Rect(5, 5, 15, 15)
                )),
                50,
                40
        );

        assertEquals(new Rect(5, 5, 15, 15), mapped.get(0).bounds);
        processor.close();
        frame.recycle();
    }

    @Test
    public void fullFramePreparationKeepsSourceOwnershipWithCaller() {
        Bitmap frame = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        StrongOcrImageProcessor processor = StrongOcrImageProcessor.prepare(
                frame,
                new RectF(0, 0, 1, 1)
        );

        processor.close();

        assertFalse(frame.isRecycled());
        frame.recycle();
    }

    @Test
    public void tiledRegionsCoverSelectionWithOverlap() {
        List<RectF> tiles = StrongOcrImageProcessor.tileRegions(
                new RectF(0, 0, 1, 1),
                true
        );

        assertEquals(4, tiles.size());
        assertEquals(0.0f, tiles.get(0).left, 0.0001f);
        assertEquals(0.575f, tiles.get(0).right, 0.0001f);
        assertEquals(0.425f, tiles.get(1).left, 0.0001f);
        assertEquals(1.0f, tiles.get(3).bottom, 0.0001f);
    }

    @Test
    public void tiledDetectionsMapToAbsoluteSurfacePixels() {
        Bitmap frame = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        StrongOcrImageProcessor processor = StrongOcrImageProcessor.prepare(
                frame,
                new RectF(0.5f, 0.5f, 1.0f, 1.0f)
        );
        List<GameTextFrameProcessor.Detection> mapped =
                processor.mapDetectionsToSurface(Collections.singletonList(
                        new GameTextFrameProcessor.Detection(
                                "Tile",
                                new Rect(0, 0, 25, 20)
                        )
                ));

        assertEquals(new Rect(50, 40, 60, 48), mapped.get(0).bounds);
        processor.close();
        frame.recycle();
    }
}
