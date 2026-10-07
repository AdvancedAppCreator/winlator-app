package com.winlator.api;

import android.content.Context;

import com.winlator.core.StartupLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The stall-remedy knowledge base: a versioned set of "flows" (ordered remedy ladders) that
 * the troubleshooter matches against {@link StallEvidence}. Loaded from a bundled asset, with
 * an optional on-disk override placed in {@code filesDir} (see {@link #OVERRIDE_RELATIVE_PATH}) so
 * the dictionary can grow without an APK rebuild. Parsing is separated from I/O so it is unit testable.
 */
final class StallKnowledgeBase {
    static final String ASSET_PATH = "diagnostics/stall_solutions.json";
    static final String OVERRIDE_RELATIVE_PATH = "diagnostics/stall_solutions.json";

    static final class Step {
        final String id;
        final String title;
        final String rationale;
        final JSONObject set;
        final JSONObject env;
        final JSONObject skipIfConfig;

        Step(String id, String title, String rationale, JSONObject set, JSONObject env,
             JSONObject skipIfConfig) {
            this.id = id;
            this.title = title;
            this.rationale = rationale;
            this.set = set;
            this.env = env;
            this.skipIfConfig = skipIfConfig;
        }
    }

    static final class Flow {
        final String id;
        final int priority;
        final Set<String> causes;
        final List<String> anyLog;
        final List<String> gpuContains;
        final JSONObject configEquals;
        final JSONObject configNot;
        final List<Step> steps;

        Flow(String id, int priority, Set<String> causes, List<String> anyLog,
             List<String> gpuContains, JSONObject configEquals, JSONObject configNot,
             List<Step> steps) {
            this.id = id;
            this.priority = priority;
            this.causes = causes;
            this.anyLog = anyLog;
            this.gpuContains = gpuContains;
            this.configEquals = configEquals;
            this.configNot = configNot;
            this.steps = steps;
        }

        boolean matches(StallEvidence evidence) {
            if (!causes.isEmpty() && !causes.contains(evidence.cause)) return false;
            if (!anyLog.isEmpty()) {
                boolean hit = false;
                for (String token : anyLog) {
                    if (evidence.logContains(token)) { hit = true; break; }
                }
                if (!hit) return false;
            }
            if (!gpuContains.isEmpty()) {
                boolean hit = false;
                for (String token : gpuContains) {
                    if (evidence.gpuContains(token)) { hit = true; break; }
                }
                if (!hit) return false;
            }
            for (Iterator<String> keys = configEquals.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!evidence.configContains(key, configEquals.optString(key))) return false;
            }
            for (Iterator<String> keys = configNot.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (evidence.configContains(key, configNot.optString(key))) return false;
            }
            return true;
        }
    }

    final int version;
    final List<Flow> flows;

    private StallKnowledgeBase(int version, List<Flow> flows) {
        this.version = version;
        this.flows = flows;
    }

    static StallKnowledgeBase empty() {
        return new StallKnowledgeBase(0, Collections.emptyList());
    }

    /** Flows whose match conditions are satisfied by the evidence, highest priority first. */
    List<Flow> match(StallEvidence evidence) {
        List<Flow> matched = new ArrayList<>();
        for (Flow flow : flows) {
            if (flow.matches(evidence)) matched.add(flow);
        }
        Collections.sort(matched, new Comparator<Flow>() {
            @Override
            public int compare(Flow a, Flow b) {
                return Integer.compare(b.priority, a.priority);
            }
        });
        return matched;
    }

    static StallKnowledgeBase load(Context context) {
        try {
            File override = new File(context.getFilesDir(), OVERRIDE_RELATIVE_PATH);
            if (override.isFile()) {
                try (InputStream stream = new FileInputStream(override)) {
                    return parse(new JSONObject(readAll(stream)));
                }
            }
        }
        catch (JSONException | IOException error) {
            StartupLog.log("Unable to load stall KB override; using bundled default", error);
        }
        try (InputStream stream = context.getAssets().open(ASSET_PATH)) {
            return parse(new JSONObject(readAll(stream)));
        }
        catch (JSONException | IOException error) {
            StartupLog.log("Unable to load bundled stall KB", error);
            return empty();
        }
    }

    static StallKnowledgeBase parse(JSONObject root) throws JSONException {
        int version = root.optInt("version", 1);
        List<Flow> flows = new ArrayList<>();
        JSONArray flowArray = root.optJSONArray("flows");
        if (flowArray != null) {
            for (int index = 0; index < flowArray.length(); index++) {
                JSONObject flowJson = flowArray.optJSONObject(index);
                if (flowJson == null) continue;
                Flow flow = parseFlow(flowJson);
                if (flow != null) flows.add(flow);
            }
        }
        return new StallKnowledgeBase(version, flows);
    }

    private static Flow parseFlow(JSONObject flowJson) {
        String id = flowJson.optString("id", "");
        if (id.isEmpty()) return null;
        JSONObject match = flowJson.optJSONObject("match");
        if (match == null) match = new JSONObject();

        Set<String> causes = new HashSet<>(stringList(match.optJSONArray("causes")));
        List<String> anyLog = stringList(match.optJSONArray("anyLog"));
        List<String> gpuContains = stringList(match.optJSONArray("gpuContains"));
        JSONObject configEquals = match.optJSONObject("configEquals");
        JSONObject configNot = match.optJSONObject("configNot");

        List<Step> steps = new ArrayList<>();
        JSONArray stepArray = flowJson.optJSONArray("steps");
        if (stepArray != null) {
            for (int index = 0; index < stepArray.length(); index++) {
                JSONObject stepJson = stepArray.optJSONObject(index);
                if (stepJson == null) continue;
                String stepId = stepJson.optString("id", "");
                if (stepId.isEmpty()) continue;
                steps.add(new Step(
                        stepId,
                        stepJson.optString("title", "Apply fix"),
                        stepJson.optString("rationale", ""),
                        stepJson.optJSONObject("set") != null
                                ? stepJson.optJSONObject("set") : new JSONObject(),
                        stepJson.optJSONObject("env") != null
                                ? stepJson.optJSONObject("env") : new JSONObject(),
                        stepJson.optJSONObject("skipIfConfig") != null
                                ? stepJson.optJSONObject("skipIfConfig") : new JSONObject()
                ));
            }
        }
        if (steps.isEmpty()) return null;
        return new Flow(
                id,
                flowJson.optInt("priority", 50),
                causes,
                anyLog,
                gpuContains,
                configEquals != null ? configEquals : new JSONObject(),
                configNot != null ? configNot : new JSONObject(),
                steps
        );
    }

    private static List<String> stringList(JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "");
                if (!value.isEmpty()) list.add(value);
            }
        }
        return list;
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) builder.append(buffer, 0, read);
        }
        return builder.toString();
    }
}
