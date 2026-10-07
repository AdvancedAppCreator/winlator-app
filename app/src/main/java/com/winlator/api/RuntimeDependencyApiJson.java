package com.winlator.api;

import android.content.Context;

import com.winlator.api.dependency.DependencyInstallRecord;
import com.winlator.api.dependency.RuntimeDependencyCatalog;
import com.winlator.api.dependency.RuntimeDependencyManager;
import com.winlator.api.dependency.RuntimeDependencyBootstrapRecord;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

final class RuntimeDependencyApiJson {
    private RuntimeDependencyApiJson() {
    }

    static JSONObject status(Context context, ManagedGame game)
            throws JSONException, IOException {
        Map<String, DependencyInstallRecord> records =
                RuntimeDependencyManager.getContainerStatus(
                        context,
                        game.containerId
                );
        JSONArray dependencies = new JSONArray();
        for (RuntimeDependencyCatalog.Entry entry :
                RuntimeDependencyCatalog.allEntries()) {
            JSONObject item = new JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.displayName)
                    .put("category", entry.category.name())
                    .put("description", entry.description)
                    .put("installable", entry.isSupported());
            DependencyInstallRecord record = records.get(entry.id);
            if (record != null) {
                JSONObject status = record.toJSON();
                if (status.has("installerPath")) {
                    String path = status.getString("installerPath");
                    status.remove("installerPath");
                    status.put("installerSelected", true);
                    status.put("installerFileName", new File(path).getName());
                }
                else {
                    status.put("installerSelected", false);
                }
                item.put("status", status);
            }
            dependencies.put(item);
        }

        List<RuntimeDependencyFacade.AffectedGame> affected =
                RuntimeDependencyManager.findAffectedGames(
                        context,
                        game.containerId
                );
        JSONArray games = new JSONArray();
        for (RuntimeDependencyFacade.AffectedGame affectedGame : affected) {
            games.put(new JSONObject()
                    .put("id", affectedGame.id)
                    .put("title", affectedGame.title));
        }
        JSONObject result = new JSONObject()
                .put("gameId", game.id)
                .put("containerId", game.containerId)
                .put("containerWide", true)
                .put("affectedGames", games)
                .put("sharedContainer", affected.size() > 1)
                .put("dependencies", dependencies);
        RuntimeDependencyBootstrapRecord bootstrap =
                RuntimeDependencyManager.getAgmBootstrap(context);
        if (bootstrap != null && bootstrap.containerId == game.containerId) {
            result.put("bootstrap", new JSONObject()
                    .put("key", RuntimeDependencyManager.AGM_BOOTSTRAP_KEY)
                    .put("planVersion", bootstrap.planVersion)
                    .put("state", bootstrap.state.name())
                    .put("containerId", bootstrap.containerId)
                    .put("selectedPackageIds", new JSONArray(bootstrap.selectedPackageIds))
                    .put("completedPackageIds", new JSONArray(bootstrap.completedPackageIds))
                    .put("currentPackageId",
                            bootstrap.currentPackageId != null
                                    ? bootstrap.currentPackageId
                                    : JSONObject.NULL)
                    .put("lastError",
                            bootstrap.lastError != null
                                    ? bootstrap.lastError
                                    : JSONObject.NULL));
        }
        return result;
    }
}
