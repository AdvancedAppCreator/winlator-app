package com.winlator.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class GameTextLanguage {
    public static final String AUTO = "auto";
    public static final String ORIGINAL = "original";

    private static final String[] INPUT_LANGUAGE_TAGS = {
            "af", "ca", "cs", "cy", "da", "de", "en", "eo", "es", "et",
            "fi", "fr", "ga", "gl", "hi", "hr", "ht", "hu", "id", "is",
            "it", "ja", "ko", "lt", "lv", "mr", "ms", "mt", "nl", "no",
            "pl", "pt", "ro", "sk", "sl", "sq", "sv", "sw", "tl", "tr",
            "vi", "zh"
    };
    private static final String[] OUTPUT_LANGUAGE_TAGS = {
            "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de",
            "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "gl",
            "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
            "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl",
            "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw",
            "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh"
    };

    public static final class Option {
        public final String tag;
        public final String label;

        private Option(String tag, String label) {
            this.tag = tag;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private GameTextLanguage() {
    }

    public static List<Option> sourceOptions() {
        ArrayList<Option> options = new ArrayList<>();
        options.add(new Option(AUTO, "Auto detect (offline)"));
        addLanguages(options, INPUT_LANGUAGE_TAGS);
        return Collections.unmodifiableList(options);
    }

    public static List<Option> targetOptions() {
        ArrayList<Option> options = new ArrayList<>();
        options.add(new Option(ORIGINAL, "Original text (no translation)"));
        addLanguages(options, OUTPUT_LANGUAGE_TAGS);
        return Collections.unmodifiableList(options);
    }

    public static String normalizeSource(String tag) {
        if (AUTO.equals(tag)) return AUTO;
        return contains(INPUT_LANGUAGE_TAGS, tag) ? tag : AUTO;
    }

    public static String normalizeTarget(String tag) {
        if (ORIGINAL.equals(tag)) return ORIGINAL;
        return contains(OUTPUT_LANGUAGE_TAGS, tag) ? tag : ORIGINAL;
    }

    public static int findOption(List<Option> options, String tag) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).tag.equals(tag)) return i;
        }
        return 0;
    }

    public static GameTextConfig.Script recommendedScript(String tag) {
        if ("zh".equals(tag)) return GameTextConfig.Script.CHINESE;
        if ("hi".equals(tag) || "mr".equals(tag)) return GameTextConfig.Script.DEVANAGARI;
        if ("ja".equals(tag)) return GameTextConfig.Script.JAPANESE;
        if ("ko".equals(tag)) return GameTextConfig.Script.KOREAN;
        return GameTextConfig.Script.LATIN;
    }

    private static void addLanguages(List<Option> options, String[] tags) {
        for (String tag : tags) {
            Locale locale = Locale.forLanguageTag(tag);
            String label = locale.getDisplayLanguage(Locale.ENGLISH);
            options.add(new Option(tag, label.isEmpty() ? tag : label));
        }
    }

    private static boolean contains(String[] values, String value) {
        if (value == null) return false;
        for (String candidate : values) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }
}
