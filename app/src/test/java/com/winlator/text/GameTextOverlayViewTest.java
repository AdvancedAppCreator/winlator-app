package com.winlator.text;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class GameTextOverlayViewTest {
    @Test
    public void acceptsSubtitleAndReplacementState() {
        Context context = ApplicationProvider.getApplicationContext();
        GameTextOverlayView overlay = new GameTextOverlayView(context);
        GameTextFrameProcessor.Item item = new GameTextFrameProcessor.Item(
                "Source",
                "Replacement",
                new RectF(0.1f, 0.2f, 0.5f, 0.3f),
                Color.BLACK,
                Color.WHITE
        );

        overlay.setMode(GameTextConfig.Mode.SUBTITLE);
        overlay.setFrame(new GameTextFrameProcessor.Frame(
                Collections.singletonList(item),
                "Replacement"
        ));

        assertEquals("Replacement", overlay.getSubtitle());
        assertEquals(1, overlay.getItemCount());
    }
}
