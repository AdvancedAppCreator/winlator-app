package com.winlator.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Collections;

public class OcrScriptSelectorTest {
    @Test
    public void sourceLanguageProvidesLazyInitialScript() {
        assertEquals(
                GameTextConfig.Script.JAPANESE,
                OcrScriptSelector.forLanguage("ja-JP", GameTextConfig.Script.LATIN)
        );
        assertEquals(
                GameTextConfig.Script.DEVANAGARI,
                OcrScriptSelector.forLanguage("hi", GameTextConfig.Script.LATIN)
        );
        assertEquals(
                GameTextConfig.Script.KOREAN,
                OcrScriptSelector.forLanguage("auto", GameTextConfig.Script.KOREAN)
        );
    }

    @Test
    public void recognizedUnicodeIdentifiesNonLatinScript() {
        assertEquals(
                GameTextConfig.Script.JAPANESE,
                OcrScriptSelector.detectFromText("設定を開く")
        );
        assertEquals(
                GameTextConfig.Script.KOREAN,
                OcrScriptSelector.detectFromText("설정")
        );
        assertEquals(
                GameTextConfig.Script.CHINESE,
                OcrScriptSelector.detectFromText("设置")
        );
        assertNull(OcrScriptSelector.detectFromText("Settings"));
    }

    @Test
    public void poorRecognitionRotatesThroughEveryRecognizer() {
        GameTextConfig.Script current = GameTextConfig.Script.LATIN;
        for (int count = 0; count < 5; count++) {
            current = OcrScriptSelector.next(current);
        }
        assertEquals(GameTextConfig.Script.LATIN, current);
    }

    @Test
    public void recognitionScoreCountsUsefulCharacters() {
        assertEquals(
                3,
                OcrScriptSelector.recognitionScore(Collections.singletonList(
                        new GameTextFrameProcessor.Detection(
                                "A 12!",
                                new Rect(0, 0, 10, 10)
                        )
                ))
        );
    }
}
