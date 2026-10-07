package com.winlator.text;

import java.util.Arrays;
import java.util.List;

final class OcrScriptSelector {
    private static final List<GameTextConfig.Script> RETRY_ORDER = Arrays.asList(
            GameTextConfig.Script.LATIN,
            GameTextConfig.Script.JAPANESE,
            GameTextConfig.Script.CHINESE,
            GameTextConfig.Script.KOREAN,
            GameTextConfig.Script.DEVANAGARI
    );

    private OcrScriptSelector() {
    }

    static GameTextConfig.Script forLanguage(
            String languageTag,
            GameTextConfig.Script fallback
    ) {
        String language = languageTag == null
                ? ""
                : languageTag.toLowerCase(java.util.Locale.ROOT);
        if (language.startsWith("ja")) return GameTextConfig.Script.JAPANESE;
        if (language.startsWith("zh")) return GameTextConfig.Script.CHINESE;
        if (language.startsWith("ko")) return GameTextConfig.Script.KOREAN;
        if (language.startsWith("hi") ||
                language.startsWith("mr") ||
                language.startsWith("ne")) {
            return GameTextConfig.Script.DEVANAGARI;
        }
        return fallback != null ? fallback : GameTextConfig.Script.LATIN;
    }

    static GameTextConfig.Script detectFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        boolean cjk = false;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            if (block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA ||
                    block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) {
                return GameTextConfig.Script.JAPANESE;
            }
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HANGUL_JAMO ||
                    block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return GameTextConfig.Script.KOREAN;
            }
            if (block == Character.UnicodeBlock.DEVANAGARI ||
                    block == Character.UnicodeBlock.DEVANAGARI_EXTENDED) {
                return GameTextConfig.Script.DEVANAGARI;
            }
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                cjk = true;
            }
        }
        return cjk ? GameTextConfig.Script.CHINESE : null;
    }

    static GameTextConfig.Script next(GameTextConfig.Script current) {
        int index = RETRY_ORDER.indexOf(current);
        return RETRY_ORDER.get((index + 1) % RETRY_ORDER.size());
    }

    static int recognitionScore(List<GameTextFrameProcessor.Detection> detections) {
        int score = 0;
        if (detections == null) return score;
        for (GameTextFrameProcessor.Detection detection : detections) {
            for (int index = 0; index < detection.text.length(); index++) {
                if (Character.isLetterOrDigit(detection.text.charAt(index))) score++;
            }
        }
        return score;
    }
}
