package com.winlator.api.dependency;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuntimeDependencyBootstrapRecord {
    public final int planVersion;
    public final int containerId;
    public final RuntimeDependencyBootstrapState state;
    public final List<String> selectedPackageIds;
    public final List<String> completedPackageIds;
    public final String currentPackageId;
    public final String originalDrives;
    public final String lastError;
    public final long updatedAt;

    private RuntimeDependencyBootstrapRecord(
            int planVersion,
            int containerId,
            RuntimeDependencyBootstrapState state,
            List<String> selectedPackageIds,
            List<String> completedPackageIds,
            String currentPackageId,
            String originalDrives,
            String lastError,
            long updatedAt
    ) {
        this.planVersion = planVersion;
        this.containerId = containerId;
        this.state = state;
        this.selectedPackageIds = Collections.unmodifiableList(
                new ArrayList<>(selectedPackageIds)
        );
        this.completedPackageIds = Collections.unmodifiableList(
                new ArrayList<>(completedPackageIds)
        );
        this.currentPackageId = currentPackageId;
        this.originalDrives = originalDrives;
        this.lastError = lastError;
        this.updatedAt = updatedAt;
    }

    public static RuntimeDependencyBootstrapRecord create(int planVersion, int containerId) {
        return new RuntimeDependencyBootstrapRecord(
                planVersion,
                containerId,
                RuntimeDependencyBootstrapState.NEEDS_SELECTION,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis()
        );
    }

    public RuntimeDependencyBootstrapRecord select(List<String> packageIds) {
        return new RuntimeDependencyBootstrapRecord(
                planVersion,
                containerId,
                RuntimeDependencyBootstrapState.DOWNLOADING,
                packageIds,
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis()
        );
    }

    public RuntimeDependencyBootstrapRecord withState(
            RuntimeDependencyBootstrapState nextState,
            String error
    ) {
        return new RuntimeDependencyBootstrapRecord(
                planVersion,
                containerId,
                nextState,
                selectedPackageIds,
                completedPackageIds,
                nextState == RuntimeDependencyBootstrapState.INSTALLING
                        ? currentPackageId
                        : null,
                originalDrives,
                error,
                System.currentTimeMillis()
        );
    }

    public RuntimeDependencyBootstrapRecord startInstall(
            String packageId,
            String drives
    ) {
        return new RuntimeDependencyBootstrapRecord(
                planVersion,
                containerId,
                RuntimeDependencyBootstrapState.INSTALLING,
                selectedPackageIds,
                completedPackageIds,
                packageId,
                originalDrives != null ? originalDrives : drives,
                null,
                System.currentTimeMillis()
        );
    }

    public RuntimeDependencyBootstrapRecord completeCurrent() {
        ArrayList<String> completed = new ArrayList<>(completedPackageIds);
        if (currentPackageId != null && !completed.contains(currentPackageId)) {
            completed.add(currentPackageId);
        }
        RuntimeDependencyBootstrapState nextState =
                completed.containsAll(selectedPackageIds)
                        ? RuntimeDependencyBootstrapState.COMPLETE
                        : RuntimeDependencyBootstrapState.READY_TO_INSTALL;
        return new RuntimeDependencyBootstrapRecord(
                planVersion,
                containerId,
                nextState,
                selectedPackageIds,
                completed,
                null,
                originalDrives,
                null,
                System.currentTimeMillis()
        );
    }

    public RuntimeDependencyBootstrapRecord fail(String error) {
        return new RuntimeDependencyBootstrapRecord(
                planVersion,
                containerId,
                RuntimeDependencyBootstrapState.FAILED,
                selectedPackageIds,
                completedPackageIds,
                null,
                originalDrives,
                error,
                System.currentTimeMillis()
        );
    }

    public String nextPackageId() {
        for (String packageId : selectedPackageIds) {
            if (!completedPackageIds.contains(packageId)) return packageId;
        }
        return null;
    }

    JSONObject toJSON() throws JSONException {
        JSONObject data = new JSONObject()
                .put("planVersion", planVersion)
                .put("containerId", containerId)
                .put("state", state.name())
                .put("selectedPackageIds", toArray(selectedPackageIds))
                .put("completedPackageIds", toArray(completedPackageIds))
                .put("updatedAt", updatedAt);
        if (currentPackageId != null) data.put("currentPackageId", currentPackageId);
        if (originalDrives != null) data.put("originalDrives", originalDrives);
        if (lastError != null) data.put("lastError", lastError);
        return data;
    }

    static RuntimeDependencyBootstrapRecord fromJSON(JSONObject data) throws JSONException {
        return new RuntimeDependencyBootstrapRecord(
                data.getInt("planVersion"),
                data.getInt("containerId"),
                RuntimeDependencyBootstrapState.fromKey(data.optString("state", null)),
                fromArray(data.optJSONArray("selectedPackageIds")),
                fromArray(data.optJSONArray("completedPackageIds")),
                optional(data, "currentPackageId"),
                data.has("originalDrives") ? data.getString("originalDrives") : null,
                optional(data, "lastError"),
                data.optLong("updatedAt", 0)
        );
    }

    private static JSONArray toArray(List<String> values) {
        JSONArray result = new JSONArray();
        for (String value : values) result.put(value);
        return result;
    }

    private static List<String> fromArray(JSONArray values) throws JSONException {
        ArrayList<String> result = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                result.add(values.getString(index));
            }
        }
        return result;
    }

    private static String optional(JSONObject data, String key) {
        String value = data.optString(key, "");
        return value.isEmpty() ? null : value;
    }
}
