package com.winlator;

import static org.junit.Assert.assertEquals;

import android.widget.Button;

import com.winlator.text.GameTextOverlayView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TextCapabilitiesTestActivityTest {
    @Test
    public void deterministicPreviewExercisesReplacementAndSubtitleDisplay() {
        TextCapabilitiesTestActivity activity = Robolectric
                .buildActivity(TextCapabilitiesTestActivity.class)
                .setup()
                .get();
        Button preview = activity.findViewById(R.id.BTPreviewGameTextTest);
        GameTextOverlayView overlay = activity.findViewById(R.id.GameTextTestOverlay);

        preview.performClick();

        assertEquals("The passage is sealed.", overlay.getSubtitle());
        assertEquals(1, overlay.getItemCount());
    }
}
