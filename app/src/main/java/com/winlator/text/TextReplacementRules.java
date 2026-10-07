package com.winlator.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class TextReplacementRules {
    private static final class Rule {
        final String source;
        final String replacement;

        Rule(String source, String replacement) {
            this.source = source;
            this.replacement = replacement;
        }
    }

    private final List<Rule> rules;
    private final String version;

    public TextReplacementRules(String definition) {
        rules = parse(definition);
        version = sha256(definition != null ? definition : "");
    }

    public String apply(String source) {
        if (source == null || source.isEmpty()) return "";
        String result = source;
        for (Rule rule : rules) {
            result = replaceLiteralIgnoreCase(result, rule.source, rule.replacement);
        }
        return result;
    }

    public int size() {
        return rules.size();
    }

    public String version() {
        return version;
    }

    private static List<Rule> parse(String definition) {
        ArrayList<Rule> result = new ArrayList<>();
        if (definition == null) return result;

        for (String rawLine : definition.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf("=>");
            if (separator <= 0) continue;
            String source = line.substring(0, separator).trim();
            String replacement = line.substring(separator + 2).trim();
            if (!source.isEmpty()) result.add(new Rule(source, replacement));
        }
        result.sort(Comparator.comparingInt((Rule rule) -> rule.source.length()).reversed());
        return result;
    }

    private static String replaceLiteralIgnoreCase(String text, String search, String replacement) {
        int start = 0;
        StringBuilder result = null;
        int lastStart = text.length() - search.length();
        for (int index = 0; index <= lastStart; ) {
            if (!text.regionMatches(true, index, search, 0, search.length())) {
                index++;
                continue;
            }
            if (result == null) result = new StringBuilder(text.length());
            result.append(text, start, index);
            result.append(replacement);
            index += search.length();
            start = index;
        }
        if (result == null) return text;
        result.append(text, start, text.length());
        return result.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }
}
