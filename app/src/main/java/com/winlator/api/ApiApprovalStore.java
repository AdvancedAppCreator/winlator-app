package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.winlator.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

final class ApiApprovalStore {
    private static final String PREFERENCES = "api_approvals";
    private static final String KEY_STORE = "store_v1";
    private static final int SCHEMA_VERSION = 1;
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    ApiApprovalStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    ApiApprovalEntry get(String packageName) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            JSONArray approvals = root.getJSONArray("approvals");
            for (int index = 0; index < approvals.length(); index++) {
                ApiApprovalEntry entry = ApiApprovalEntry.fromJSONObject(
                        approvals.getJSONObject(index)
                );
                if (entry.packageName.equals(packageName)) return entry;
            }
            return null;
        }
    }

    ArrayList<ApiApprovalEntry> list() throws JSONException, IOException {
        synchronized (LOCK) {
            JSONArray approvals = loadRoot().getJSONArray("approvals");
            ArrayList<ApiApprovalEntry> result = new ArrayList<>();
            for (int index = 0; index < approvals.length(); index++) {
                result.add(ApiApprovalEntry.fromJSONObject(
                        approvals.getJSONObject(index)
                ));
            }
            result.sort((first, second) ->
                    first.packageName.compareToIgnoreCase(second.packageName));
            return result;
        }
    }

    void approve(
            String packageName,
            Set<String> certificates,
            Set<String> scopes
    ) throws JSONException, IOException {
        if (packageName == null || packageName.isEmpty() || certificates.isEmpty()) {
            throw new IllegalArgumentException("An installed signed package is required.");
        }
        if (scopes.isEmpty() || !ApiScope.ALL.containsAll(scopes)) {
            throw new IllegalArgumentException("At least one supported API scope is required.");
        }
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            JSONArray approvals = root.getJSONArray("approvals");
            ApiApprovalEntry replacement = new ApiApprovalEntry();
            replacement.packageName = packageName;
            for (String certificate : certificates) {
                String normalized = certificate == null
                        ? ""
                        : certificate.toLowerCase(java.util.Locale.ROOT);
                if (!normalized.matches("^[0-9a-f]{64}$")) {
                    throw new IllegalArgumentException(
                            "Signing certificates must be SHA-256 hex digests."
                    );
                }
                replacement.certificateSha256.add(normalized);
            }
            replacement.scopes.addAll(scopes);
            replacement.approvalType = "user";
            replacement.approvedAt = System.currentTimeMillis();
            for (int index = approvals.length() - 1; index >= 0; index--) {
                if (packageName.equals(
                        approvals.getJSONObject(index).optString("packageName")
                )) {
                    approvals.remove(index);
                }
            }
            approvals.put(replacement.toJSONObject());
            save(root);
        }
    }

    void setRevoked(String packageName, boolean revoked)
            throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            JSONArray approvals = root.getJSONArray("approvals");
            for (int index = 0; index < approvals.length(); index++) {
                JSONObject entry = approvals.getJSONObject(index);
                if (!packageName.equals(entry.optString("packageName"))) continue;
                entry.put("revoked", revoked);
                save(root);
                return;
            }
            throw new IllegalArgumentException("The package is not approved.");
        }
    }

    private JSONObject loadRoot() throws JSONException, IOException {
        String text = preferences.getString(KEY_STORE, null);
        JSONObject root;
        if (text == null || text.isEmpty()) {
            root = new JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("approvals", new JSONArray());
        }
        else {
            root = new JSONObject(text);
            if (root.optInt("schemaVersion", 0) != SCHEMA_VERSION) {
                throw new JSONException("Unsupported API approval-store schema.");
            }
            if (root.optJSONArray("approvals") == null) {
                root.put("approvals", new JSONArray());
            }
        }
        if (reconcileFactoryApprovals(root)) save(root);
        return root;
    }

    private boolean reconcileFactoryApprovals(JSONObject root) throws JSONException {
        JSONArray approvals = root.getJSONArray("approvals");
        String factoryPackage = BuildConfig.FACTORY_INTEGRATION_PACKAGE;
        String factoryCertificate =
                BuildConfig.FACTORY_INTEGRATION_CERT_SHA256.toLowerCase(
                        java.util.Locale.ROOT
                );
        boolean factoryConfigured = !factoryPackage.isEmpty()
                && factoryCertificate.matches("^[0-9a-f]{64}$");
        boolean revoked = false;
        int factoryEntries = 0;
        boolean exactFactoryEntry = false;
        boolean staleFactoryEntry = false;
        for (int index = 0; index < approvals.length(); index++) {
            JSONObject approval = approvals.getJSONObject(index);
            String packageName = approval.optString("packageName");
            if (factoryConfigured && factoryPackage.equals(packageName)) {
                factoryEntries++;
                revoked |= approval.optBoolean("revoked", false);
                ApiApprovalEntry entry = ApiApprovalEntry.fromJSONObject(approval);
                exactFactoryEntry =
                        "factory".equals(entry.approvalType)
                        && entry.certificateSha256.size() == 1
                        && entry.certificateSha256.contains(factoryCertificate)
                        && entry.scopes.equals(ApiScope.ALL);
            }
            else if ("factory".equals(approval.optString("approvalType"))) {
                staleFactoryEntry = true;
            }
        }
        if (factoryConfigured
                && factoryEntries == 1
                && exactFactoryEntry
                && !staleFactoryEntry) {
            return false;
        }
        if (!factoryConfigured && !staleFactoryEntry) return false;

        for (int index = approvals.length() - 1; index >= 0; index--) {
            JSONObject approval = approvals.getJSONObject(index);
            if ("factory".equals(approval.optString("approvalType"))
                    || factoryConfigured && factoryPackage.equals(
                            approval.optString("packageName")
                    )) {
                approvals.remove(index);
            }
        }
        if (!factoryConfigured) return true;

        ApiApprovalEntry factory = new ApiApprovalEntry();
        factory.packageName = factoryPackage;
        factory.certificateSha256.add(factoryCertificate);
        factory.scopes.addAll(ApiScope.ALL);
        factory.approvalType = "factory";
        factory.revoked = revoked;
        factory.approvedAt = 0;
        approvals.put(factory.toJSONObject());
        return true;
    }

    private void save(JSONObject root) throws IOException {
        String previous = preferences.getString(KEY_STORE, null);
        if (preferences.edit().putString(KEY_STORE, root.toString()).commit()) return;
        SharedPreferences.Editor rollback = preferences.edit();
        if (previous != null) rollback.putString(KEY_STORE, previous);
        else rollback.remove(KEY_STORE);
        rollback.commit();
        throw new IOException("Unable to persist API approvals.");
    }
}
