package com.winlator.renderer;

import static org.junit.Assert.assertEquals;

import com.winlator.math.XForm;

import org.junit.Test;

public class ViewTransformationTest {
    @Test
    public void fullscreenUsesIndependentStretchScales() {
        float[] transform = XForm.getInstance();
        ViewTransformation.updateInputTransform(transform, true, 2400, 1080, 1280, 720);

        float[] center = XForm.transformPoint(transform, 1200, 540);
        assertEquals(640, center[0], 0.001f);
        assertEquals(360, center[1], 0.001f);
    }

    @Test
    public void fittedViewRemovesLetterboxOffset() {
        float[] transform = XForm.getInstance();
        ViewTransformation.updateInputTransform(transform, false, 2400, 1080, 1280, 720);

        float[] leftEdge = XForm.transformPoint(transform, 240, 0);
        float[] rightEdge = XForm.transformPoint(transform, 2160, 1080);
        assertEquals(0, leftEdge[0], 0.001f);
        assertEquals(0, leftEdge[1], 0.001f);
        assertEquals(1280, rightEdge[0], 0.001f);
        assertEquals(720, rightEdge[1], 0.001f);
    }
}
