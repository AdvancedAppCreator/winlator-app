package com.winlator.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Non-blocking in-session overlay that surfaces a realtime black-screen / hang diagnosis
 * and offers one-tap fixes. The overlay itself is transparent to touches so game input
 * still passes through; only the diagnosis card and its buttons consume events.
 */
public class SessionStallOverlayView extends FrameLayout {
    public interface Listener {
        void onApplySuggestion(JSONObject suggestion);

        void onKeepWaiting();

        void onCloseGame();
    }

    private final Listener listener;
    private final LinearLayout card;
    private final TextView titleView;
    private final TextView messageView;
    private final TextView evidenceView;
    private final TextView remainingView;
    private final LinearLayout actionsContainer;

    public SessionStallOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClickable(false);
        setFocusable(false);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF01B1B1F);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), 0xFF3A3A44);
        card.setBackground(background);

        TextView heading = new TextView(context);
        heading.setText(R.string.stall_overlay_heading);
        heading.setTextColor(0xFFB0B0C0);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        heading.setAllCaps(true);
        card.addView(heading);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setPadding(0, dp(4), 0, dp(6));
        card.addView(titleView);

        messageView = new TextView(context);
        messageView.setTextColor(0xFFD0D0D8);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        messageView.setLineSpacing(dp(2), 1.0f);
        card.addView(messageView);

        evidenceView = new TextView(context);
        evidenceView.setTextColor(0xFF7E8AA0);
        evidenceView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams evidenceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        evidenceParams.topMargin = dp(8);
        card.addView(evidenceView, evidenceParams);

        actionsContainer = new LinearLayout(context);
        actionsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionsParams.topMargin = dp(12);
        card.addView(actionsContainer, actionsParams);

        remainingView = new TextView(context);
        remainingView.setTextColor(0xFF8A8A96);
        remainingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams remainingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        remainingParams.topMargin = dp(10);
        remainingParams.leftMargin = dp(4);
        card.addView(remainingView, remainingParams);

        ScrollView scroller = new ScrollView(context);
        scroller.setClickable(false);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dp(360),
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardParams.topMargin = dp(24);
        cardParams.leftMargin = dp(16);
        cardParams.rightMargin = dp(16);
        scroller.addView(card);
        addView(scroller, cardParams);

        setVisibility(GONE);
    }

    public void showDiagnosis(JSONObject diagnosis) {
        if (diagnosis == null) return;
        titleView.setText(diagnosis.optString(
                "title", getContext().getString(R.string.stall_overlay_default_title)));
        messageView.setText(diagnosis.optString("message", ""));
        renderEvidence(diagnosis.optJSONObject("evidence"));
        actionsContainer.removeAllViews();

        JSONArray suggestions = diagnosis.optJSONArray("suggestions");
        if (suggestions != null) {
            for (int index = 0; index < suggestions.length(); index++) {
                JSONObject suggestion = suggestions.optJSONObject(index);
                if (suggestion == null) continue;
                addPrimaryAction(
                        suggestion.optString("title", getContext()
                                .getString(R.string.stall_overlay_apply_fix)),
                        suggestion.optString("rationale", ""),
                        view -> listener.onApplySuggestion(suggestion)
                );
            }
        }

        renderRemaining(diagnosis.optJSONObject("flow"));

        addSecondaryAction(
                getContext().getString(R.string.stall_overlay_keep_waiting),
                view -> {
                    setVisibility(GONE);
                    listener.onKeepWaiting();
                }
        );
        addSecondaryAction(
                getContext().getString(R.string.stall_overlay_close_game),
                view -> listener.onCloseGame()
        );

        setVisibility(VISIBLE);
        bringToFront();
    }

    private void renderEvidence(JSONObject evidence) {
        if (evidence == null) {
            evidenceView.setVisibility(GONE);
            return;
        }
        StringBuilder summary = new StringBuilder();
        appendFact(summary, "GPU", shortGpu(evidence.optString("gpu", "")));
        appendFact(summary, "driver", evidence.optString("graphicsDriver", ""));
        appendFact(summary, "dx", evidence.optString("dxwrapper", ""));
        appendFact(summary, "box64", evidence.optString("box64Preset", ""));
        if (summary.length() == 0) {
            evidenceView.setVisibility(GONE);
        }
        else {
            evidenceView.setText(summary.toString());
            evidenceView.setVisibility(VISIBLE);
        }
    }

    private void renderRemaining(JSONObject flow) {
        if (flow == null) {
            remainingView.setVisibility(GONE);
            return;
        }
        JSONArray remaining = flow.optJSONArray("remaining");
        int triedCount = flow.optInt("triedCount", 0);
        StringBuilder text = new StringBuilder();
        if (triedCount > 0) {
            text.append(getContext().getString(
                    R.string.stall_overlay_tried_count, triedCount));
        }
        if (remaining != null && remaining.length() > 0) {
            if (text.length() > 0) text.append('\n');
            text.append(getContext().getString(R.string.stall_overlay_next_steps));
            for (int index = 0; index < remaining.length() && index < 3; index++) {
                text.append("\n• ").append(remaining.optString(index, ""));
            }
        }
        if (text.length() == 0) {
            remainingView.setVisibility(GONE);
        }
        else {
            remainingView.setText(text.toString());
            remainingView.setVisibility(VISIBLE);
        }
    }

    private void appendFact(StringBuilder builder, String label, String value) {
        if (value == null || value.isEmpty()) return;
        if (builder.length() > 0) builder.append("  ·  ");
        builder.append(label).append(' ').append(value);
    }

    private String shortGpu(String gpu) {
        if (gpu == null || gpu.isEmpty()) return "";
        String trimmed = gpu.replace("(tm)", "").replace("(r)", "").trim();
        return trimmed.length() > 28 ? trimmed.substring(0, 28) : trimmed;
    }

    public void hide() {
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void setActionsEnabled(boolean enabled) {
        for (int index = 0; index < actionsContainer.getChildCount(); index++) {
            actionsContainer.getChildAt(index).setEnabled(enabled);
        }
    }

    private void addPrimaryAction(String title, String rationale, OnClickListener onClick) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF2E6BE6);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        button.setOnClickListener(onClick);
        actionsContainer.addView(button, params);

        if (rationale != null && !rationale.isEmpty()) {
            TextView caption = new TextView(getContext());
            caption.setText(rationale);
            caption.setTextColor(0xFF9A9AA6);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            captionParams.topMargin = dp(2);
            captionParams.leftMargin = dp(4);
            actionsContainer.addView(caption, captionParams);
        }
    }

    private void addSecondaryAction(String title, OnClickListener onClick) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(0xFFC8C8D2);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x00000000);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), 0xFF44444E);
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        button.setOnClickListener(onClick);
        actionsContainer.addView(button, params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
