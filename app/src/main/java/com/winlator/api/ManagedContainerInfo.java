package com.winlator.api;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ManagedContainerInfo {
    public final int referenceCount;
    public final boolean shared;
    public final String containerKey;
    public final List<String> gameTitles;

    private ManagedContainerInfo(ManagedGameStore.ContainerUsage usage) {
        referenceCount = usage.referenceCount;
        shared = usage.shared;
        containerKey = usage.containerKey;
        gameTitles = Collections.unmodifiableList(new ArrayList<>(usage.titles));
    }

    public static ManagedContainerInfo inspect(Context context, int containerId)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            return new ManagedContainerInfo(
                    new ManagedGameStore(context).getContainerUsage(containerId)
            );
        }
    }

    public static RemovalResult removeIfUnreferenced(
            Context context,
            ContainerManager manager,
            Container container
    ) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            ManagedContainerInfo info = inspect(context, container.id);
            if (info.referenceCount > 0 || info.shared) {
                return new RemovalResult(false, true, info);
            }
            return new RemovalResult(
                    manager.removeContainer(container),
                    false,
                    info
            );
        }
    }

    public static final class RemovalResult {
        public final boolean removed;
        public final boolean blocked;
        public final ManagedContainerInfo info;

        RemovalResult(boolean removed, boolean blocked, ManagedContainerInfo info) {
            this.removed = removed;
            this.blocked = blocked;
            this.info = info;
        }
    }
}
