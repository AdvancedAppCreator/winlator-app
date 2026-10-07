package com.winlator.api;

import android.content.Context;

import com.winlator.box64.Box64Preset;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.DesktopMode;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.WineThemeManager;
import com.winlator.win32.WinVersions;

import org.json.JSONException;
import org.json.JSONObject;

final class ManagedContainerFactory {
    private ManagedContainerFactory() {}

    static JSONObject createData(
            Context context,
            String name,
            String drives,
            JSONObject config
    ) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("name", name);
        data.put("screenSize", Container.DEFAULT_SCREEN_SIZE);
        data.put("winVersion", WinVersions.DEFAULT_VERSION);
        data.put("envVars", Container.DEFAULT_ENV_VARS);
        data.put("cpuList", Container.getFallbackCPUList());
        data.put("cpuListWoW64", Container.getFallbackCPUList());
        data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(context));
        data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
        data.put("dxwrapperConfig", "");
        data.put("graphicsDriverConfig", "");
        data.put("audioDriver", AudioDrivers.ALSA);
        data.put("audioDriverConfig", "");
        data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
        data.put("drives", drives);
        data.put("hudMode", 0);
        data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
        data.put("box64Preset", Box64Preset.DEFAULT);
        data.put("desktopTheme", WineThemeManager.DEFAULT_DESKTOP_THEME);

        for (String field : GameApiJson.CONFIG_FIELDS) {
            if (config.has(field)) data.put(field, config.get(field));
        }
        return data;
    }
}
