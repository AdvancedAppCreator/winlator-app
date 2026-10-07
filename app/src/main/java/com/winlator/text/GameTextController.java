package com.winlator.text;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.StartupLog;
import com.winlator.renderer.GLRenderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GameTextController implements AutoCloseable, GLRenderer.FrameCaptureListener {
    private static final String TAG = "GameText";
    private static final long STRONG_OCR_RESULT_HOLD_MILLIS = 10000;

    public interface SnapshotListener {
        void onCaptured(Bitmap bitmap);
    }

    public interface InspectionListener {
        void onInspectionReady(Bitmap snapshot, List<RectF> detectedRegions);
    }

    public interface ConfigStore {
        GameTextConfig load();
        void save(GameTextConfig config);

        default boolean tiledStrongOcrEnabled() {
            return true;
        }

        default String strongOcrPreprocessing() {
            return "AUTO";
        }

        default boolean translationCacheEnabled() {
            return true;
        }

        default int translationCacheMaxEntries() {
            return GameTextTranslationCache.DEFAULT_MAX_ENTRIES;
        }

        default String contentLanguage() {
            return "und";
        }

        default void saveDetectedScript(GameTextConfig.Script script) {
        }
    }

    private final Activity activity;
    private final ConfigStore configStore;
    private final GLRenderer renderer;
    private final GameTextOverlayView overlayView;
    private final GameTextFrameProcessor frameProcessor = new GameTextFrameProcessor();
    private final GameTextTranslator translator;
    private final AtomicBoolean recognitionInProgress = new AtomicBoolean();
    private final AtomicBoolean strongRecognitionInProgress = new AtomicBoolean();
    private final Object recognizerLock = new Object();
    private final ExecutorService strongOcrExecutor = Executors.newSingleThreadExecutor();
    private GameTextRecognizer recognizer;
    private GameTextConfig.Script recognizerScript;
    private volatile GameTextConfig config;
    private volatile TextReplacementRules replacementRules;
    private volatile int generation;
    private volatile boolean errorReported;
    private volatile boolean translationErrorReported;
    private volatile boolean strongTranslationErrorReported;
    private volatile boolean scriptPersistenceErrorReported;
    private volatile boolean closed;
    private volatile long liveOverlaySuppressedUntil;
    private volatile boolean automaticScript;
    private int poorRecognitionCount;

    public GameTextController(
            Activity activity,
            SharedPreferences preferences,
            GLRenderer renderer,
            GameTextOverlayView overlayView,
            String cacheNamespace
    ) {
        this(
                activity,
                preferencesConfigStore(preferences),
                renderer,
                overlayView,
                cacheNamespace
        );
    }

    public GameTextController(
            Activity activity,
            ConfigStore configStore,
            GLRenderer renderer,
            GameTextOverlayView overlayView,
            String cacheNamespace
    ) {
        this.activity = activity;
        this.configStore = configStore;
        this.renderer = renderer;
        this.overlayView = overlayView;
        translator = configStore.translationCacheEnabled()
                ? new GameTextTranslator(
                        activity,
                        cacheNamespace,
                        configStore.translationCacheMaxEntries()
                )
                : new GameTextTranslator();
        reload();
    }

    public void reload() {
        generation++;
        config = configStore.load();
        replacementRules = new TextReplacementRules(config.replacements);
        synchronized (frameProcessor) {
            frameProcessor.reset();
        }
        overlayView.setMode(config.mode);
        overlayView.clear();
        errorReported = false;
        translationErrorReported = false;
        strongTranslationErrorReported = false;
        scriptPersistenceErrorReported = false;
        recognitionInProgress.set(false);
        liveOverlaySuppressedUntil = 0;
        automaticScript = GameTextLanguage.AUTO.equals(config.sourceLanguage);
        poorRecognitionCount = 0;

        if (config.mode == GameTextConfig.Mode.OFF) {
            renderer.setFrameCaptureListener(null, null, config.intervalMillis);
            synchronized (recognizerLock) {
                if (recognizer != null) {
                    recognizer.close();
                    recognizer = null;
                    recognizerScript = null;
                }
            }
            return;
        }

        synchronized (recognizerLock) {
            GameTextConfig.Script requestedScript = automaticScript
                    ? OcrScriptSelector.forLanguage(
                            configStore.contentLanguage(),
                            config.script
                    )
                    : config.script;
            if (recognizer == null || recognizerScript != requestedScript) {
                GameTextRecognizer oldRecognizer = recognizer;
                recognizer = new GameTextRecognizer(requestedScript);
                recognizerScript = requestedScript;
                if (oldRecognizer != null) oldRecognizer.close();
            }
        }
        renderer.setFrameCaptureListener(this, config.captureRegion, config.intervalMillis);
    }

    public GameTextConfig getConfig() {
        return config;
    }

    public void saveConfig(GameTextConfig config) {
        configStore.save(config);
        reload();
    }

    public static ConfigStore preferencesConfigStore(SharedPreferences preferences) {
        return new ConfigStore() {
            @Override
            public GameTextConfig load() {
                return GameTextConfig.load(preferences);
            }

            @Override
            public void save(GameTextConfig config) {
                config.save(preferences);
            }
        };
    }

    public boolean isEnabled() {
        return config.mode != GameTextConfig.Mode.OFF;
    }

    public void showPreview() {
        RectF first = new RectF(0.18f, 0.62f, 0.82f, 0.70f);
        RectF second = new RectF(0.24f, 0.72f, 0.76f, 0.80f);
        List<GameTextFrameProcessor.Item> items = Arrays.asList(
                new GameTextFrameProcessor.Item(
                        "The door is locked.",
                        replacementRules.apply("The door is locked."),
                        first,
                        Color.rgb(28, 28, 32),
                        Color.WHITE
                ),
                new GameTextFrameProcessor.Item(
                        "Find another way inside.",
                        replacementRules.apply("Find another way inside."),
                        second,
                        Color.rgb(28, 28, 32),
                        Color.WHITE
                )
        );
        overlayView.setFrame(new GameTextFrameProcessor.Frame(
                items,
                items.get(0).displayText + "\n" + items.get(1).displayText
        ));
    }

    public void prepareTranslationModels(GameTextConfig requestedConfig, GameTextTranslator.ModelListener listener) {
        translator.prepareModels(
                requestedConfig.sourceLanguage,
                requestedConfig.targetLanguage,
                new GameTextTranslator.ModelListener() {
                    @Override
                    public void onReady() {
                        listener.onReady();
                    }

                    @Override
                    public void onFailure(Exception error) {
                        StartupLog.log(
                                "Translation model preparation failed " +
                                        requestedConfig.sourceLanguage + " -> " +
                                        requestedConfig.targetLanguage,
                                error
                        );
                        listener.onFailure(error);
                    }
                }
        );
    }

    public void captureStrongOcrSnapshot(SnapshotListener listener) {
        if (closed || listener == null) return;
        renderer.captureFrameOnce((bitmap, captureBounds, surfaceWidth, surfaceHeight) ->
                activity.runOnUiThread(() -> {
                    if (closed || activity.isFinishing()) {
                        bitmap.recycle();
                        return;
                    }
                    listener.onCaptured(bitmap);
                }), new RectF(0.0f, 0.0f, 1.0f, 1.0f));
    }

    public void runStrongOcr(Bitmap frame, RectF region) {
        runStrongOcr(frame, region, null);
    }

    public void runStrongOcr(
            Bitmap frame,
            RectF region,
            InspectionListener inspectionListener
    ) {
        if (frame == null || frame.isRecycled()) return;
        if (closed || config.mode == GameTextConfig.Mode.OFF) {
            frame.recycle();
            activity.runOnUiThread(() -> AppUtils.showToast(activity, R.string.game_text_strong_enable_first));
            return;
        }
        if (!strongRecognitionInProgress.compareAndSet(false, true)) {
            frame.recycle();
            activity.runOnUiThread(() -> AppUtils.showToast(activity, R.string.game_text_strong_busy));
            return;
        }

        liveOverlaySuppressedUntil = Long.MAX_VALUE;
        activity.runOnUiThread(() -> AppUtils.showToast(activity, R.string.game_text_strong_processing));
        StrongOcrSession session = new StrongOcrSession(
                generation,
                config,
                replacementRules,
                frame,
                region,
                inspectionListener
        );
        if (!executeStrongTask(session::prepare)) {
            session.fail(new IllegalStateException("Strong OCR worker is unavailable"));
        }
    }

    @Override
    public void onFrameCaptured(Bitmap bitmap, Rect captureBounds, int surfaceWidth, int surfaceHeight) {
        long suppressedUntil = liveOverlaySuppressedUntil;
        long now = SystemClock.uptimeMillis();
        if (suppressedUntil == Long.MAX_VALUE || now < suppressedUntil) {
            bitmap.recycle();
            return;
        }
        if (suppressedUntil > 0) {
            liveOverlaySuppressedUntil = 0;
            synchronized (frameProcessor) {
                frameProcessor.reset();
            }
        }
        if (!recognitionInProgress.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }

        int requestGeneration = generation;
        GameTextRecognizer requestRecognizer;
        synchronized (recognizerLock) {
            requestRecognizer = recognizer;
            if (requestRecognizer == null || config.mode == GameTextConfig.Mode.OFF) {
                bitmap.recycle();
                recognitionInProgress.set(false);
                return;
            }
            requestRecognizer.recognize(bitmap, new GameTextRecognizer.Listener() {
            @Override
            public void onRecognized(List<GameTextFrameProcessor.Detection> detections) {
                if (requestGeneration == generation) {
                    updateAutomaticScript(detections);
                    GameTextFrameProcessor.Frame frame;
                    synchronized (frameProcessor) {
                        frame = frameProcessor.process(
                                detections,
                                captureBounds,
                                surfaceWidth,
                                surfaceHeight,
                                bitmap,
                                replacementRules
                        );
                    }
                    bitmap.recycle();
                    if (frame == null) {
                        recognitionInProgress.set(false);
                        return;
                    }
                    translator.translate(
                            frame,
                            config.sourceLanguage,
                            config.targetLanguage,
                            replacementRules,
                            new GameTextTranslator.TranslationListener() {
                                @Override
                                public void onTranslated(GameTextFrameProcessor.Frame translatedFrame, String sourceLanguage) {
                                    if (requestGeneration == generation) {
                                        activity.runOnUiThread(() -> overlayView.setFrame(translatedFrame));
                                    }
                                    recognitionInProgress.set(false);
                                }

                                @Override
                                public void onFailure(Exception error) {
                                    Log.w(TAG, "Offline game text translation unavailable", error);
                                    if (requestGeneration == generation) {
                                        activity.runOnUiThread(() -> overlayView.setFrame(frame));
                                        if (!translationErrorReported) {
                                            translationErrorReported = true;
                                            StartupLog.log(
                                                    "Offline game text translation unavailable " +
                                                            config.sourceLanguage + " -> " +
                                                            config.targetLanguage,
                                                    error
                                            );
                                            activity.runOnUiThread(() -> AppUtils.showToast(
                                                    activity,
                                                    R.string.game_text_translation_unavailable
                                            ));
                                        }
                                    }
                                    recognitionInProgress.set(false);
                                }
                            }
                    );
                    return;
                }
                bitmap.recycle();
                recognitionInProgress.set(false);
            }

            @Override
            public void onFailure(Exception error) {
                Log.e(TAG, "On-device game text recognition failed", error);
                bitmap.recycle();
                recognitionInProgress.set(false);
                if (requestGeneration == generation && !errorReported) {
                    errorReported = true;
                    activity.runOnUiThread(() -> AppUtils.showToast(activity, R.string.game_text_recognition_failed));
                }
            }
            });
        }
    }

    @Override
    public void close() {
        closed = true;
        liveOverlaySuppressedUntil = 0;
        generation++;
        renderer.setFrameCaptureListener(null, null, 1000);
        synchronized (recognizerLock) {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
                recognizerScript = null;
            }
        }
        strongOcrExecutor.shutdownNow();
        translator.close();
        overlayView.clear();
    }

    private boolean executeStrongTask(Runnable task) {
        if (closed || strongOcrExecutor.isShutdown()) return false;
        try {
            strongOcrExecutor.execute(task);
            return true;
        }
        catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void updateAutomaticScript(
            List<GameTextFrameProcessor.Detection> detections
    ) {
        if (!automaticScript || closed) return;
        StringBuilder text = new StringBuilder();
        if (detections != null) {
            for (GameTextFrameProcessor.Detection detection : detections) {
                if (text.length() > 0) text.append('\n');
                text.append(detection.text);
            }
        }
        GameTextConfig.Script detected = OcrScriptSelector.detectFromText(text.toString());
        int score = OcrScriptSelector.recognitionScore(detections);
        if (detected != null) {
            poorRecognitionCount = 0;
            switchRecognizer(detected, true);
            return;
        }
        if (score >= 4) {
            poorRecognitionCount = 0;
            persistDetectedScript(recognizerScript);
            return;
        }
        poorRecognitionCount++;
        if (poorRecognitionCount < 3) return;
        poorRecognitionCount = 0;
        switchRecognizer(OcrScriptSelector.next(recognizerScript), false);
    }

    private void switchRecognizer(
            GameTextConfig.Script script,
            boolean persist
    ) {
        synchronized (recognizerLock) {
            if (recognizerScript == script) {
                if (persist) persistDetectedScript(script);
                return;
            }
            GameTextRecognizer oldRecognizer = recognizer;
            recognizer = new GameTextRecognizer(script);
            recognizerScript = script;
            if (oldRecognizer != null) oldRecognizer.close();
        }
        if (persist) persistDetectedScript(script);
    }

    private void persistDetectedScript(GameTextConfig.Script script) {
        try {
            configStore.saveDetectedScript(script);
        }
        catch (RuntimeException error) {
            Log.e(TAG, "Unable to persist the detected OCR script", error);
            if (!scriptPersistenceErrorReported) {
                scriptPersistenceErrorReported = true;
                activity.runOnUiThread(() -> AppUtils.showToast(
                        activity,
                        "Detected OCR script could not be saved."
                ));
            }
        }
    }

    private final class StrongOcrSession {
        private final int requestGeneration;
        private final GameTextConfig requestConfig;
        private final TextReplacementRules requestReplacementRules;
        private final RectF region;
        private final boolean tiled;
        private final GameTextConfig.Script requestScript;
        private final InspectionListener inspectionListener;
        private final String preprocessing;
        private Bitmap capturedFrame;
        private StrongOcrImageProcessor imageProcessor;
        private GameTextRecognizer strongRecognizer;
        private List<RectF> tileRegions = Collections.emptyList();
        private int tileIndex;
        private List<GameTextFrameProcessor.Detection> accumulatedDetections =
                Collections.emptyList();
        private int outlinedEnhancedWidth = -1;
        private int outlinedEnhancedHeight = -1;
        private Exception firstError;
        private boolean passSucceeded;
        private boolean finished;

        StrongOcrSession(
                int requestGeneration,
                GameTextConfig requestConfig,
                TextReplacementRules requestReplacementRules,
                Bitmap capturedFrame,
                RectF region,
                InspectionListener inspectionListener
        ) {
            this.requestGeneration = requestGeneration;
            this.requestConfig = requestConfig;
            this.requestReplacementRules = requestReplacementRules;
            this.capturedFrame = capturedFrame;
            this.region = region != null
                    ? new RectF(region)
                    : new RectF(0.0f, 0.0f, 1.0f, 1.0f);
            tiled = configStore.tiledStrongOcrEnabled();
            preprocessing = configStore.strongOcrPreprocessing();
            this.inspectionListener = inspectionListener;
            synchronized (recognizerLock) {
                requestScript = recognizerScript != null
                        ? recognizerScript
                        : requestConfig.script;
            }
        }

        void prepare() {
            if (closed) {
                finish();
                return;
            }
            try {
                strongRecognizer = new GameTextRecognizer(requestScript);
                tileRegions = StrongOcrImageProcessor.tileRegions(region, tiled);
                prepareTile();
            }
            catch (RuntimeException error) {
                fail(error);
            }
        }

        void prepareTile() {
            try {
                if (imageProcessor != null) imageProcessor.close();
                imageProcessor = StrongOcrImageProcessor.prepare(
                        capturedFrame,
                        tileRegions.get(tileIndex)
                );
                recognizeVariant(0);
            }
            catch (RuntimeException error) {
                fail(error);
            }
        }

        void recognizeVariant(int variantIndex) {
            Bitmap variant;
            int variantKind;
            try {
                variantKind = variantKind(variantIndex);
                if (variantKind == 0) {
                    variant = imageProcessor.createColorVariant();
                }
                else if (variantKind == 1) {
                    variant = imageProcessor.createHighContrastVariant();
                }
                else if (variantKind == 2) {
                    variant = imageProcessor.createBrightTextVariant();
                }
                else {
                    variant = imageProcessor.createOutlinedTextVariant(variantKind == 3);
                }
            }
            catch (RuntimeException error) {
                fail(error);
                return;
            }

            int variantWidth = variant.getWidth();
            int variantHeight = variant.getHeight();
            if (variantKind == 3) {
                outlinedEnhancedWidth = variantWidth;
                outlinedEnhancedHeight = variantHeight;
            }
            else if (variantKind == 4
                    && variantWidth == outlinedEnhancedWidth
                    && variantHeight == outlinedEnhancedHeight) {
                variant.recycle();
                if (passSucceeded) {
                    advanceTileOrProcess();
                }
                else {
                    fail(firstError != null
                            ? firstError
                            : new IllegalStateException("Outlined OCR produced no usable pass"));
                }
                return;
            }
            strongRecognizer.recognize(variant, new GameTextRecognizer.Listener() {
                @Override
                public void onRecognized(List<GameTextFrameProcessor.Detection> detections) {
                    variant.recycle();
                    passSucceeded = true;
                    accumulatedDetections = StrongOcrImageProcessor.mergeDetections(
                            accumulatedDetections,
                            imageProcessor.mapDetectionsToSurface(
                                    detections,
                                    variantWidth,
                                    variantHeight
                            )
                    );
                    if (variantIndex + 1 < variantCount()) {
                        if (!executeStrongTask(() -> recognizeVariant(variantIndex + 1))) {
                            fail(new IllegalStateException("Strong OCR worker stopped"));
                        }
                        return;
                    }
                    advanceTileOrProcess();
                }

                @Override
                public void onFailure(Exception error) {
                    variant.recycle();
                    if (firstError == null) firstError = error;
                    if (variantIndex + 1 < variantCount()) {
                        if (!executeStrongTask(() -> recognizeVariant(variantIndex + 1))) {
                            fail(error);
                        }
                    }
                    else if (passSucceeded) {
                        advanceTileOrProcess();
                    }
                    else {
                        fail(firstError != null ? firstError : error);
                    }
                }
            });
        }

        private int variantCount() {
            if ("AUTO".equals(preprocessing)) return 5;
            return "OUTLINED_TEXT".equals(preprocessing) ? 2 : 1;
        }

        private int variantKind(int variantIndex) {
            if ("AUTO".equals(preprocessing)) return variantIndex;
            if ("OUTLINED_TEXT".equals(preprocessing)) return 3 + variantIndex;
            return preprocessingVariant(preprocessing);
        }

        private int preprocessingVariant(String value) {
            switch (value) {
                case "HIGH_CONTRAST":
                    return 1;
                case "BRIGHT_TEXT":
                    return 2;
                case "OUTLINED_TEXT":
                    return 3;
                case "COLOR":
                default:
                    return 0;
            }
        }

        void advanceTileOrProcess() {
            tileIndex++;
            if (tileIndex < tileRegions.size()) {
                if (!executeStrongTask(this::prepareTile)) {
                    fail(new IllegalStateException("Strong OCR worker stopped"));
                }
                return;
            }
            if (!executeStrongTask(() -> processDetections(accumulatedDetections))) {
                fail(new IllegalStateException("Strong OCR worker stopped"));
            }
        }

        void processDetections(List<GameTextFrameProcessor.Detection> detections) {
            try {
                GameTextFrameProcessor.Frame frame = new GameTextFrameProcessor().process(
                        detections,
                        new Rect(0, 0, capturedFrame.getWidth(), capturedFrame.getHeight()),
                        capturedFrame.getWidth(),
                        capturedFrame.getHeight(),
                        capturedFrame,
                        requestReplacementRules
                );
                strongRecognizer.close();
                strongRecognizer = null;
                imageProcessor.close();
                imageProcessor = null;

                if (frame == null || frame.items.isEmpty()) {
                    finishWithMessage(R.string.game_text_strong_no_text);
                    return;
                }
                translator.translate(
                        frame,
                        requestConfig.sourceLanguage,
                        requestConfig.targetLanguage,
                        requestReplacementRules,
                        new GameTextTranslator.TranslationListener() {
                            @Override
                            public void onTranslated(
                                    GameTextFrameProcessor.Frame translatedFrame,
                                    String sourceLanguage
                            ) {
                                if (requestGeneration == generation && !closed) {
                                    holdStrongResult();
                                    activity.runOnUiThread(() -> overlayView.setFrame(translatedFrame));
                                    deliverInspection(detections);
                                }
                                finish();
                            }

                            @Override
                            public void onFailure(Exception error) {
                                Log.w(TAG, "Strong OCR translation unavailable", error);
                                if (!strongTranslationErrorReported) {
                                    strongTranslationErrorReported = true;
                                    StartupLog.log(
                                            "Strong OCR translation unavailable " +
                                                    requestConfig.sourceLanguage + " -> " +
                                                    requestConfig.targetLanguage,
                                            error
                                    );
                                }
                                if (requestGeneration == generation && !closed) {
                                    holdStrongResult();
                                    activity.runOnUiThread(() -> {
                                        overlayView.setFrame(frame);
                                        AppUtils.showToast(
                                                activity,
                                                R.string.game_text_translation_unavailable
                                        );
                                    });
                                    deliverInspection(detections);
                                }
                                finish();
                            }
                        }
                );
            }
            catch (RuntimeException error) {
                fail(error);
            }
        }

        void fail(Exception error) {
            Log.e(TAG, "Strong OCR failed", error);
            resumeLiveOverlay();
            if (!closed && requestGeneration == generation) {
                activity.runOnUiThread(() -> AppUtils.showToast(
                        activity,
                        R.string.game_text_strong_failed
                ));
            }
            finish();
        }

        void finishWithMessage(int messageResId) {
            resumeLiveOverlay();
            if (!closed && requestGeneration == generation) {
                activity.runOnUiThread(() -> AppUtils.showToast(activity, messageResId));
            }
            finish();
        }

        synchronized void finish() {
            if (finished) return;
            finished = true;
            cleanup();
            strongRecognitionInProgress.set(false);
        }

        private void cleanup() {
            if (capturedFrame != null && !capturedFrame.isRecycled()) {
                capturedFrame.recycle();
                capturedFrame = null;
            }
            if (strongRecognizer != null) {
                strongRecognizer.close();
                strongRecognizer = null;
            }
            if (imageProcessor != null) {
                imageProcessor.close();
                imageProcessor = null;
            }
        }

        private void holdStrongResult() {
            liveOverlaySuppressedUntil = SystemClock.uptimeMillis() + STRONG_OCR_RESULT_HOLD_MILLIS;
        }

        private void resumeLiveOverlay() {
            liveOverlaySuppressedUntil = 0;
            synchronized (frameProcessor) {
                frameProcessor.reset();
            }
        }

        private void deliverInspection(
                List<GameTextFrameProcessor.Detection> detections
        ) {
            if (inspectionListener == null || capturedFrame == null) return;
            ArrayList<RectF> regions = new ArrayList<>();
            float width = capturedFrame.getWidth();
            float height = capturedFrame.getHeight();
            for (GameTextFrameProcessor.Detection detection : detections) {
                regions.add(new RectF(
                        detection.bounds.left / width,
                        detection.bounds.top / height,
                        detection.bounds.right / width,
                        detection.bounds.bottom / height
                ));
            }
            Bitmap inspectionSnapshot = capturedFrame;
            capturedFrame = null;
            activity.runOnUiThread(() ->
                    inspectionListener.onInspectionReady(
                            inspectionSnapshot,
                            regions
                    ));
        }
    }
}
