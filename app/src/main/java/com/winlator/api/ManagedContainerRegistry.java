package com.winlator.api;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

final class ManagedContainerRegistry {
    private ManagedContainerRegistry() {}

    static Container ensureAgmDefaultContainer(Context context)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            ManagedGameStore store = new ManagedGameStore(context);
            ContainerManager manager = new ContainerManager(context);
            String key = ManagedGame.DEFAULT_SHARED_CONTAINER_KEY;
            Integer boundId = store.getSharedContainerId(key);
            if (boundId != null) {
                Container existing = manager.getContainerById(boundId);
                if (existing != null) return existing;
                store.clearSharedContainer(key, boundId);
            }

            JSONObject data = ManagedContainerFactory.createData(
                    context,
                    "AGM Shared (" + key + ")",
                    Container.getDefaultDrives(context),
                    new JSONObject()
            );
            Container created = manager.createContainer(data);
            if (created == null) throw new IOException("Unable to create AGM shared container.");
            try {
                store.bindSharedContainer(key, created.id);
                return created;
            }
            catch (JSONException | IOException error) {
                manager.removeContainer(created);
                throw error;
            }
        }
    }
}
