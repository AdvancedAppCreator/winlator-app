package com.winlator.api;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

final class ManagedGame {
    static final String CONTAINER_POLICY_ISOLATED = "isolated";
    static final String CONTAINER_POLICY_SHARED_DEFAULT = "shared_default";
    static final String DEFAULT_SHARED_CONTAINER_KEY = "agm.default";
    static final String CREATION_MODE_PORTABLE = "portable";
    static final String CREATION_MODE_INSTALLER = "installer";
    static final String WIN_VERSION_SOURCE_EXPLICIT = "explicit";
    static final String WIN_VERSION_SOURCE_DEFAULT = "default";
    static final String WIN_VERSION_SOURCE_REGISTRY_LEGACY = "registry_legacy";

    String id;
    String title;
    int containerId;
    String containerPolicy = CONTAINER_POLICY_ISOLATED;
    String containerKey;
    String gamePath;
    String executablePath;
    String executableDosPath;
    String creationMode;
    String creationIdentitySha256;
    String arguments;
    String configJson;
    String winVersionSource;
    String settingsJson;
    String agmMetadata;
    String state;
    long createdAt;
    long updatedAt;

    JSONObject toJSONObject() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("id", id);
        data.put("title", title);
        data.put("containerId", containerId);
        data.put("containerPolicy", containerPolicy);
        putOptional(data, "containerKey", containerKey);
        putOptional(data, "gamePath", gamePath);
        putOptional(data, "executablePath", executablePath);
        putOptional(data, "executableDosPath", executableDosPath);
        putOptional(data, "creationMode", creationMode);
        putOptional(data, "creationIdentitySha256", creationIdentitySha256);
        putOptional(data, "arguments", arguments);
        putOptional(data, "configJson", configJson);
        putOptional(data, "winVersionSource", winVersionSource);
        putOptional(data, "settingsJson", settingsJson);
        if (agmMetadata != null) data.put("agm_metadata", agmMetadata);
        data.put("state", state);
        data.put("createdAt", createdAt);
        data.put("updatedAt", updatedAt);
        return data;
    }

    static ManagedGame fromJSONObject(JSONObject data) throws JSONException {
        ManagedGame game = new ManagedGame();
        game.id = data.getString("id");
        game.title = data.getString("title");
        game.containerId = data.getInt("containerId");
        game.containerPolicy = data.optString(
                "containerPolicy",
                CONTAINER_POLICY_ISOLATED
        );
        game.containerKey = optional(data, "containerKey");
        game.gamePath = optional(data, "gamePath");
        game.executablePath = optional(data, "executablePath");
        game.executableDosPath = optional(data, "executableDosPath");
        game.creationMode = optional(data, "creationMode");
        game.creationIdentitySha256 = optional(data, "creationIdentitySha256");
        game.arguments = optional(data, "arguments");
        game.configJson = optional(data, "configJson");
        game.winVersionSource = optional(data, "winVersionSource");
        game.settingsJson = optional(data, "settingsJson");
        game.agmMetadata = data.has("agm_metadata") ? data.optString("agm_metadata", "") : null;
        game.state = data.optString("state", "setup_required");
        game.createdAt = data.optLong("createdAt", 0);
        game.updatedAt = data.optLong("updatedAt", game.createdAt);
        return game;
    }

    void setCreationIdentity(String installerPath) throws IOException, JSONException {
        creationMode = installerPath == null
                ? CREATION_MODE_PORTABLE
                : CREATION_MODE_INSTALLER;
        creationIdentitySha256 = createIdentitySha256(
                gamePath,
                executablePath,
                executableDosPath,
                installerPath,
                containerPolicy,
                containerKey
        );
    }

    boolean matchesCreateIdentity(
            String requestedGamePath,
            String requestedExecutablePath,
            String requestedExecutableDosPath,
            String requestedInstallerPath,
            String requestedContainerPolicy,
            String requestedContainerKey
    ) throws IOException, JSONException {
        String requestedMode = requestedInstallerPath == null
                ? CREATION_MODE_PORTABLE
                : CREATION_MODE_INSTALLER;
        if (creationIdentitySha256 != null) {
            return creationIdentitySha256.equals(createIdentitySha256(
                    requestedGamePath,
                    requestedExecutablePath,
                    requestedExecutableDosPath,
                    requestedInstallerPath,
                    requestedContainerPolicy,
                    requestedContainerKey
            ));
        }

        String storedMode = creationMode;
        if (storedMode == null) {
            storedMode = executablePath != null && executableDosPath == null
                    ? CREATION_MODE_PORTABLE
                    : CREATION_MODE_INSTALLER;
        }
        if (!storedMode.equals(requestedMode)) return false;
        if (!Objects.equals(containerPolicy, requestedContainerPolicy)) return false;
        if (!Objects.equals(
                effectiveContainerKey(containerPolicy, containerKey),
                effectiveContainerKey(requestedContainerPolicy, requestedContainerKey)
        )) return false;
        if (!sameUnixPath(gamePath, requestedGamePath)) return false;
        if (!sameUnixPath(executablePath, requestedExecutablePath)) return false;
        if (!sameDosPath(executableDosPath, requestedExecutableDosPath)) return false;
        return CREATION_MODE_PORTABLE.equals(requestedMode);
    }

    private static String createIdentitySha256(
            String gamePath,
            String executablePath,
            String executableDosPath,
            String installerPath,
            String containerPolicy,
            String containerKey
    ) throws IOException, JSONException {
        JSONObject identity = new JSONObject();
        identity.put("mode", installerPath == null
                ? CREATION_MODE_PORTABLE
                : CREATION_MODE_INSTALLER);
        identity.put("gamePath", normalizedUnixPath(gamePath));
        identity.put("executablePath", normalizedUnixPath(executablePath));
        identity.put("executableDosPath", executableDosPath == null
                ? JSONObject.NULL
                : normalizeDosPath(executableDosPath).toLowerCase(Locale.US));
        identity.put("installerPath", normalizedUnixPath(installerPath));
        identity.put("containerPolicy", containerPolicy);
        identity.put(
                "containerKey",
                effectiveContainerKey(containerPolicy, containerKey)
        );
        return GameConfigSchema.hash(identity);
    }

    private static String effectiveContainerKey(String policy, String key) {
        return CONTAINER_POLICY_SHARED_DEFAULT.equals(policy) && key == null
                ? DEFAULT_SHARED_CONTAINER_KEY
                : key;
    }

    private static boolean sameUnixPath(String first, String second) throws IOException {
        if (first == null || second == null) return first == null && second == null;
        return normalizedUnixPath(first).equals(normalizedUnixPath(second));
    }

    private static Object normalizedUnixPath(String path) throws IOException {
        return path == null ? JSONObject.NULL : new File(path).getCanonicalPath();
    }

    private static boolean sameDosPath(String first, String second) {
        if (first == null || second == null) return first == null && second == null;
        return normalizeDosPath(first).equalsIgnoreCase(normalizeDosPath(second));
    }

    private static String normalizeDosPath(String path) {
        String normalized = path.replace('/', '\\');
        while (normalized.length() > 3 && normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void putOptional(JSONObject data, String name, String value) throws JSONException {
        if (value != null && !value.isEmpty()) data.put(name, value);
    }

    private static String optional(JSONObject data, String name) {
        String value = data.optString(name, "");
        return value.isEmpty() ? null : value;
    }
}
