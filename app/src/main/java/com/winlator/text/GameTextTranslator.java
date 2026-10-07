package com.winlator.text;

import android.content.Context;
import android.os.SystemClock;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class GameTextTranslator implements AutoCloseable {
    private static final DownloadConditions MODEL_DOWNLOAD_CONDITIONS =
            new DownloadConditions.Builder().requireWifi().build();
    private static final long MODEL_RETRY_MILLIS = 30000;
    private static final long LIVE_MODEL_WAIT_SECONDS = 5;
    private static final long PREPARE_MODEL_WAIT_MINUTES = 2;

    public interface TranslationListener {
        void onTranslated(GameTextFrameProcessor.Frame frame, String sourceLanguage);
        void onFailure(Exception error);
    }

    public interface ModelListener {
        void onReady();
        void onFailure(Exception error);
    }

    private final LanguageIdentifier languageIdentifier = LanguageIdentification.getClient();
    private final Map<String, Translator> translators = new HashMap<>();
    private final Map<String, ModelAttempt> modelAttempts = new HashMap<>();
    private final GameTextTranslationCache cache;
    private volatile boolean closed;

    public GameTextTranslator() {
        cache = null;
    }

    public GameTextTranslator(Context context, String cacheNamespace, int cacheMaxEntries) {
        cache = new GameTextTranslationCache(context, cacheNamespace, cacheMaxEntries);
    }

    public void translate(
            GameTextFrameProcessor.Frame frame,
            String configuredSourceLanguage,
            String targetLanguage,
            TextReplacementRules replacementRules,
            TranslationListener listener
    ) {
        if (frame == null || frame.items.isEmpty() || GameTextLanguage.ORIGINAL.equals(targetLanguage)) {
            listener.onTranslated(frame, configuredSourceLanguage);
            return;
        }

        if (GameTextLanguage.AUTO.equals(configuredSourceLanguage)) {
            synchronized (this) {
                if (closed) {
                    listener.onFailure(new IllegalStateException("Translator is closed"));
                    return;
                }
                languageIdentifier.identifyLanguage(sourceText(frame))
                        .addOnSuccessListener(languageTag -> translateIdentified(
                                frame,
                                languageTag,
                                targetLanguage,
                                replacementRules,
                                listener
                        ))
                        .addOnFailureListener(listener::onFailure);
            }
        }
        else {
            translateIdentified(
                    frame,
                    configuredSourceLanguage,
                    targetLanguage,
                    replacementRules,
                    listener
            );
        }
    }

    public void prepareModels(String sourceLanguage, String targetLanguage, ModelListener listener) {
        if (GameTextLanguage.AUTO.equals(sourceLanguage)) {
            listener.onFailure(new IllegalArgumentException("Select a source language before preparing models"));
            return;
        }
        if (GameTextLanguage.ORIGINAL.equals(targetLanguage) || sourceLanguage.equals(targetLanguage)) {
            listener.onReady();
            return;
        }

        String source = supportedLanguage(sourceLanguage);
        String target = supportedLanguage(targetLanguage);
        if (source == null || target == null) {
            listener.onFailure(new IllegalArgumentException("Unsupported translation language"));
            return;
        }

        Task<Void> task = modelTask(source, target, true);
        if (task == null) {
            listener.onFailure(new IllegalStateException("Translator is closed"));
            return;
        }
        Tasks.withTimeout(task, PREPARE_MODEL_WAIT_MINUTES, TimeUnit.MINUTES)
                .addOnSuccessListener(ignored -> {
                    if (!closed) listener.onReady();
                })
                .addOnFailureListener(error -> {
                    if (!closed) listener.onFailure(error);
                });
    }

    private void translateIdentified(
            GameTextFrameProcessor.Frame frame,
            String languageTag,
            String targetLanguage,
            TextReplacementRules replacementRules,
            TranslationListener listener
    ) {
        String source = supportedLanguage(languageTag);
        String target = supportedLanguage(targetLanguage);
        if (source == null || target == null || "und".equals(languageTag)) {
            listener.onFailure(new IllegalArgumentException("Language could not be identified for translation"));
            return;
        }
        if (source.equals(target)) {
            listener.onTranslated(frame, source);
            return;
        }

        Task<Void> task = modelTask(source, target, false);
        if (task == null) {
            listener.onFailure(new IllegalStateException("Translator is closed"));
            return;
        }
        Tasks.withTimeout(task, LIVE_MODEL_WAIT_SECONDS, TimeUnit.SECONDS)
                .addOnSuccessListener(ignored -> {
                    Translator translator;
                    synchronized (GameTextTranslator.this) {
                        if (closed) {
                            listener.onFailure(new IllegalStateException("Translator is closed"));
                            return;
                        }
                        translator = translatorFor(source, target);
                    }
                    translateItem(
                            translator,
                            frame,
                            replacementRules,
                            source,
                            target,
                            0,
                            new ArrayList<>(),
                            listener
                    );
                })
                .addOnFailureListener(listener::onFailure);
    }

    private void translateItem(
            Translator translator,
            GameTextFrameProcessor.Frame frame,
            TextReplacementRules replacementRules,
            String sourceLanguage,
            String targetLanguage,
            int index,
            List<String> translatedTexts,
            TranslationListener listener
    ) {
        if (closed) {
            listener.onFailure(new IllegalStateException("Translator is closed"));
            return;
        }
        if (index >= frame.items.size()) {
            listener.onTranslated(applyTranslations(frame, translatedTexts, replacementRules), sourceLanguage);
            return;
        }
        String sourceText = frame.items.get(index).sourceText;
        String cached = cache != null
                ? cache.get(
                        sourceLanguage,
                        targetLanguage,
                        replacementRules.version(),
                        sourceText
                )
                : null;
        if (cached != null) {
            translatedTexts.add(cached);
            translateItem(
                    translator,
                    frame,
                    replacementRules,
                    sourceLanguage,
                    targetLanguage,
                    index + 1,
                    translatedTexts,
                    listener
            );
            return;
        }
        synchronized (this) {
            if (closed) {
                listener.onFailure(new IllegalStateException("Translator is closed"));
                return;
            }
            translator.translate(sourceText)
                    .addOnSuccessListener(text -> {
                        if (cache != null) {
                            cache.put(
                                    sourceLanguage,
                                    targetLanguage,
                                    replacementRules.version(),
                                    sourceText,
                                    text
                            );
                        }
                        translatedTexts.add(text);
                        translateItem(
                                translator,
                                frame,
                                replacementRules,
                                sourceLanguage,
                                targetLanguage,
                                index + 1,
                                translatedTexts,
                                listener
                        );
                    })
                    .addOnFailureListener(listener::onFailure);
        }
    }

    static GameTextFrameProcessor.Frame applyTranslations(
            GameTextFrameProcessor.Frame frame,
            List<String> translatedTexts,
            TextReplacementRules replacementRules
    ) {
        ArrayList<GameTextFrameProcessor.Item> items = new ArrayList<>();
        StringBuilder subtitle = new StringBuilder();
        for (int i = 0; i < frame.items.size(); i++) {
            GameTextFrameProcessor.Item item = frame.items.get(i);
            String translated = i < translatedTexts.size() ? translatedTexts.get(i) : item.sourceText;
            String displayText = replacementRules.apply(translated);
            if (displayText.isEmpty()) continue;
            items.add(new GameTextFrameProcessor.Item(
                    item.sourceText,
                    displayText,
                    item.normalizedBounds,
                    item.backgroundColor,
                    item.foregroundColor
            ));
            if (subtitle.length() > 0) subtitle.append('\n');
            subtitle.append(displayText);
        }
        return new GameTextFrameProcessor.Frame(items, subtitle.toString());
    }

    private synchronized Translator translatorFor(String sourceLanguage, String targetLanguage) {
        String key = sourceLanguage + ">" + targetLanguage;
        Translator translator = translators.get(key);
        if (translator == null) {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build();
            translator = Translation.getClient(options);
            translators.put(key, translator);
        }
        return translator;
    }

    private synchronized Task<Void> modelTask(
            String sourceLanguage,
            String targetLanguage,
            boolean retryFailure
    ) {
        if (closed) return null;
        String key = sourceLanguage + ">" + targetLanguage;
        ModelAttempt attempt = modelAttempts.get(key);
        boolean retryExpired = attempt != null &&
                attempt.failedAt > 0 &&
                SystemClock.elapsedRealtime() - attempt.failedAt >= MODEL_RETRY_MILLIS;
        if (attempt == null ||
                ((retryFailure || retryExpired) &&
                        attempt.task.isComplete() &&
                        !attempt.task.isSuccessful())) {
            Task<Void> task = translatorFor(sourceLanguage, targetLanguage)
                    .downloadModelIfNeeded(MODEL_DOWNLOAD_CONDITIONS);
            attempt = new ModelAttempt(task);
            ModelAttempt current = attempt;
            task.addOnFailureListener(error ->
                    current.failedAt = SystemClock.elapsedRealtime()
            );
            modelAttempts.put(key, attempt);
        }
        return attempt.task;
    }

    private static String sourceText(GameTextFrameProcessor.Frame frame) {
        StringBuilder text = new StringBuilder();
        for (GameTextFrameProcessor.Item item : frame.items) {
            if (text.length() > 0) text.append('\n');
            text.append(item.sourceText);
        }
        return text.toString();
    }

    private static String supportedLanguage(String languageTag) {
        return TranslateLanguage.fromLanguageTag(languageTag);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        languageIdentifier.close();
        for (Translator translator : translators.values()) translator.close();
        translators.clear();
        modelAttempts.clear();
        if (cache != null) cache.close();
    }

    private static final class ModelAttempt {
        final Task<Void> task;
        volatile long failedAt;

        ModelAttempt(Task<Void> task) {
            this.task = task;
        }
    }
}
