package com.winlator.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class ApiApprovalStoreTest {
    private static final String AGM_PACKAGE =
            "com.advancedappcreator.adultgamemanager";
    private static final String AGM_CERTIFICATE =
            "f2e2d51bdf36d9ab1ce2b9a4fedbf12e6a95bece274476a4aaefb0ab2dd7d509";

    private Context context;

    @Before
    public void resetStore() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("api_approvals", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void officialAgmIsFactoryApproved() throws Exception {
        ApiApprovalStore store = new ApiApprovalStore(context);
        ApiApprovalEntry entry = store.get(AGM_PACKAGE);

        assertNotNull(entry);
        assertEquals("factory", entry.approvalType);
        assertEquals(Collections.singleton(AGM_CERTIFICATE), entry.certificateSha256);
        assertEquals(ApiScope.ALL, entry.scopes);
        assertFalse(entry.revoked);
        assertEquals(0, entry.approvedAt);
        assertEquals(1, store.list().size());
        assertNull(store.get("com.example.integration"));
    }

    @Test
    public void staleFactoryApprovalsAreReplacedByOfficialAgm() throws Exception {
        JSONObject root = new JSONObject()
                .put("schemaVersion", 1)
                .put("approvals", new JSONArray()
                        .put(new JSONObject()
                                .put("packageName", "com.example.legacy")
                                .put("certificateSha256", new JSONArray()
                                        .put(repeat("a", 64)))
                                .put("scopes", new JSONArray().put(ApiScope.READ))
                                .put("approvalType", "factory")
                                .put("revoked", true)));
        context.getSharedPreferences("api_approvals", Context.MODE_PRIVATE)
                .edit()
                .putString("store_v1", root.toString())
                .commit();

        ApiApprovalStore store = new ApiApprovalStore(context);
        assertNull(store.get("com.example.legacy"));
        assertNotNull(store.get(AGM_PACKAGE));
        assertEquals(1, store.list().size());
    }

    @Test
    public void factoryRevocationSurvivesCertificateReconciliation() throws Exception {
        JSONObject root = new JSONObject()
                .put("schemaVersion", 1)
                .put("approvals", new JSONArray()
                        .put(new JSONObject()
                                .put("packageName", AGM_PACKAGE)
                                .put("certificateSha256", new JSONArray()
                                        .put(repeat("a", 64)))
                                .put("scopes", new JSONArray().put(ApiScope.READ))
                                .put("approvalType", "factory")
                                .put("revoked", true)));
        context.getSharedPreferences("api_approvals", Context.MODE_PRIVATE)
                .edit()
                .putString("store_v1", root.toString())
                .commit();

        ApiApprovalEntry entry = new ApiApprovalStore(context).get(AGM_PACKAGE);
        assertNotNull(entry);
        assertEquals(Collections.singleton(AGM_CERTIFICATE), entry.certificateSha256);
        assertEquals(ApiScope.ALL, entry.scopes);
        assertTrue(entry.revoked);
    }

    @Test
    public void userApprovalPersistsOnlySelectedScopes() throws Exception {
        ApiApprovalStore store = new ApiApprovalStore(context);
        store.approve(
                "com.example.integration",
                Collections.singleton(repeat("a", 64)),
                Collections.singleton(ApiScope.SETTINGS)
        );

        ApiApprovalEntry entry = store.get("com.example.integration");
        assertTrue(entry.hasScope(ApiScope.SETTINGS));
        assertFalse(entry.hasScope(ApiScope.READ));
    }

    @Test
    public void actionScopeMappingSeparatesReadSettingsAndRecovery() {
        assertTrue(ApiScope.READ.equals(GameApiAuthorization.requiredScope(
                GameApiContract.ACTION_LIST_GAMES
        )));
        assertTrue(ApiScope.SETTINGS.equals(GameApiAuthorization.requiredScope(
                GameApiContract.ACTION_CONFIGURE_GAME
        )));
        assertTrue(ApiScope.RECOVERY.equals(GameApiAuthorization.requiredScope(
                GameApiContract.ACTION_CREATE_SNAPSHOT
        )));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
