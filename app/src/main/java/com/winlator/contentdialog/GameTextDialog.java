package com.winlator.contentdialog;

import android.content.Intent;
import android.graphics.RectF;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.TextCapabilitiesTestActivity;
import com.winlator.XServerDisplayActivity;
import com.winlator.text.GameTextLanguage;
import com.winlator.text.GameTextConfig;
import com.winlator.text.GameTextController;
import com.winlator.text.GameTextTranslator;

import java.util.List;

public final class GameTextDialog extends ContentDialog {
    private static final long[] INTERVAL_VALUES = {500, 750, 1000, 1500, 2000};

    private final XServerDisplayActivity activity;
    private final GameTextController controller;
    private final Spinner modeSpinner;
    private final Spinner sourceLanguageSpinner;
    private final Spinner targetLanguageSpinner;
    private final Spinner intervalSpinner;
    private final EditText replacements;
    private final TextView translationStatus;
    private final List<GameTextLanguage.Option> sourceLanguages = GameTextLanguage.sourceOptions();
    private final List<GameTextLanguage.Option> targetLanguages = GameTextLanguage.targetOptions();

    public GameTextDialog(XServerDisplayActivity activity, GameTextController controller) {
        super(activity, R.layout.game_text_dialog);
        this.activity = activity;
        this.controller = controller;
        setTitle(R.string.game_text);
        setIcon(R.drawable.icon_text);

        GameTextConfig config = controller.getConfig();
        modeSpinner = findViewById(R.id.SGameTextMode);
        sourceLanguageSpinner = findViewById(R.id.SGameTextSourceLanguage);
        targetLanguageSpinner = findViewById(R.id.SGameTextTargetLanguage);
        intervalSpinner = findViewById(R.id.SGameTextInterval);
        replacements = findViewById(R.id.ETGameTextReplacements);
        translationStatus = findViewById(R.id.TVGameTextTranslationStatus);

        modeSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{
                activity.getString(R.string.disabled),
                activity.getString(R.string.subtitle_bar),
                activity.getString(R.string.visual_replacement)
        }));
        sourceLanguageSpinner.setAdapter(new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                sourceLanguages
        ));
        targetLanguageSpinner.setAdapter(new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                targetLanguages
        ));
        intervalSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "0.5 s", "0.75 s", "1.0 s", "1.5 s", "2.0 s"
        }));

        modeSpinner.setSelection(config.mode.ordinal());
        sourceLanguageSpinner.setSelection(GameTextLanguage.findOption(sourceLanguages, config.sourceLanguage));
        targetLanguageSpinner.setSelection(GameTextLanguage.findOption(targetLanguages, config.targetLanguage));
        intervalSpinner.setSelection(findInterval(config.intervalMillis));
        replacements.setText(config.replacements);

        findViewById(R.id.BTSelectGameTextRegion).setOnClickListener(view -> {
            save();
            dismiss();
            activity.beginGameTextRegionSelection();
        });
        findViewById(R.id.BTResetGameTextRegion).setOnClickListener(view -> {
            GameTextConfig current = selections(GameTextConfig.defaultRegion());
            controller.saveConfig(current);
        });
        findViewById(R.id.BTPreviewGameText).setOnClickListener(view -> {
            save();
            controller.showPreview();
        });
        findViewById(R.id.BTOpenGameTextTest).setOnClickListener(view -> {
            save();
            activity.startActivity(new Intent(activity, TextCapabilitiesTestActivity.class));
        });
        findViewById(R.id.BTPrepareGameTextTranslation).setOnClickListener(view -> {
            GameTextConfig selected = selections(controller.getConfig().captureRegion);
            controller.saveConfig(selected);
            translationStatus.setText(R.string.game_text_preparing_translation);
            controller.prepareTranslationModels(selected, new GameTextTranslator.ModelListener() {
                @Override
                public void onReady() {
                    translationStatus.setText(R.string.game_text_translation_ready);
                }

                @Override
                public void onFailure(Exception error) {
                    translationStatus.setText(getContext().getString(
                            R.string.game_text_translation_prepare_failed,
                            error.getMessage()
                    ));
                }
            });
        });
        setOnConfirmCallback(this::save);
    }

    private void save() {
        controller.saveConfig(selections(controller.getConfig().captureRegion));
        activity.updateGameTextButton();
    }

    private GameTextConfig selections(RectF region) {
        return new GameTextConfig(
                GameTextConfig.Mode.values()[modeSpinner.getSelectedItemPosition()],
                INTERVAL_VALUES[intervalSpinner.getSelectedItemPosition()],
                region,
                replacements.getText().toString(),
                sourceLanguages.get(sourceLanguageSpinner.getSelectedItemPosition()).tag,
                targetLanguages.get(targetLanguageSpinner.getSelectedItemPosition()).tag
        );
    }

    private static int findInterval(long interval) {
        int best = 0;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            long currentDistance = Math.abs(INTERVAL_VALUES[i] - interval);
            if (currentDistance < distance) {
                best = i;
                distance = currentDistance;
            }
        }
        return best;
    }
}
