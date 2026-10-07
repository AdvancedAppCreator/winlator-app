package com.winlator.api;

import com.winlator.container.Container;
import com.winlator.win32.WinVersions;

import org.json.JSONException;
import org.json.JSONObject;

final class ManagedWinVersion {
    static final class State {
        final String effectiveValue;
        final String source;

        State(String effectiveValue, String source) {
            this.effectiveValue = effectiveValue;
            this.source = source;
        }
    }

    private ManagedWinVersion() {
    }

    static State resolve(ManagedGame game, Container container) throws JSONException {
        if (game.configJson != null) {
            JSONObject config = new JSONObject(game.configJson);
            if (config.has("winVersion")) {
                String source = game.winVersionSource;
                if (!ManagedGame.WIN_VERSION_SOURCE_DEFAULT.equals(source)
                        && !ManagedGame.WIN_VERSION_SOURCE_EXPLICIT.equals(source)) {
                    source = ManagedGame.WIN_VERSION_SOURCE_EXPLICIT;
                }
                return new State(config.getString("winVersion"), source);
            }
        }
        return new State(
                WinVersions.readVersion(container),
                ManagedGame.WIN_VERSION_SOURCE_REGISTRY_LEGACY
        );
    }

    static void putMetadata(JSONObject target, ManagedGame game, Container container)
            throws JSONException {
        State state = resolve(game, container);
        if (state.effectiveValue != null) {
            target.put("effectiveWinVersion", state.effectiveValue);
        }
        target.put("winVersionSource", state.source);
    }
}
