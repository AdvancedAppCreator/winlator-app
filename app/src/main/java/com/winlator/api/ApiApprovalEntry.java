package com.winlator.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

final class ApiApprovalEntry {
    String packageName;
    final Set<String> certificateSha256 = new HashSet<>();
    final Set<String> scopes = new HashSet<>();
    String approvalType;
    boolean revoked;
    long approvedAt;

    boolean hasScope(String scope) {
        return !revoked && scopes.contains(scope);
    }

    JSONObject toJSONObject() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("packageName", packageName);
        data.put("certificateSha256", new JSONArray(certificateSha256));
        data.put("scopes", new JSONArray(scopes));
        data.put("approvalType", approvalType);
        data.put("revoked", revoked);
        data.put("approvedAt", approvedAt);
        return data;
    }

    static ApiApprovalEntry fromJSONObject(JSONObject data) throws JSONException {
        ApiApprovalEntry entry = new ApiApprovalEntry();
        entry.packageName = data.getString("packageName");
        JSONArray certificates = data.getJSONArray("certificateSha256");
        for (int index = 0; index < certificates.length(); index++) {
            entry.certificateSha256.add(certificates.getString(index).toLowerCase());
        }
        JSONArray scopes = data.getJSONArray("scopes");
        for (int index = 0; index < scopes.length(); index++) {
            String scope = scopes.getString(index);
            if (ApiScope.ALL.contains(scope)) entry.scopes.add(scope);
        }
        entry.approvalType = data.optString("approvalType", "user");
        entry.revoked = data.optBoolean("revoked", false);
        entry.approvedAt = data.optLong("approvedAt", 0);
        return entry;
    }
}
