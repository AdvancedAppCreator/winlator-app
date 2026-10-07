package com.winlator.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class GameTextFrameProcessorTest {
    @Test
    public void mapsCaptureCoordinatesAndBuildsSubtitle() {
        GameTextFrameProcessor processor = new GameTextFrameProcessor();
        GameTextFrameProcessor.Frame frame = processor.process(
                Collections.singletonList(new GameTextFrameProcessor.Detection(
                        "The door is locked.",
                        new Rect(10, 20, 110, 60)
                )),
                new Rect(100, 200, 500, 500),
                1000,
                800,
                null,
                new TextReplacementRules("door => gate")
        );

        assertEquals("The gate is locked.", frame.subtitle);
        assertEquals(1, frame.items.size());
        assertEquals(0.11f, frame.items.get(0).normalizedBounds.left, 0.0001f);
        assertEquals(0.275f, frame.items.get(0).normalizedBounds.top, 0.0001f);
    }

    @Test
    public void suppressesDuplicateFramesAndClearsAfterTwoEmptyFrames() {
        GameTextFrameProcessor processor = new GameTextFrameProcessor();
        GameTextFrameProcessor.Detection detection = new GameTextFrameProcessor.Detection(
                "Repeated",
                new Rect(0, 0, 100, 30)
        );

        assertTrue(processor.process(
                Collections.singletonList(detection),
                new Rect(0, 0, 200, 100),
                200,
                100,
                null,
                new TextReplacementRules("")
        ) != null);
        assertNull(processor.process(
                Collections.singletonList(detection),
                new Rect(0, 0, 200, 100),
                200,
                100,
                null,
                new TextReplacementRules("")
        ));
        assertNull(processor.process(
                Collections.emptyList(),
                new Rect(0, 0, 200, 100),
                200,
                100,
                null,
                new TextReplacementRules("")
        ));
        assertEquals(0, processor.process(
                Collections.emptyList(),
                new Rect(0, 0, 200, 100),
                200,
                100,
                null,
                new TextReplacementRules("")
        ).items.size());
    }
}
