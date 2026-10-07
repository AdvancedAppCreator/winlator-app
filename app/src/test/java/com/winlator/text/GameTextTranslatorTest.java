package com.winlator.text;

import static org.junit.Assert.assertEquals;

import android.graphics.Color;
import android.graphics.RectF;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class GameTextTranslatorTest {
    @Test
    public void appliesTranslatedTextAndReplacementRulesWithoutChangingGeometry() {
        RectF bounds = new RectF(0.1f, 0.2f, 0.8f, 0.3f);
        GameTextFrameProcessor.Frame source = new GameTextFrameProcessor.Frame(
                Collections.singletonList(new GameTextFrameProcessor.Item(
                        "The door is locked.",
                        "The door is locked.",
                        bounds,
                        Color.BLACK,
                        Color.WHITE
                )),
                "The door is locked."
        );

        GameTextFrameProcessor.Frame translated = GameTextTranslator.applyTranslations(
                source,
                Collections.singletonList("La puerta esta cerrada."),
                new TextReplacementRules("puerta => entrada")
        );

        assertEquals("La entrada esta cerrada.", translated.subtitle);
        assertEquals(bounds.left, translated.items.get(0).normalizedBounds.left, 0.0001f);
        assertEquals(bounds.top, translated.items.get(0).normalizedBounds.top, 0.0001f);
        assertEquals(bounds.right, translated.items.get(0).normalizedBounds.right, 0.0001f);
        assertEquals(bounds.bottom, translated.items.get(0).normalizedBounds.bottom, 0.0001f);
        assertEquals("The door is locked.", translated.items.get(0).sourceText);
    }

    @Test
    public void buildsSubtitleInOriginalItemOrder() {
        GameTextFrameProcessor.Item first = item("one");
        GameTextFrameProcessor.Item second = item("two");
        GameTextFrameProcessor.Frame translated = GameTextTranslator.applyTranslations(
                new GameTextFrameProcessor.Frame(Arrays.asList(first, second), "one\ntwo"),
                Arrays.asList("uno", "dos"),
                new TextReplacementRules("")
        );

        assertEquals("uno\ndos", translated.subtitle);
    }

    private static GameTextFrameProcessor.Item item(String text) {
        return new GameTextFrameProcessor.Item(
                text,
                text,
                new RectF(0, 0, 1, 1),
                Color.BLACK,
                Color.WHITE
        );
    }
}
