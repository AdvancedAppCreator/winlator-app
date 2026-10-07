package com.winlator.api;

public abstract class GameApiContract {
    public static final int API_VERSION = 7;

    public static final String QUERY_PROVIDER_AUTHORITY = "com.winlator.secure.agm";
    public static final String QUERY_PATH_CAPABILITIES = "capabilities";
    public static final String QUERY_PATH_CONFIG_SCHEMA = "config-schema";
    public static final String QUERY_PATH_SETTINGS_SCHEMA = "settings-schema";
    public static final String QUERY_PATH_SETTINGS = "settings";
    public static final String QUERY_PATH_GAMES = "games";
    public static final String QUERY_PATH_DIAGNOSTICS = "diagnostics";
    public static final String QUERY_PATH_SNAPSHOTS = "snapshots";
    public static final String QUERY_PATH_RECOVERY = "recovery";
    public static final String QUERY_PATH_TRANSLATION_CACHE = "translation-cache";
    public static final String QUERY_PATH_GLOBAL_SETTINGS = "global-settings";
    public static final String QUERY_PATH_MODS = "mods";
    public static final String QUERY_PATH_DEPENDENCIES = "dependencies";
    public static final String QUERY_PATH_SUGGESTED_CONFIG = "suggested-config";

    public static final String ACTION_GAME_EVENT = "com.winlator.secure.event.GAME_EVENT";
    public static final String PERMISSION_SEND_GAME_EVENTS =
            "com.winlator.secure.permission.SEND_GAME_EVENTS";

    public static final String ACTION_GET_CAPABILITIES = "com.winlator.secure.action.GET_CAPABILITIES";
    public static final String ACTION_LIST_GAMES = "com.winlator.secure.action.LIST_GAMES";
    public static final String ACTION_GET_GAME = "com.winlator.secure.action.GET_GAME";
    public static final String ACTION_CREATE_GAME = "com.winlator.secure.action.CREATE_GAME";
    public static final String ACTION_CONFIGURE_GAME = "com.winlator.secure.action.CONFIGURE_GAME";
    public static final String ACTION_RUN_INSTALLER = "com.winlator.secure.action.RUN_INSTALLER";
    public static final String ACTION_LAUNCH_GAME = "com.winlator.secure.action.LAUNCH_GAME";
    public static final String ACTION_DELETE_GAME = "com.winlator.secure.action.DELETE_GAME";
    public static final String ACTION_MOVE_GAME_TO_ISOLATED =
            "com.winlator.secure.action.MOVE_GAME_TO_ISOLATED";
    public static final String ACTION_CREATE_SNAPSHOT =
            "com.winlator.secure.action.CREATE_SNAPSHOT";
    public static final String ACTION_CLEAR_TRANSLATION_CACHE =
            "com.winlator.secure.action.CLEAR_TRANSLATION_CACHE";
    public static final String ACTION_CONFIGURE_GLOBAL_SETTINGS =
            "com.winlator.secure.action.CONFIGURE_GLOBAL_SETTINGS";
    public static final String ACTION_OPEN_RECOVERY =
            "com.winlator.secure.action.OPEN_RECOVERY";
    public static final String ACTION_OPEN_MOD_MANAGER =
            "com.winlator.secure.action.OPEN_MOD_MANAGER";
    public static final String ACTION_OPEN_DEPENDENCY_MANAGER =
            "com.winlator.secure.action.OPEN_DEPENDENCY_MANAGER";

    public static final String EXTRA_API_VERSION = "api_version";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_ERROR_CODE = "error_code";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";
    public static final String EXTRA_CAPABILITIES_JSON = "capabilities_json";
    public static final String EXTRA_CONFIG_SCHEMA_JSON = "config_schema_json";
    public static final String EXTRA_SETTINGS_SCHEMA_JSON = "settings_schema_json";
    public static final String EXTRA_SETTINGS_JSON = "settings_json";
    public static final String EXTRA_GAMES_JSON = "games_json";
    public static final String EXTRA_GAME_JSON = "game_json";
    public static final String EXTRA_REPORT_JSON = "report_json";
    public static final String EXTRA_DIAGNOSTICS_JSON = "diagnostics_json";
    public static final String EXTRA_SNAPSHOTS_JSON = "snapshots_json";
    public static final String EXTRA_RECOVERY_JSON = "recovery_json";
    public static final String EXTRA_CACHE_STATUS_JSON = "cache_status_json";
    public static final String EXTRA_GLOBAL_SETTINGS_JSON = "global_settings_json";
    public static final String EXTRA_MOD_STATUS_JSON = "mod_status_json";
    public static final String EXTRA_DEPENDENCY_STATUS_JSON = "dependency_status_json";
    public static final String EXTRA_SUGGESTED_CONFIG_JSON = "suggested_config_json";
    public static final String EXTRA_SNAPSHOT_JSON = "snapshot_json";
    public static final String EXTRA_SNAPSHOT_ID = "snapshot_id";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_REPORT_ID = "report_id";
    public static final String EXTRA_GAME_ID = "game_id";
    public static final String EXTRA_CONTAINER_ID = "container_id";
    public static final String EXTRA_CONTAINER_POLICY = "container_policy";
    public static final String EXTRA_CONTAINER_KEY = "container_key";
    public static final String EXTRA_CREATE_RECONCILED = "create_reconciled";
    public static final String EXTRA_CONTAINER_DELETED = "container_deleted";
    public static final String EXTRA_CONTAINER_PRESERVED = "container_preserved";
    public static final String EXTRA_CONTAINER_REFERENCE_COUNT = "container_reference_count";
    public static final String EXTRA_LAUNCH_DEFERRED = "launch_deferred";
    public static final String EXTRA_EVENT_TYPE = "event_type";
    public static final String EXTRA_STARTED_AT = "started_at";
    public static final String EXTRA_ENDED_AT = "ended_at";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_OFFSET = "offset";
    public static final String EXTRA_LIMIT = "limit";
    public static final String EXTRA_HAS_MORE = "has_more";
    public static final String EXTRA_NEXT_OFFSET = "next_offset";
    public static final String EXTRA_OUTCOME = "outcome";
    public static final String EXTRA_PHASE = "phase";
    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_CONFIDENCE = "confidence";
    public static final String EXTRA_CONFIG_HEALTH = "config_health";
    public static final String EXTRA_DURATION_MILLIS = "duration_millis";
    public static final String EXTRA_EXIT_CODE = "exit_code";
    public static final String EXTRA_SIGNAL = "signal";
    public static final String EXTRA_TERMINATION_ORIGIN = "termination_origin";
    public static final String EXTRA_RUNTIME_REACHED = "runtime_reached";
    public static final String EXTRA_APPLIED_CONFIG_SHA256 = "applied_config_sha256";
    public static final String EXTRA_SETTINGS_SHA256 = "settings_sha256";
    public static final String EXTRA_CHANGED_NAMESPACE = "changed_namespace";
    public static final String EXTRA_PROVIDER_PATH = "provider_path";

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_GAME_PATH = "game_path";
    public static final String EXTRA_EXECUTABLE_PATH = "executable_path";
    public static final String EXTRA_EXECUTABLE_DOS_PATH = "executable_dos_path";
    public static final String EXTRA_INSTALLER_PATH = "installer_path";
    public static final String EXTRA_ARGUMENTS = "arguments";
    public static final String EXTRA_INSTALLER_ARGUMENTS = "installer_arguments";
    public static final String EXTRA_CONFIG_JSON = "config_json";
    public static final String EXTRA_CONFIG_UPDATE_JSON = "config_update_json";
    public static final String EXTRA_SETTINGS_UPDATE_JSON = "settings_update_json";
    public static final String EXTRA_LAUNCH_AFTER_CREATE = "launch_after_create";
    public static final String EXTRA_AGM_METADATA = "agm_metadata";
    public static final String EXTRA_ASYNC_INSTALLER = "async_installer";
    public static final String INTERNAL_EXTRA_SESSION_TOKEN =
            "com.winlator.secure.internal.SESSION_TOKEN";
    public static final String INTERNAL_EXTRA_FORCE_FULLSCREEN =
            "com.winlator.secure.internal.FORCE_FULLSCREEN";
    public static final String INTERNAL_EXTRA_GAME_LANGUAGE =
            "com.winlator.secure.internal.GAME_LANGUAGE";
    public static final String INTERNAL_EXTRA_RUNTIME_LOCALE =
            "com.winlator.secure.internal.RUNTIME_LOCALE";
    public static final String INTERNAL_EXTRA_DEPENDENCY_ID =
            "com.winlator.secure.internal.DEPENDENCY_ID";
    public static final String EXTRA_DEPENDENCY_ID = "dependency_id";

    public static final String EVENT_GAME_EXITED = "game_exited";
    public static final String EVENT_INSTALL_PROGRESS = "install_progress";
    public static final String EVENT_INSTALL_COMPLETED = "install_completed";
    public static final String EVENT_INSTALL_FAILED = "install_failed";
    public static final String EVENT_SETTINGS_CHANGED = "settings_changed";
    public static final String EVENT_DEPENDENCY_INSTALLED = "dependency_installed";
    public static final String EVENT_DEPENDENCY_FAILED = "dependency_failed";
    public static final String EVENT_STALL_OUTCOME = "stall_outcome";

    public static final String ERROR_UNAUTHORIZED = "UNAUTHORIZED";
    public static final String ERROR_SCOPE_DENIED = "SCOPE_DENIED";
    public static final String ERROR_UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION";
    public static final String ERROR_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String ERROR_ROOTFS_NOT_READY = "ROOTFS_NOT_READY";
    public static final String ERROR_GAME_NOT_FOUND = "GAME_NOT_FOUND";
    public static final String ERROR_GAME_ALREADY_EXISTS = "GAME_ALREADY_EXISTS";
    public static final String ERROR_CONTAINER_NOT_FOUND = "CONTAINER_NOT_FOUND";
    public static final String ERROR_CREATE_FAILED = "CREATE_FAILED";
    public static final String ERROR_DELETE_FAILED = "DELETE_FAILED";
    public static final String ERROR_CONTAINER_IN_USE = "CONTAINER_IN_USE";
    public static final String ERROR_CLONE_FAILED = "CLONE_FAILED";
    public static final String ERROR_INSUFFICIENT_STORAGE = "INSUFFICIENT_STORAGE";
    public static final String ERROR_SIZE_UNAVAILABLE = "SIZE_UNAVAILABLE";
    public static final String ERROR_EXECUTABLE_NOT_CONFIGURED = "EXECUTABLE_NOT_CONFIGURED";
    public static final String ERROR_PATH_NOT_ACCESSIBLE = "PATH_NOT_ACCESSIBLE";
    public static final String ERROR_WINLATOR_BUSY = "WINLATOR_BUSY";
    public static final String ERROR_OPERATION_IN_PROGRESS = "OPERATION_IN_PROGRESS";
    public static final String ERROR_STORAGE_FAILED = "STORAGE_FAILED";
    public static final String ERROR_METADATA_TOO_LARGE = "METADATA_TOO_LARGE";
    public static final String ERROR_PATH_OUTSIDE_PROFILE = "PATH_OUTSIDE_PROFILE";
    public static final String ERROR_INSTALL_FAILED = "INSTALL_FAILED";
    public static final String ERROR_INSTALL_ABORTED = "INSTALL_ABORTED";
    public static final String ERROR_LAUNCH_FAILED = "LAUNCH_FAILED";
    public static final String ERROR_CONFIG_CONFLICT = "CONFIG_CONFLICT";
    public static final String ERROR_SETTINGS_CONFLICT = "SETTINGS_CONFLICT";
    public static final String ERROR_DIAGNOSTIC_NOT_FOUND = "DIAGNOSTIC_NOT_FOUND";
    public static final String ERROR_RUNTIME_PREREQUISITES_REQUIRED =
            "RUNTIME_PREREQUISITES_REQUIRED";

    public static void validatePage(int offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative.");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100.");
        }
    }
}
