package com.winlator.xenvironment;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StartupLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final RootFS rootFS;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

    public XEnvironment(Context context, RootFS rootFS) {
        this.context = context;
        this.rootFS = rootFS;
    }

    public Context getContext() {
        return context;
    }

    public RootFS getRootFS() {
        return rootFS;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public File getTmpDir() {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir());
        for (EnvironmentComponent environmentComponent : this) {
            String name = environmentComponent.getClass().getSimpleName();
            StartupLog.log("Starting component "+name);
            environmentComponent.start();
            StartupLog.log("Started component "+name);
        }
    }

    public void stopEnvironmentComponents() {
        stopEnvironmentComponents(ProcessHelper.TerminationOrigin.WINLATOR_TEARDOWN);
    }

    public void stopEnvironmentComponents(ProcessHelper.TerminationOrigin origin) {
        for (int index = components.size() - 1; index >= 0; index--) {
            EnvironmentComponent environmentComponent = components.get(index);
            String name = environmentComponent.getClass().getSimpleName();
            StartupLog.log("Stopping component "+name);
            environmentComponent.stop(origin);
        }
    }

    public void onPause() {
        for (EnvironmentComponent environmentComponent : this) environmentComponent.onPause();
    }

    public void onResume() {
        for (EnvironmentComponent environmentComponent : this) environmentComponent.onResume();
    }
}
