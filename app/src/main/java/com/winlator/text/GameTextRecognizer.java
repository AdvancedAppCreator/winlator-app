package com.winlator.text;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

public final class GameTextRecognizer implements Closeable {
    public interface Listener {
        void onRecognized(List<GameTextFrameProcessor.Detection> detections);
        void onFailure(Exception error);
    }

    private final Object lock = new Object();
    private TextRecognizer recognizer;
    private int pendingRequests;
    private boolean closeRequested;

    public GameTextRecognizer(GameTextConfig.Script script) {
        GameTextConfig.Script requested = script == null ? GameTextConfig.Script.LATIN : script;
        switch (requested) {
            case CHINESE:
                recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                break;
            case DEVANAGARI:
                recognizer = TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
                break;
            case JAPANESE:
                recognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
                break;
            case KOREAN:
                recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                break;
            case LATIN:
            default:
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                break;
        }
    }

    public void recognize(Bitmap bitmap, Listener listener) {
        TextRecognizer activeRecognizer;
        synchronized (lock) {
            if (recognizer == null || closeRequested) {
                listener.onFailure(new IllegalStateException("Text recognizer is closed"));
                return;
            }
            pendingRequests++;
            activeRecognizer = recognizer;
        }

        try {
            activeRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(result -> listener.onRecognized(toDetections(result)))
                    .addOnFailureListener(listener::onFailure)
                    .addOnCompleteListener(task -> finishRequest());
        }
        catch (RuntimeException error) {
            finishRequest();
            listener.onFailure(error);
        }
    }

    private static List<GameTextFrameProcessor.Detection> toDetections(Text result) {
        ArrayList<GameTextFrameProcessor.Detection> detections = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect bounds = line.getBoundingBox();
                if (bounds != null) detections.add(new GameTextFrameProcessor.Detection(line.getText(), bounds));
            }
        }
        return detections;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeRequested = true;
            if (pendingRequests == 0) closeRecognizer();
        }
    }

    private void finishRequest() {
        synchronized (lock) {
            pendingRequests--;
            if (closeRequested && pendingRequests == 0) closeRecognizer();
        }
    }

    private void closeRecognizer() {
        if (recognizer == null) return;
        recognizer.close();
        recognizer = null;
    }
}
