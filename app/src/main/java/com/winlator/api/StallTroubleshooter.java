package com.winlator.api;

import com.winlator.core.EnvVars;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evidence-driven troubleshooter. Ties the cause classifier, the evidence extractor, and the
 * flowable knowledge base together, tracks progress via {@link StallAttemptStore}, and emits
 * an actionable diagnosis with the next remedy in the ladder. Advancing across restarts,
 * anti-loop skipping, and terminal (exhausted) handling all live here. {@link #buildPlan} is
 * a pure function so the escalation logic is unit tested independently of Android.
 */
final class StallTroubleshooter {
    private static final long RESOLVE_SETTLE_MILLIS = 15000;

    static final class Plan {
        final StallKnowledgeBase.Step recommended;
        final List<StallKnowledgeBase.Step> remaining;
        final boolean terminal;
        final int triedCount;
        final boolean hasFlows;
        final List<String> flowIds;

        Plan(StallKnowledgeBase.Step recommended, List<StallKnowledgeBase.Step> remaining,
             boolean terminal, int triedCount, boolean hasFlows, List<String> flowIds) {
            this.recommended = recommended;
            this.remaining = remaining;
            this.terminal = terminal;
            this.triedCount = triedCount;
            this.hasFlows = hasFlows;
            this.flowIds = flowIds;
        }
    }

    static final class Result {
        enum Kind { NONE, STALL, RESOLVED }

        final Kind kind;
        final JSONObject diagnosis;
        final String resolvedStepId;
        final String cause;

        private Result(Kind kind, JSONObject diagnosis, String resolvedStepId, String cause) {
            this.kind = kind;
            this.diagnosis = diagnosis;
            this.resolvedStepId = resolvedStepId;
            this.cause = cause;
        }

        static Result none() {
            return new Result(Kind.NONE, null, null, null);
        }

        static Result stall(JSONObject diagnosis) {
            return new Result(Kind.STALL, diagnosis, null, diagnosis.optString("cause", ""));
        }

        static Result resolved(String stepId, String cause) {
            return new Result(Kind.RESOLVED, null, stepId, cause);
        }
    }

    private final String gameId;
    private final StallKnowledgeBase knowledgeBase;
    private final StallAttemptStore attemptStore;
    private final StallThresholds thresholds;
    private final String gpu;

    StallTroubleshooter(String gameId, StallKnowledgeBase knowledgeBase,
                        StallAttemptStore attemptStore, StallThresholds thresholds, String gpu) {
        this.gameId = gameId;
        this.knowledgeBase = knowledgeBase;
        this.attemptStore = attemptStore;
        this.thresholds = thresholds;
        this.gpu = gpu;
    }

    Result evaluate(StallSignals signals) throws JSONException {
        JSONObject base = ManagedStallClassifier.classify(signals, thresholds);
        boolean stalled = base.optBoolean("stalled", false);

        if (!stalled) {
            String pending = attemptStore.pendingStepId(gameId);
            boolean settledHealthy = signals.runtimeReached
                    && signals.elapsedMillis() >= RESOLVE_SETTLE_MILLIS
                    && isFramesFresh(signals);
            if (pending != null
                    && attemptStore.pendingAppliedAt(gameId) < signals.startedAt
                    && settledHealthy) {
                return Result.resolved(pending, attemptStore.pendingCause(gameId));
            }
            return Result.none();
        }

        String cause = base.optString("cause", "unknown");
        attemptStore.promoteStalePending(gameId, signals.startedAt);

        StallEvidence evidence = StallEvidence.from(cause, signals, gpu);
        List<StallKnowledgeBase.Flow> matched = knowledgeBase.match(evidence);
        Set<String> tried = attemptStore.triedStepIds(gameId);
        Plan plan = buildPlan(matched, evidence.config, tried);

        JSONObject diagnosis = new JSONObject()
                .put("classificationVersion", ManagedStallClassifier.CLASSIFICATION_VERSION)
                .put("stalled", true)
                .put("cause", cause)
                .put("confidence", base.optString("confidence", "low"))
                .put("evidence", evidence.toJson());

        JSONObject flow = new JSONObject()
                .put("triedCount", plan.triedCount)
                .put("terminal", plan.terminal)
                .put("flowIds", new JSONArray(plan.flowIds));
        JSONArray remaining = new JSONArray();
        for (StallKnowledgeBase.Step step : plan.remaining) remaining.put(step.title);
        flow.put("remaining", remaining);
        diagnosis.put("flow", flow);

        String recommendedStepId = "";
        if (plan.recommended != null) {
            diagnosis.put("title", base.optString("title", "The game may be stuck"));
            diagnosis.put("message", base.optString("message", ""));
            JSONArray suggestions = new JSONArray();
            suggestions.put(suggestionFor(
                    plan.recommended, evidence.config, signals.appliedConfigSha256));
            diagnosis.put("suggestions", suggestions);
            recommendedStepId = plan.recommended.id;
        }
        else if (!plan.hasFlows) {
            diagnosis.put("title", base.optString("title", "The game may be stuck"));
            diagnosis.put("message", base.optString("message", ""));
            diagnosis.put("suggestions", base.optJSONArray("suggestions") != null
                    ? base.optJSONArray("suggestions") : new JSONArray());
            recommendedStepId = firstSuggestionId(base);
        }
        else {
            diagnosis.put("title", "No automatic fix worked");
            diagnosis.put("message", "None of the known fixes resolved the black screen for "
                    + "this game. You can adjust graphics or Box64 settings manually, or "
                    + "close the game.");
            diagnosis.put("suggestions", new JSONArray());
            recommendedStepId = "terminal";
        }

        diagnosis.put("dedupeKey", cause + ":" + recommendedStepId + ":" + plan.terminal);
        return Result.stall(diagnosis);
    }

    private boolean isFramesFresh(StallSignals signals) {
        long frameSilence = signals.frameSilenceMillis();
        return frameSilence >= 0 && frameSilence < thresholds.frameSilenceMillis;
    }

    static Plan buildPlan(List<StallKnowledgeBase.Flow> flows, JSONObject config,
                          Set<String> tried) {
        LinkedHashMap<String, StallKnowledgeBase.Step> unique = new LinkedHashMap<>();
        List<String> flowIds = new ArrayList<>();
        for (StallKnowledgeBase.Flow flow : flows) {
            flowIds.add(flow.id);
            for (StallKnowledgeBase.Step step : flow.steps) {
                if (!unique.containsKey(step.id)) unique.put(step.id, step);
            }
        }

        List<StallKnowledgeBase.Step> candidates = new ArrayList<>();
        for (StallKnowledgeBase.Step step : unique.values()) {
            if (tried.contains(step.id)) continue;
            if (satisfiedByConfig(step, config)) continue;
            candidates.add(step);
        }

        StallKnowledgeBase.Step recommended = candidates.isEmpty() ? null : candidates.get(0);
        List<StallKnowledgeBase.Step> remaining = candidates.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(candidates.subList(1, candidates.size()));
        int triedCount = unique.size() - candidates.size();
        boolean terminal = recommended == null && !unique.isEmpty();
        return new Plan(recommended, remaining, terminal, triedCount, !flows.isEmpty(), flowIds);
    }

    private static boolean satisfiedByConfig(StallKnowledgeBase.Step step, JSONObject config) {
        if (step.skipIfConfig.length() == 0) return false;
        for (Iterator<String> keys = step.skipIfConfig.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!configContains(config, key, step.skipIfConfig.optString(key))) return false;
        }
        return true;
    }

    private static boolean configContains(JSONObject config, String key, String value) {
        String current = config.optString(key, "").toLowerCase(Locale.ENGLISH);
        return current.contains(value == null ? "" : value.toLowerCase(Locale.ENGLISH));
    }

    private static JSONObject suggestionFor(StallKnowledgeBase.Step step, JSONObject config,
                                            String baseHash) throws JSONException {
        JSONObject set = new JSONObject();
        for (Iterator<String> keys = step.set.keys(); keys.hasNext(); ) {
            String key = keys.next();
            set.put(key, step.set.get(key));
        }
        if (step.env.length() > 0) {
            EnvVars envVars = new EnvVars(config.optString("envVars", ""));
            for (Iterator<String> keys = step.env.keys(); keys.hasNext(); ) {
                String key = keys.next();
                envVars.put(key, step.env.optString(key));
            }
            set.put("envVars", envVars.toString());
        }
        return new JSONObject()
                .put("id", step.id)
                .put("stepId", step.id)
                .put("title", step.title)
                .put("rationale", step.rationale)
                .put("baseConfigSha256", baseHash != null ? baseHash : "")
                .put("set", set);
    }

    private static String firstSuggestionId(JSONObject base) {
        JSONArray suggestions = base.optJSONArray("suggestions");
        if (suggestions != null && suggestions.length() > 0) {
            JSONObject first = suggestions.optJSONObject(0);
            if (first != null) return first.optString("id", "");
        }
        return "";
    }
}
