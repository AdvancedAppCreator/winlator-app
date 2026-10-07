package com.winlator.api;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

import com.winlator.container.ContainerOperationLock;
import com.winlator.text.GameTextCacheManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public class GameQueryProvider extends ContentProvider {
    public static final String[] COLUMNS = {
            "_id",
            GameApiContract.EXTRA_SUCCESS,
            GameApiContract.EXTRA_API_VERSION,
            GameApiContract.EXTRA_ERROR_CODE,
            GameApiContract.EXTRA_ERROR_MESSAGE,
            GameApiContract.EXTRA_CAPABILITIES_JSON,
            GameApiContract.EXTRA_CONFIG_SCHEMA_JSON,
            GameApiContract.EXTRA_SETTINGS_SCHEMA_JSON,
            GameApiContract.EXTRA_SETTINGS_JSON,
            GameApiContract.EXTRA_GAMES_JSON,
            GameApiContract.EXTRA_GAME_JSON,
            GameApiContract.EXTRA_REPORT_JSON,
            GameApiContract.EXTRA_DIAGNOSTICS_JSON,
            GameApiContract.EXTRA_SNAPSHOTS_JSON,
            GameApiContract.EXTRA_RECOVERY_JSON,
            GameApiContract.EXTRA_CACHE_STATUS_JSON,
            GameApiContract.EXTRA_GLOBAL_SETTINGS_JSON,
            GameApiContract.EXTRA_MOD_STATUS_JSON,
            GameApiContract.EXTRA_DEPENDENCY_STATUS_JSON,
            GameApiContract.EXTRA_SUGGESTED_CONFIG_JSON,
            GameApiContract.EXTRA_HAS_MORE,
            GameApiContract.EXTRA_NEXT_OFFSET
    };

    private static final int CAPABILITIES = 1;
    private static final int CONFIG_SCHEMA = 2;
    private static final int GAMES = 3;
    private static final int GAME = 4;
    private static final int DIAGNOSTIC = 5;
    private static final int GAME_DIAGNOSTICS = 6;
    private static final int SETTINGS_SCHEMA = 7;
    private static final int GAME_SETTINGS = 8;
    private static final int GAME_SNAPSHOTS = 9;
    private static final int GAME_RECOVERY = 10;
    private static final int GAME_TRANSLATION_CACHE = 11;
    private static final int GLOBAL_SETTINGS = 12;
    private static final int GAME_MODS = 13;
    private static final int GAME_DEPENDENCIES = 14;
    private static final int GAME_SUGGESTED_CONFIG = 15;
    private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_CONFIG_SCHEMA,
                CONFIG_SCHEMA
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_SETTINGS_SCHEMA,
                SETTINGS_SCHEMA
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_CAPABILITIES,
                CAPABILITIES
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES,
                GAMES
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*",
                GAME
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_DIAGNOSTICS+"/*",
                DIAGNOSTIC
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+GameApiContract.QUERY_PATH_DIAGNOSTICS,
                GAME_DIAGNOSTICS
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+GameApiContract.QUERY_PATH_SETTINGS,
                GAME_SETTINGS
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+GameApiContract.QUERY_PATH_SNAPSHOTS,
                GAME_SNAPSHOTS
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+GameApiContract.QUERY_PATH_RECOVERY,
                GAME_RECOVERY
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+
                        GameApiContract.QUERY_PATH_TRANSLATION_CACHE,
                GAME_TRANSLATION_CACHE
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GLOBAL_SETTINGS,
                GLOBAL_SETTINGS
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+GameApiContract.QUERY_PATH_MODS,
                GAME_MODS
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+
                        GameApiContract.QUERY_PATH_DEPENDENCIES,
                GAME_DEPENDENCIES
        );
        URI_MATCHER.addURI(
                GameApiContract.QUERY_PROVIDER_AUTHORITY,
                GameApiContract.QUERY_PATH_GAMES+"/*/"+
                        GameApiContract.QUERY_PATH_SUGGESTED_CONFIG,
                GAME_SUGGESTED_CONFIG
        );
    }

    private ManagedGameStore store;

    @Override
    public boolean onCreate() {
        store = new ManagedGameStore(providerContext());
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        enforceAuthorizedCaller();
        try {
            switch (URI_MATCHER.match(uri)) {
                case CAPABILITIES:
                    return successCursor(
                            GameApiContract.EXTRA_CAPABILITIES_JSON,
                            GameApiJson.capabilities(providerContext()).toString()
                    );
                case CONFIG_SCHEMA:
                    return successCursor(
                            GameApiContract.EXTRA_CONFIG_SCHEMA_JSON,
                            GameConfigSchema.build(providerContext()).toString()
                    );
                case SETTINGS_SCHEMA:
                    return successCursor(
                            GameApiContract.EXTRA_SETTINGS_SCHEMA_JSON,
                            GameSettingsSchema.build(providerContext()).toString()
                    );
                case GLOBAL_SETTINGS:
                    JSONObject globalSettings =
                            new GlobalSettingsStore(providerContext()).get();
                    return successCursor(
                            GameApiContract.EXTRA_GLOBAL_SETTINGS_JSON,
                            GameApiJson.settingsPayload(globalSettings).toString()
                    );
                case GAMES:
                    boolean paged = uri.getQueryParameter(GameApiContract.EXTRA_OFFSET) != null ||
                            uri.getQueryParameter(GameApiContract.EXTRA_LIMIT) != null;
                    int offset = paged
                            ? queryInt(uri, GameApiContract.EXTRA_OFFSET, 0)
                            : 0;
                    int limit = paged
                            ? queryInt(uri, GameApiContract.EXTRA_LIMIT, 8)
                            : Integer.MAX_VALUE;
                    if (paged) GameApiContract.validatePage(offset, limit);
                    GameApiJson.GamePage page = GameApiJson.gamesPage(
                            providerContext(),
                            store,
                            offset,
                            limit
                    );
                    return pageCursor(page);
                case GAME:
                    synchronized (ContainerOperationLock.LOCK) {
                        String gameId = uri.getLastPathSegment();
                        ManagedGame game = gameId != null ? store.get(gameId) : null;
                        if (game == null) {
                            return errorCursor(
                                    GameApiContract.ERROR_GAME_NOT_FOUND,
                                    "No managed game uses id "+gameId+"."
                            );
                        }
                        return successCursor(
                                GameApiContract.EXTRA_GAME_JSON,
                                GameApiJson.game(providerContext(), game).toString()
                        );
                    }
                case DIAGNOSTIC:
                    String reportId = uri.getLastPathSegment();
                    JSONObject report = new ManagedDiagnosticStore(providerContext()).get(reportId);
                    if (report == null) {
                        return errorCursor(
                                GameApiContract.ERROR_DIAGNOSTIC_NOT_FOUND,
                                "No diagnostic report uses id "+reportId+"."
                        );
                    }
                    return successCursor(
                            GameApiContract.EXTRA_REPORT_JSON,
                            report.toString()
                    );
                case GAME_DIAGNOSTICS:
                    java.util.List<String> segments = uri.getPathSegments();
                    String diagnosticGameId = segments.size() >= 2 ? segments.get(1) : null;
                    if (diagnosticGameId == null || diagnosticGameId.isEmpty()) {
                        throw new IllegalArgumentException("A game id is required.");
                    }
                    int diagnosticLimit = queryInt(uri, GameApiContract.EXTRA_LIMIT, 20);
                    if (diagnosticLimit < 1 || diagnosticLimit > 20) {
                        throw new IllegalArgumentException("limit must be between 1 and 20.");
                    }
                    return successCursor(
                            GameApiContract.EXTRA_DIAGNOSTICS_JSON,
                            new ManagedDiagnosticStore(providerContext())
                                    .listForGame(diagnosticGameId, diagnosticLimit)
                                    .toString()
                    );
                case GAME_SETTINGS:
                    java.util.List<String> settingsSegments = uri.getPathSegments();
                    String settingsGameId =
                            settingsSegments.size() >= 2 ? settingsSegments.get(1) : null;
                    ManagedGame settingsGame =
                            settingsGameId != null ? store.get(settingsGameId) : null;
                    if (settingsGame == null) {
                        return errorCursor(
                                GameApiContract.ERROR_GAME_NOT_FOUND,
                                "No managed game uses id " + settingsGameId + "."
                        );
                    }
                    return successCursor(
                            GameApiContract.EXTRA_SETTINGS_JSON,
                            GameApiJson.settingsPayload(
                                    GameSettingsSchema.effective(
                                            providerContext(),
                                            settingsGame
                                    )
                            ).toString()
                    );
                case GAME_SNAPSHOTS:
                    String snapshotGameId = gameIdFromResourceUri(uri);
                    if (store.get(snapshotGameId) == null) {
                        return gameNotFound(snapshotGameId);
                    }
                    return successCursor(
                            GameApiContract.EXTRA_SNAPSHOTS_JSON,
                            new ConfigurationSnapshotStore(providerContext())
                                    .list(snapshotGameId)
                                    .toString()
                    );
                case GAME_RECOVERY:
                    String recoveryGameId = gameIdFromResourceUri(uri);
                    if (store.get(recoveryGameId) == null) {
                        return gameNotFound(recoveryGameId);
                    }
                    return successCursor(
                            GameApiContract.EXTRA_RECOVERY_JSON,
                            ManagedRecoveryStatus.analyze(
                                    recoveryGameId,
                                    new ManagedDiagnosticStore(providerContext())
                                            .listForGame(recoveryGameId, 20),
                                    System.currentTimeMillis()
                            ).toString()
                    );
                case GAME_TRANSLATION_CACHE:
                    String cacheGameId = gameIdFromResourceUri(uri);
                    if (store.get(cacheGameId) == null) {
                        return gameNotFound(cacheGameId);
                    }
                    return successCursor(
                            GameApiContract.EXTRA_CACHE_STATUS_JSON,
                            GameTextCacheManager.status(
                                    providerContext(),
                                    "game:" + cacheGameId
                            ).toString()
                    );
                case GAME_MODS:
                    String modsGameId = gameIdFromResourceUri(uri);
                    ManagedGame modsGame = store.get(modsGameId);
                    if (modsGame == null) return gameNotFound(modsGameId);
                    return successCursor(
                            GameApiContract.EXTRA_MOD_STATUS_JSON,
                            ModApiJson.status(providerContext(), modsGame).toString()
                    );
                case GAME_DEPENDENCIES:
                    String dependenciesGameId = gameIdFromResourceUri(uri);
                    ManagedGame dependenciesGame = store.get(dependenciesGameId);
                    if (dependenciesGame == null) {
                        return gameNotFound(dependenciesGameId);
                    }
                    return successCursor(
                            GameApiContract.EXTRA_DEPENDENCY_STATUS_JSON,
                            RuntimeDependencyApiJson.status(
                                    providerContext(),
                                    dependenciesGame
                            ).toString()
                    );
                case GAME_SUGGESTED_CONFIG:
                    String suggestGameId = gameIdFromResourceUri(uri);
                    ManagedGame suggestGame = store.get(suggestGameId);
                    if (suggestGame == null) return gameNotFound(suggestGameId);
                    return successCursor(
                            GameApiContract.EXTRA_SUGGESTED_CONFIG_JSON,
                            GameConfigSuggester.suggest(providerContext(), suggestGame)
                                    .toString()
                    );
                default:
                    throw new IllegalArgumentException("Unsupported Winlator AGM query URI: "+uri);
            }
        }
        catch (JSONException | IOException e) {
            return errorCursor(GameApiContract.ERROR_STORAGE_FAILED, e.getMessage());
        }
        catch (IllegalArgumentException e) {
            return errorCursor(GameApiContract.ERROR_INVALID_ARGUMENT, e.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        enforceAuthorizedCaller();
        switch (URI_MATCHER.match(uri)) {
            case CAPABILITIES:
            case CONFIG_SCHEMA:
            case SETTINGS_SCHEMA:
            case GLOBAL_SETTINGS:
            case GAME:
            case DIAGNOSTIC:
            case GAME_SETTINGS:
            case GAME_RECOVERY:
            case GAME_TRANSLATION_CACHE:
            case GAME_MODS:
            case GAME_DEPENDENCIES:
            case GAME_SUGGESTED_CONFIG:
                return "vnd.android.cursor.item/vnd.com.winlator.secure.agm";
            case GAMES:
            case GAME_DIAGNOSTICS:
            case GAME_SNAPSHOTS:
                return "vnd.android.cursor.dir/vnd.com.winlator.secure.agm";
            default:
                return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        enforceAuthorizedCaller();
        throw new UnsupportedOperationException("The Winlator AGM provider is read-only.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceAuthorizedCaller();
        throw new UnsupportedOperationException("The Winlator AGM provider is read-only.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        enforceAuthorizedCaller();
        throw new UnsupportedOperationException("The Winlator AGM provider is read-only.");
    }

    private void enforceAuthorizedCaller() {
        GameApiAuthorization.AuthResult result = GameApiAuthorization.authorizeUid(
                providerContext(),
                Binder.getCallingUid(),
                ApiScope.READ
        );
        if (!result.authenticated) {
            throw new SecurityException(
                    GameApiContract.ERROR_UNAUTHORIZED
                            + ": The caller is not an approved Winlator integration."
            );
        }
        if (!result.authorized) {
            throw new SecurityException(
                    GameApiContract.ERROR_SCOPE_DENIED
                            + ": The caller does not have the read scope."
            );
        }
    }

    private android.content.Context providerContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context is unavailable.");
        return context;
    }

    private MatrixCursor successCursor(String payloadColumn, String payload) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("_id", 0);
        row.add(GameApiContract.EXTRA_SUCCESS, 1);
        row.add(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        row.add(payloadColumn, payload);
        return cursor;
    }

    private MatrixCursor errorCursor(String code, String message) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("_id", 0);
        row.add(GameApiContract.EXTRA_SUCCESS, 0);
        row.add(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        row.add(GameApiContract.EXTRA_ERROR_CODE, code);
        row.add(GameApiContract.EXTRA_ERROR_MESSAGE, message != null ? message : code);
        return cursor;
    }

    private MatrixCursor pageCursor(GameApiJson.GamePage page) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("_id", 0);
        row.add(GameApiContract.EXTRA_SUCCESS, 1);
        row.add(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        row.add(GameApiContract.EXTRA_GAMES_JSON, page.games.toString());
        row.add(GameApiContract.EXTRA_HAS_MORE, page.hasMore ? 1 : 0);
        row.add(GameApiContract.EXTRA_NEXT_OFFSET, page.nextOffset);
        return cursor;
    }

    private int queryInt(Uri uri, String name, int defaultValue) {
        String value = uri.getQueryParameter(name);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(name+" must be an integer.");
        }
    }

    private String gameIdFromResourceUri(Uri uri) {
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() < 3 || segments.get(1).isEmpty()) {
            throw new IllegalArgumentException("A game id is required.");
        }
        return segments.get(1);
    }

    private MatrixCursor gameNotFound(String gameId) {
        return errorCursor(
                GameApiContract.ERROR_GAME_NOT_FOUND,
                "No managed game uses id " + gameId + "."
        );
    }
}
