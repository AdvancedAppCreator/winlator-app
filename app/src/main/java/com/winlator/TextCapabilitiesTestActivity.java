package com.winlator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.core.AppUtils;
import com.winlator.text.GameTextConfig;
import com.winlator.text.GameTextFrameProcessor;
import com.winlator.text.GameTextLanguage;
import com.winlator.text.GameTextOverlayView;
import com.winlator.text.GameTextRecognizer;
import com.winlator.text.GameTextTranslator;
import com.winlator.text.TextReplacementRules;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TextCapabilitiesTestActivity extends AppCompatActivity {
    private final GameTextFrameProcessor processor = new GameTextFrameProcessor();
    private final AtomicBoolean recognitionInProgress = new AtomicBoolean();
    private GameTextRecognizer recognizer;
    private GameTextConfig.Script recognizerScript = GameTextConfig.Script.LATIN;
    private GameTextOverlayView overlayView;
    private View testCard;
    private EditText sourceText;
    private EditText replacementText;
    private Spinner modeSpinner;
    private Spinner scriptSpinner;
    private Spinner sourceLanguageSpinner;
    private Spinner targetLanguageSpinner;
    private TextView statusText;
    private GameTextTranslator translator;
    private final List<GameTextLanguage.Option> sourceLanguages = GameTextLanguage.sourceOptions();
    private final List<GameTextLanguage.Option> targetLanguages = GameTextLanguage.targetOptions();
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.text_capabilities_test_activity);

        overlayView = findViewById(R.id.GameTextTestOverlay);
        testCard = findViewById(R.id.GameTextTestCard);
        sourceText = findViewById(R.id.ETGameTextTestSource);
        replacementText = findViewById(R.id.ETGameTextTestReplacement);
        modeSpinner = findViewById(R.id.SGameTextTestMode);
        scriptSpinner = findViewById(R.id.SGameTextTestScript);
        sourceLanguageSpinner = findViewById(R.id.SGameTextTestSourceLanguage);
        targetLanguageSpinner = findViewById(R.id.SGameTextTestTargetLanguage);
        statusText = findViewById(R.id.TVGameTextTestStatus);

        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                getString(R.string.subtitle_bar),
                getString(R.string.visual_replacement)
        }));
        scriptSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Latin", "Chinese", "Devanagari", "Japanese", "Korean"
        }));
        sourceLanguageSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                sourceLanguages
        ));
        targetLanguageSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                targetLanguages
        ));
        sourceLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String sourceLanguage = sourceLanguages.get(position).tag;
                if (!GameTextLanguage.AUTO.equals(sourceLanguage)) {
                    scriptSpinner.setSelection(GameTextLanguage.recommendedScript(sourceLanguage).ordinal());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        findViewById(R.id.BTRunGameTextTest).setOnClickListener(view -> runRecognitionTest());
        findViewById(R.id.BTPreviewGameTextTest).setOnClickListener(view -> showDeterministicPreview());
        findViewById(R.id.BTPrepareGameTextTestTranslation).setOnClickListener(view -> prepareTranslationModel());
    }

    private void runRecognitionTest() {
        if (!recognitionInProgress.compareAndSet(false, true)) return;
        statusText.setText(R.string.game_text_recognizing);
        processor.reset();
        ((TextView)findViewById(R.id.TVGameTextTestCardText)).setText(sourceText.getText());

        GameTextConfig.Script script = GameTextConfig.Script.values()[scriptSpinner.getSelectedItemPosition()];
        if (recognizer == null || recognizerScript != script) {
            if (recognizer != null) recognizer.close();
            recognizer = new GameTextRecognizer(script);
            recognizerScript = script;
        }
        Bitmap bitmap = Bitmap.createBitmap(testCard.getWidth(), testCard.getHeight(), Bitmap.Config.ARGB_8888);
        testCard.draw(new Canvas(bitmap));

        recognizer.recognize(bitmap, new GameTextRecognizer.Listener() {
            @Override
            public void onRecognized(List<GameTextFrameProcessor.Detection> detections) {
                if (destroyed) {
                    bitmap.recycle();
                    recognitionInProgress.set(false);
                    return;
                }
                Rect cardBounds = boundsRelativeToRoot(testCard);
                TextReplacementRules rules = new TextReplacementRules(
                        sourceText.getText().toString() + " => " + replacementText.getText().toString()
                );
                GameTextFrameProcessor.Frame frame = processor.process(
                        detections,
                        cardBounds,
                        findViewById(R.id.GameTextTestRoot).getWidth(),
                        findViewById(R.id.GameTextTestRoot).getHeight(),
                        bitmap,
                        rules
                );
                bitmap.recycle();
                if (frame == null) {
                    recognitionInProgress.set(false);
                    return;
                }
                translator().translate(
                        frame,
                        selectedSourceLanguage(),
                        selectedTargetLanguage(),
                        rules,
                        new GameTextTranslator.TranslationListener() {
                            @Override
                            public void onTranslated(GameTextFrameProcessor.Frame translatedFrame, String sourceLanguage) {
                                if (!destroyed) {
                                    overlayView.setMode(selectedMode());
                                    overlayView.setFrame(translatedFrame);
                                    if (GameTextLanguage.ORIGINAL.equals(selectedTargetLanguage())) {
                                        statusText.setText(getString(
                                                R.string.game_text_test_detected,
                                                detections.size()
                                        ));
                                    }
                                    else {
                                        statusText.setText(getString(
                                                R.string.game_text_test_translated,
                                                detections.size(),
                                                sourceLanguage
                                        ));
                                    }
                                }
                                recognitionInProgress.set(false);
                            }

                            @Override
                            public void onFailure(Exception error) {
                                if (!destroyed) {
                                    overlayView.setMode(selectedMode());
                                    overlayView.setFrame(frame);
                                    statusText.setText(getString(
                                            R.string.game_text_test_failed,
                                            error.getMessage()
                                    ));
                                }
                                recognitionInProgress.set(false);
                            }
                        }
                );
            }

            @Override
            public void onFailure(Exception error) {
                if (destroyed) {
                    bitmap.recycle();
                    recognitionInProgress.set(false);
                    return;
                }
                statusText.setText(getString(R.string.game_text_test_failed, error.getMessage()));
                bitmap.recycle();
                recognitionInProgress.set(false);
            }
        });
    }

    private void prepareTranslationModel() {
        statusText.setText(R.string.game_text_preparing_translation);
        translator().prepareModels(
                selectedSourceLanguage(),
                selectedTargetLanguage(),
                new GameTextTranslator.ModelListener() {
                    @Override
                    public void onReady() {
                        if (!destroyed) statusText.setText(R.string.game_text_translation_ready);
                    }

                    @Override
                    public void onFailure(Exception error) {
                        if (!destroyed) {
                            statusText.setText(getString(
                                    R.string.game_text_translation_prepare_failed,
                                    error.getMessage()
                            ));
                        }
                    }
                }
        );
    }

    private GameTextTranslator translator() {
        if (translator == null) translator = new GameTextTranslator();
        return translator;
    }

    private String selectedSourceLanguage() {
        return sourceLanguages.get(sourceLanguageSpinner.getSelectedItemPosition()).tag;
    }

    private String selectedTargetLanguage() {
        return targetLanguages.get(targetLanguageSpinner.getSelectedItemPosition()).tag;
    }

    private void showDeterministicPreview() {
        Rect cardBounds = boundsRelativeToRoot(testCard);
        String source = sourceText.getText().toString();
        TextReplacementRules rules = new TextReplacementRules(source + " => " + replacementText.getText().toString());
        GameTextFrameProcessor.Detection detection = new GameTextFrameProcessor.Detection(
                source,
                new Rect(20, 20, Math.max(21, testCard.getWidth() - 20), Math.max(21, testCard.getHeight() - 20))
        );
        processor.reset();
        GameTextFrameProcessor.Frame frame = processor.process(
                java.util.Collections.singletonList(detection),
                cardBounds,
                findViewById(R.id.GameTextTestRoot).getWidth(),
                findViewById(R.id.GameTextTestRoot).getHeight(),
                null,
                rules
        );
        overlayView.setMode(selectedMode());
        overlayView.setFrame(frame);
        statusText.setText(R.string.game_text_preview_ready);
    }

    private GameTextConfig.Mode selectedMode() {
        return modeSpinner.getSelectedItemPosition() == 0
                ? GameTextConfig.Mode.SUBTITLE
                : GameTextConfig.Mode.REPLACE;
    }

    private Rect boundsRelativeToRoot(View view) {
        int[] rootLocation = new int[2];
        int[] viewLocation = new int[2];
        findViewById(R.id.GameTextTestRoot).getLocationOnScreen(rootLocation);
        view.getLocationOnScreen(viewLocation);
        int left = viewLocation[0] - rootLocation[0];
        int top = viewLocation[1] - rootLocation[1];
        return new Rect(left, top, left + view.getWidth(), top + view.getHeight());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (recognizer != null) recognizer.close();
        if (translator != null) translator.close();
        super.onDestroy();
    }
}
