# AGM integration with Winlator Secure

Winlator Secure (`com.winlator.secure`) exposes API v7 for approved companion apps such as Adult Game Manager (AGM). Managed games may use isolated containers or the single prerequisite-managed `agm.default` shared container. `XServerDisplayActivity` remains private.

API v7 is additive. Every v1-v6 action, extra, result field, state, and error code remains valid. Integrations must feature-detect additions through `GET_CAPABILITIES`.

## Requirements and authorization

- AGM and Winlator must be installed in the same Android user/profile.
- Calls must originate from an installed package approved inside Winlator and must match an approved signing-certificate SHA-256 digest.
- Official builds factory-approve the release-signed AGM package on first use. The
  approval is certificate-pinned, includes all supported scopes, and remains revocable
  under **Settings > Approved integrations**.
- Distributors may replace the factory-approved package and signing-certificate digest
  with the `factoryIntegrationPackage` and `factoryIntegrationCertSha256` Gradle
  properties.
- Other installed integrations can be approved under **Settings > Approved integrations** with only the scopes the user selects.
- Activity requests must use the Activity Result API. Plain `startActivity()` does not provide a trusted calling package and is rejected with `UNAUTHORIZED`.
- The silent provider applies the same package and signing-certificate checks using the Binder caller UID.
- Open Winlator manually once and complete rootfs setup before creating or launching games.

Add package visibility to AGM:

```xml
<queries>
    <package android:name="com.winlator.secure" />
</queries>
```

## Capability negotiation

Activity action:

```text
com.winlator.secure.action.GET_CAPABILITIES
```

Read `capabilities_json` from the successful Activity Result, or query the silent provider described below.

Example API v7 payload includes all earlier fields plus:

```json
{
  "apiVersion": 7,
  "packageName": "com.winlator.secure",
  "oneContainerPerGame": false,
  "sharedContainers": true,
  "sharedDefaultContainer": true,
  "defaultContainerPolicy": "isolated",
  "defaultSharedContainerKey": "agm.default",
  "moveGameToIsolated": true,
  "referenceSafeContainerDeletion": true,
  "supportsPortableGames": true,
  "supportsWindowsInstallers": true,
  "supportsUnixExecutables": true,
  "supportsDosExecutables": true,
  "idempotentCreateByGameId": true,
  "createReconciledExtra": "create_reconciled",
  "createConflictIncludesGame": true,
  "silentQueryProvider": true,
  "silentQueryProviderAuthority": "com.winlator.secure.agm",
  "agmMetadata": true,
  "agmMetadataMaxBytes": 65536,
  "gameEventBroadcast": true,
  "gameEventBroadcastAction": "com.winlator.secure.event.GAME_EVENT",
  "managedLaunchReturnsToCaller": true,
  "gameEventSenderPermission": "com.winlator.secure.permission.SEND_GAME_EVENTS",
  "installerProgress": true,
  "asyncInstallers": true,
  "asyncInstallerOptIn": true,
  "asyncInstallerExtra": "async_installer",
  "sameProfilePaths": true,
  "safUri": false,
  "listPagination": true,
  "recommendedListPageSize": 8,
  "managedGameConfiguration": true,
  "unityTextureLimitOverlay": true,
  "gameConfigSchemaVersion": 1,
  "configSchemaPath": "config-schema",
  "configConflictDetection": true,
  "configUpdateExtra": "config_update_json",
  "managedDiagnostics": true,
  "diagnosticsSchemaVersion": 1,
  "diagnosticHistory": true,
  "diagnosticHistoryPerGame": 20,
  "gameEventDiagnosticRefs": true,
  "diagnosticsPath": "diagnostics",
  "managedGameSettings": true,
  "gameSettingsSchemaVersion": 1,
  "settingsSchemaPath": "settings-schema",
  "settingsConflictDetection": true,
  "settingsUpdateExtra": "settings_update_json",
  "perGameLocalization": true,
  "perGameOcrProfiles": true,
  "automaticOcrScriptSelection": true,
  "tiledStrongOcr": true,
  "translationCache": true,
  "translationCachePath": "translation-cache",
  "quickControlProfiles": true,
  "perGameControlsProfile": true,
  "performancePresets": true,
  "configurationSnapshots": true,
  "snapshotHistoryPerGame": 20,
  "snapshotsPath": "snapshots",
  "crashLoopRecovery": true,
  "recoveryPath": "recovery",
  "perGameModManager": true,
  "modsPath": "mods",
  "runtimeDependencyManager": true,
  "dependenciesPath": "dependencies",
  "agmPrerequisiteBootstrap": true,
  "agmSharedContainerKey": "agm.default",
  "configSuggestions": true,
  "configSuggestionsPath": "suggested-config",
  "runnerRecommendation": true,
  "scopedApprovedIntegrations": true,
  "integrationRevocation": true,
  "integrationScopes": [
    "read", "manage_games", "settings", "launch",
    "mods", "dependencies", "recovery", "global_ui"
  ],
  "globalSettings": true,
  "globalSettingsPath": "global-settings",
  "actions": [
    "com.winlator.secure.action.GET_CAPABILITIES",
    "com.winlator.secure.action.LIST_GAMES",
    "com.winlator.secure.action.GET_GAME",
    "com.winlator.secure.action.CREATE_GAME",
    "com.winlator.secure.action.CONFIGURE_GAME",
    "com.winlator.secure.action.RUN_INSTALLER",
    "com.winlator.secure.action.LAUNCH_GAME",
    "com.winlator.secure.action.DELETE_GAME",
    "com.winlator.secure.action.MOVE_GAME_TO_ISOLATED",
    "com.winlator.secure.action.CREATE_SNAPSHOT",
    "com.winlator.secure.action.CLEAR_TRANSLATION_CACHE",
    "com.winlator.secure.action.CONFIGURE_GLOBAL_SETTINGS",
    "com.winlator.secure.action.OPEN_RECOVERY",
    "com.winlator.secure.action.OPEN_MOD_MANAGER",
    "com.winlator.secure.action.OPEN_DEPENDENCY_MANAGER"
  ],
  "configFields": [
    "screenSize",
    "envVars",
    "cpuList",
    "cpuListWoW64",
    "graphicsDriver",
    "graphicsDriverConfig",
    "dxwrapper",
    "dxwrapperConfig",
    "audioDriver",
    "audioDriverConfig",
    "wincomponents",
    "hudMode",
    "startupSelection",
    "box64Preset",
    "desktopTheme",
    "forceFullscreen",
    "unityTextureLimit"
  ]
}
```

## API v5 scopes

Authorization is bound to the real calling package and its installed signing certificate. Caller-supplied package extras are never trusted.

| Scope | Operations |
|---|---|
| `read` | Provider queries, capabilities, list/get games, and targeted event delivery |
| `manage_games` | Create, delete, and move games |
| `settings` | Configure games and clear translation caches |
| `launch` | Launch games and run installers |
| `mods` | Open the selected game's mod manager |
| `dependencies` | Open the selected container's dependency manager |
| `recovery` | Create snapshots and open recovery |
| `global_ui` | Configure global OCR, control, and performance defaults |

Unauthenticated callers receive `UNAUTHORIZED`. An approved caller missing the required scope receives `SCOPE_DENIED`.

## Unified settings

Query `content://com.winlator.secure.agm/settings-schema` before rendering settings. Per-game effective settings are available at:

```text
content://com.winlator.secure.agm/games/{encoded_game_id}/settings
```

The `settings_json` cursor column contains:

```json
{
  "settingsJson": {
    "runtime": {},
    "localization": {
      "gameLanguage": "ja",
      "runtimeLocale": "ja_JP.UTF-8"
    },
    "ocr": {
      "mode": "SUBTITLE",
      "scriptMode": "AUTO",
      "script": "JAPANESE",
      "sourceLanguage": "ja",
      "targetLanguage": "en",
      "intervalMillis": 750,
      "captureRegion": "0,0,1,1",
      "preprocessing": "AUTO",
      "replacements": "",
      "tiledStrongOcr": true,
      "translationCacheEnabled": true,
      "translationCacheMaxEntries": 1000
    },
    "controls": {
      "buttonOrder": ["fullscreen", "input_mode", "game_text", "strong_ocr", "exit"],
      "visibleButtons": ["fullscreen", "input_mode", "game_text", "strong_ocr", "exit"],
      "buttonSize": "MEDIUM",
      "opacity": 0.85,
      "expanded": true
    },
    "input": {
      "controlsProfileId": 0
    },
    "performance": {
      "preset": "CUSTOM"
    }
  },
  "settingsSha256": "..."
}
```

`runtime` is the complete API v4 configuration object. `runtimeLocale` determines the Wine locale and code page for that game's launches and installers without permanently changing a shared container. Supported values and every field constraint are authoritative in the schema.

Locales not already present in the rootfs are generated into a persistent per-locale
directory before Wine starts. If preparation fails, Winlator emits `game_exited` with
`success=false`, `error_code=LAUNCH_FAILED`, `runtime_reached=false`, and a managed
diagnostic `report_id`; it does not start the game with a different locale.

Strong OCR preprocessing supports `AUTO`, `COLOR`, `HIGH_CONTRAST`, `BRIGHT_TEXT`, and
`OUTLINED_TEXT`. `AUTO` includes native- and enhanced-scale outline reconstruction for
layered game fonts.

Update settings through `CONFIGURE_GAME` using `settings_update_json`:

```json
{
  "baseSettingsSha256": "...",
  "set": {
    "localization": {
      "gameLanguage": "ja",
      "runtimeLocale": "ja_JP.UTF-8"
    },
    "ocr": {
      "scriptMode": "AUTO",
      "preprocessing": "AUTO",
      "tiledStrongOcr": true
    }
  }
}
```

On a stale hash, Winlator returns `SETTINGS_CONFLICT` and the current `game_json`. Do not automatically retry. Do not combine `config_json` or `config_update_json` with `settings.runtime`, and do not combine an explicit runtime patch with a non-`CUSTOM` performance preset.

`CREATE_GAME` may accept `settings_json` as a schema-validated initial settings patch. New games otherwise inherit the current global OCR, control, and performance defaults.

Global effective defaults are read from `content://com.winlator.secure.agm/global-settings` in `global_settings_json`, using the same `{settingsJson, settingsSha256}` envelope. Update them with `CONFIGURE_GLOBAL_SETTINGS` and `settings_update_json`. Only `ocr`, `controls`, and `performance` are accepted globally.

### On-screen controller profile (`input.controlsProfileId`)

`input` is a per-game (not global) settings namespace. `controlsProfileId` selects which
Winlator on-screen input-controls profile (a virtual controller/keyboard layout) is
auto-enabled when the game launches. `0` means none.

The **available profiles are advertised as the `options` of the `input.controlsProfileId`
enum field in the settings schema** (`settings-schema`), each as `{ "value": <id>, "label": <name> }`,
with a leading `{ "value": 0, "label": "None" }`. Render that dropdown directly from the schema
and refetch the schema to pick up newly created profiles. Winlator resolves the id at launch and
silently ignores a stale/deleted id (no overlay). Set it like any other field:

```json
{ "baseSettingsSha256": "...", "set": { "input": { "controlsProfileId": 3 } } }
```

### Suggested configuration (`suggested-config`)

Query `content://com.winlator.secure.agm/games/{game_id}/suggested-config` to have Winlator
fingerprint the game's on-disk files (engine markers, PE architecture, bundled DirectX/.NET
DLLs) and return a best-effort recommended configuration in `suggested_config_json`:

```json
{
  "gameId": "…",
  "engine": "RPG_MAKER_MV_MZ",
  "engineLabel": "RPG Maker MV/MZ (NW.js)",
  "confidence": "high",
  "architecture": "x86_64",
  "evidence": ["nw.dll", "www"],
  "suggestedConfig": { "launchArguments": "--disable-gpu --in-process-gpu", "box64Preset": "INTERMEDIATE" },
  "suggestedSettings": { "localization": { "runtimeLocale": "ja_JP.UTF-8" } },
  "runnerRecommendation": {
    "winlatorSuitability": "poor",
    "nativeEngineInterpreterPreferred": true,
    "preferredRunner": "joiplay",
    "confidence": "high",
    "reasons": ["RPG Maker XP/VX/VX Ace (RGSS) runs natively in a JoiPlay-style interpreter…"],
    "knownIssues": ["wine-mmdevapi-audio-deadlock"]
  },
  "rationale": ["RPG Maker MV/MZ ships an NW.js/Chromium runtime that frequently shows a black screen…"]
}
```

`suggestedConfig` contains only runtime **config** fields Winlator is confident about (a subset of
the `runtime` config fields); apply it verbatim through `config_update_json` (config surface) after
the user reviews it. `suggestedSettings` (when non-empty) carries **settings**-surface suggestions —
currently `localization.runtimeLocale` for detected Japanese/Chinese/Korean games — and must be
applied through `settings_update_json`, not the config surface. Both objects are always present and
either may be empty. Winlator derives these from the executable's PE import table (e.g. a native
OpenGL game → Zink OpenGL driver to avoid the upside-down default Gladio renderer) and from CJK
scripts in the game's file names. An unrecognized game with no confident suggestion returns
`confidence: "low"` and empty `suggestedConfig`/`suggestedSettings`.

#### Runner recommendation (`runnerRecommendation`)

Gated by capability flag **`runnerRecommendation`** (feature-detect it; absent flag = ignore the
object). When present, `runnerRecommendation` is an advisory letting the manager route a game to a
native engine interpreter (e.g. JoiPlay) versus Winlator, derived purely from the detected engine.
It is **independent of the config suggestions** — it may be populated even when both
`suggestedConfig` and `suggestedSettings` are empty; a missing object means "no recommendation".

| Field | Values | Meaning |
| --- | --- | --- |
| `winlatorSuitability` | `ideal` \| `good` \| `fair` \| `poor` | How well Winlator itself runs this engine. |
| `nativeEngineInterpreterPreferred` | `true` \| `false` | Engine is one a native interpreter (JoiPlay) handles directly (RPG Maker / Ren'Py). |
| `preferredRunner` | `winlator` \| `joiplay` \| `either` | Winlator's advice; **AGM owns the final routing** and should honor `joiplay` only when it is installed and the title is importable. |
| `confidence` | `low` \| `medium` \| `high` | Confidence in the recommendation. |
| `reasons` | string[] | Human-readable UI copy. |
| `knownIssues` | string[] | Stable, kebab-case, append-only tags AGM can branch on (e.g. `wine-mmdevapi-audio-deadlock`). |

Engine → recommendation: RGSS → `joiplay`/`poor` (+`wine-mmdevapi-audio-deadlock`); MV/MZ →
`joiplay`/`fair`; Ren'Py → `joiplay`/`good`; Wolf RPG & KiriKiri → `winlator`/`good`; Unity/Unreal →
`winlator`/`ideal`; Godot/Electron/unknown-native → `winlator`. Treat as an advisory chip/action,
never an automatic runner switch.

## API v5 operational resources

| Resource | URI | Payload column |
|---|---|---|
| Settings schema | `content://com.winlator.secure.agm/settings-schema` | `settings_schema_json` |
| Per-game settings | `content://com.winlator.secure.agm/games/{game_id}/settings` | `settings_json` |
| Global settings | `content://com.winlator.secure.agm/global-settings` | `global_settings_json` |
| Snapshots | `content://com.winlator.secure.agm/games/{game_id}/snapshots` | `snapshots_json` |
| Recovery status | `content://com.winlator.secure.agm/games/{game_id}/recovery` | `recovery_json` |
| Translation cache | `content://com.winlator.secure.agm/games/{game_id}/translation-cache` | `cache_status_json` |
| Mods | `content://com.winlator.secure.agm/games/{game_id}/mods` | `mod_status_json` |
| Dependencies | `content://com.winlator.secure.agm/games/{game_id}/dependencies` | `dependency_status_json` |
| Suggested config | `content://com.winlator.secure.agm/games/{game_id}/suggested-config` | `suggested_config_json` |

Additional Activity actions:

| Action | Required extras and result |
|---|---|
| `CREATE_SNAPSHOT` | `game_id`, optional `label`; returns `snapshot_json` |
| `CLEAR_TRANSLATION_CACHE` | `game_id`; returns `cache_status_json` |
| `CONFIGURE_GLOBAL_SETTINGS` | `settings_update_json`; returns `global_settings_json` |
| `OPEN_RECOVERY` | `game_id`; opens confirmed Apply-and-Retry/recovery UI |
| `OPEN_MOD_MANAGER` | `game_id`; opens that game's mod manager |
| `OPEN_DEPENDENCY_MANAGER` | `game_id`; opens the shared container's dependency manager |

External apps can read status and open the management UI, but cannot bypass Winlator confirmation for mod file changes, dependency installation/repair, snapshot restoration, or Apply-and-Retry. Dependency status is container-wide and identifies every affected managed game. Mods are applied only to the selected game root, use tracked manifests/baselines, and report conflicts according to enabled load order.

Only use a feature when its capability is present and enabled. `safUri` is currently `false`; API v4 continues to accept filesystem paths rather than `content://` URIs.

## Activity endpoint

```kotlin
private const val WINLATOR_PACKAGE = "com.winlator.secure"
private const val WINLATOR_API_ACTIVITY = "com.winlator.api.GameManagerActivity"

fun winlatorRequest(action: String) =
    Intent(action).setClassName(WINLATOR_PACKAGE, WINLATOR_API_ACTIVITY)
```

Launch requests through `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`.

```kotlin
val winlatorApi = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    val data = result.data
    val success = data?.getBooleanExtra("success", false) == true
    if (!success) {
        val code = data?.getStringExtra("error_code")
        val message = data?.getStringExtra("error_message")
        // Surface code/message to the user.
    }
}
```

### Actions

| Action | Purpose |
|---|---|
| `com.winlator.secure.action.GET_CAPABILITIES` | Query API version and supported features |
| `com.winlator.secure.action.LIST_GAMES` | Return all AGM-managed Winlator games |
| `com.winlator.secure.action.GET_GAME` | Return one managed game |
| `com.winlator.secure.action.CREATE_GAME` | Create a game using an isolated or shared container |
| `com.winlator.secure.action.CONFIGURE_GAME` | Update game metadata, executable, title, arguments, or container settings |
| `com.winlator.secure.action.RUN_INSTALLER` | Run an installer/update in an existing container |
| `com.winlator.secure.action.LAUNCH_GAME` | Launch the configured executable |
| `com.winlator.secure.action.DELETE_GAME` | Delete one managed record and reclaim only an unreferenced isolated container |
| `com.winlator.secure.action.MOVE_GAME_TO_ISOLATED` | Clone a shared container and reassign one game to the clone |

### Common request extras

| Extra | Type | Meaning |
|---|---|---|
| `game_id` | `String` | Stable AGM identifier: letters, digits, `.`, `_`, `-`; maximum 128 characters |
| `title` | `String` | Display/container name |
| `game_path` | `String` | Existing absolute game directory in the current Android profile |
| `executable_path` | `String` | Existing absolute Android/Unix path to a portable executable |
| `executable_dos_path` | `String` | Executable inside Wine, such as `C:\Program Files\Game\game.exe` |
| `installer_path` | `String` | Existing absolute Android/Unix path to an installer |
| `arguments` | `String` | Raw game executable arguments |
| `installer_arguments` | `String` | Raw installer/update arguments |
| `config_json` | `String` | Optional legacy merge patch for game configuration |
| `config_update_json` | `String` | Optional v4 conflict-safe configuration update |
| `launch_after_create` | `Boolean` | Defaults to `true` |
| `agm_metadata` | `String` | Opaque AGM-owned data, up to 65,536 UTF-8 bytes |
| `async_installer` | `Boolean` | Opt into v2 non-blocking installer results; defaults to `false` |
| `container_policy` | `String` | `shared_default` or `isolated`; omission defaults to `isolated` |
| `container_key` | `String` | Optional only with `shared_default`; omission uses `agm.default`, and public builds reject every other key |

Do not provide both `executable_path` and `executable_dos_path`.

## Shared and isolated containers

Existing callers remain isolated because omitted `container_policy` means `isolated`.

AGM should explicitly request its default shared container:

```kotlin
val request = winlatorRequest("com.winlator.secure.action.CREATE_GAME").apply {
    putExtra("game_id", gameId)
    putExtra("title", title)
    putExtra("installer_path", installerPath)
    putExtra("container_policy", "shared_default")
    // Optional. Omit this to use the stable integration default "agm.default".
    // putExtra("container_key", "agm.default")
}
winlatorApi.launch(request)
```

Winlator creates and provisions `agm.default` during first-launch prerequisite setup. Later shared games reuse its numeric container ID. The numeric ID is an implementation detail and must never be cached as AGM's shared-container identity.

To force a private prefix, omit the policy or send:

```kotlin
putExtra("container_policy", "isolated")
```

Each managed record retains its own title, executable, arguments, metadata, state, path mappings, and `config_json`. Before configure, installer, or launch work, Winlator applies the selected record's mappings and saved configuration to its associated container. Shared-container settings therefore do not replace another game's saved settings.

Public builds accept only `container_key=agm.default`. It is rejected for isolated creation.

`CREATE_GAME` is idempotent by `game_id`. If the ID already exists with the same original
portable/installer paths and container policy, Winlator returns the authoritative existing
game with `create_reconciled=true` and does not recreate a container, rerun an installer,
or launch the game. Records created before creation identity was persisted can reconcile
portable paths; legacy installer records return `GAME_ALREADY_EXISTS` because their
original installer path cannot be validated. If immutable identity differs, Winlator
returns `GAME_ALREADY_EXISTS` with the authoritative `game_json`; callers must not
overwrite or delete that record automatically.

### Shared-container prerequisites

Before AGM can create, install, or launch a `shared_default` game, the user must
complete Winlator's first-launch Windows prerequisite setup. Winlator downloads
the selected Microsoft installers directly from Microsoft, verifies their
pinned SHA-256 values, and installs them sequentially into `agm.default`.
Microsoft files are not included in the APK or update feed.

If setup is incomplete or verification fails, shared operations return
`RUNTIME_PREREQUISITES_REQUIRED`. Open Winlator or use **Settings >
Manage/Reinstall Prerequisites**, complete setup, then retry the AGM operation.
The dependency status payload includes `bootstrap` with its plan version,
state, container ID, selected/completed package IDs, current package, and last
error.

## Silent read provider

API v5 provides authenticated read operations without launching an Activity and without consulting the mutation or Wine-session launch locks.

Authority:

```text
com.winlator.secure.agm
```

| Operation | URI | Payload column |
|---|---|---|
| Capabilities | `content://com.winlator.secure.agm/capabilities` | `capabilities_json` |
| Configuration schema | `content://com.winlator.secure.agm/config-schema` | `config_schema_json` |
| List games | `content://com.winlator.secure.agm/games` | `games_json` |
| Get game | `content://com.winlator.secure.agm/games/{encoded_game_id}` | `game_json` |
| Diagnostic report | `content://com.winlator.secure.agm/diagnostics/{encoded_report_id}` | `report_json` |
| Diagnostic history | `content://com.winlator.secure.agm/games/{encoded_game_id}/diagnostics?limit=10` | `diagnostics_json` |

Every query returns a one-row cursor with these fixed columns:

```text
_id
success
api_version
error_code
error_message
capabilities_json
config_schema_json
settings_schema_json
settings_json
games_json
game_json
report_json
diagnostics_json
snapshots_json
recovery_json
cache_status_json
global_settings_json
mod_status_json
dependency_status_json
has_more
next_offset
```

Only the payload column for the requested operation is populated. `success` is `1` or `0`. A missing game returns `GAME_NOT_FOUND`; a missing report returns `DIAGNOSTIC_NOT_FOUND`. Diagnostic history `limit` must be between 1 and 20. An unauthorized caller receives a `SecurityException` whose message begins with `UNAUTHORIZED`.

Because each record may contain 64 KiB of metadata, v2 callers should page list queries:

```text
content://com.winlator.secure.agm/games?offset=0&limit=8
```

`limit` must be between 1 and 100. Read `has_more` and `next_offset` from the cursor, then request the next page. Omitting both parameters preserves the original unpaged `LIST_GAMES` behavior.

The Activity `LIST_GAMES` action supports the same pagination through integer extras `offset` and `limit`, returning `has_more` and `next_offset` result extras.

```kotlin
fun queryWinlatorJson(uri: Uri, payloadColumn: String): String {
    contentResolver.query(uri, null, null, null, null).use { cursor ->
        require(cursor != null && cursor.moveToFirst())
        val success = cursor.getInt(cursor.getColumnIndexOrThrow("success")) != 0
        if (!success) {
            val code = cursor.getString(cursor.getColumnIndexOrThrow("error_code"))
            val message = cursor.getString(cursor.getColumnIndexOrThrow("error_message"))
            error("$code: $message")
        }
        return cursor.getString(cursor.getColumnIndexOrThrow(payloadColumn))
    }
}

val gamesJson = queryWinlatorJson(
    Uri.parse("content://com.winlator.secure.agm/games?offset=0&limit=8"),
    "games_json"
)

val gameJson = queryWinlatorJson(
    Uri.parse("content://com.winlator.secure.agm/games/${Uri.encode(gameId)}"),
    "game_json"
)
```

The provider is read-only. `insert`, `update`, and `delete` are unsupported.

## AGM-owned metadata

`agm_metadata` is stored verbatim. Winlator never parses, normalizes, or validates it as JSON. AGM may use any string representation, but JSON is recommended.

The maximum is 65,536 UTF-8 bytes. Larger values fail with `METADATA_TOO_LARGE`. Sending an empty string stores and returns an empty string; sending the extra with a null value clears it.

It may be supplied during creation or updated independently through `CONFIGURE_GAME`:

```kotlin
val metadata = """
    {
      "catalogMappingId": "catalog-12345",
      "version": "0.9.4",
      "joiplayGameId": "jp-42"
    }
""".trimIndent()

winlatorApi.launch(
    winlatorRequest("com.winlator.secure.action.CONFIGURE_GAME").apply {
        putExtra("game_id", gameId)
        putExtra("agm_metadata", metadata)
    }
)
```

`GET_GAME` and every `LIST_GAMES` entry echo the value as the exact `agm_metadata` JSON string field.

## Portable game

The game remains in shared Android storage. Winlator maps its folder into the private container and runs the executable directly.

```kotlin
val request = winlatorRequest("com.winlator.secure.action.CREATE_GAME").apply {
    putExtra("game_id", gameId)
    putExtra("title", title)
    putExtra("game_path", "/storage/emulated/150/Games/MyGame")
    putExtra("executable_path", "/storage/emulated/150/Games/MyGame/Game.exe")
    putExtra("launch_after_create", true)
}
winlatorApi.launch(request)
```

When the launched Wine session exits, AGM receives the `game_exited` broadcast described below.

## Windows installers

### Backward-compatible mode

Omitting `async_installer`, or setting it to `false`, preserves the v1 Activity Result and game-state behavior.

```kotlin
val request = winlatorRequest("com.winlator.secure.action.CREATE_GAME").apply {
    putExtra("game_id", gameId)
    putExtra("title", title)
    putExtra("installer_path", "/storage/emulated/150/Download/setup.exe")
    putExtra("executable_dos_path", "C:\\Program Files\\My Game\\game.exe")
    putExtra("installer_arguments", "/S")
    putExtra("launch_after_create", true)
}
winlatorApi.launch(request)
```

If the final executable is unknown, omit `executable_dos_path`. After installation, configure it:

```kotlin
winlatorApi.launch(
    winlatorRequest("com.winlator.secure.action.CONFIGURE_GAME").apply {
        putExtra("game_id", gameId)
        putExtra("executable_dos_path", "C:\\Program Files\\My Game\\game.exe")
    }
)
```

### Non-blocking v2 mode

When `asyncInstallers` and `installerProgress` are advertised, set:

```kotlin
putExtra("async_installer", true)
```

For `CREATE_GAME` with an installer, `async_installer=true` requires `launch_after_create=true`.

Winlator creates or updates the record, sets its state to `installing`, returns the Activity Result, and then launches the installer through an internal deferred service. AGM receives progress and final completion through broadcasts.

```kotlin
val request = winlatorRequest("com.winlator.secure.action.CREATE_GAME").apply {
    putExtra("game_id", gameId)
    putExtra("title", title)
    putExtra("installer_path", "/storage/emulated/150/Download/setup.exe")
    putExtra("async_installer", true)
    putExtra("launch_after_create", true)
}
winlatorApi.launch(request)
```

Run an update in an existing container:

```kotlin
val request = winlatorRequest("com.winlator.secure.action.RUN_INSTALLER").apply {
    putExtra("game_id", gameId)
    putExtra("installer_path", "/storage/emulated/150/Download/update.exe")
    putExtra("installer_arguments", "/S")
    putExtra("async_installer", true)
}
winlatorApi.launch(request)
```

Installer progress uses descriptive `stage` text. Current values include `container_created`, `launching`, `launch_scheduled`, `session_starting`, and `installer_running`. Treat stage values as informational; completion must be determined from `install_completed` or `install_failed`.

An installer is considered successful when its Wine host process exits with status `0`. Closing the installer session before process completion produces `INSTALL_ABORTED`.

## Game and installer event broadcasts

Action:

```text
com.winlator.secure.event.GAME_EVENT
```

Each broadcast is explicitly targeted to every currently installed, certificate-verified integration with the `read` scope. AGM declares a receiver for this action:

```xml
<receiver
    android:name=".winlator.WinlatorGameEventReceiver"
    android:exported="true"
    android:permission="com.winlator.secure.permission.SEND_GAME_EVENTS">
    <intent-filter>
        <action android:name="com.winlator.secure.event.GAME_EVENT" />
    </intent-filter>
</receiver>
```

`SEND_GAME_EVENTS` is a signature-level permission defined and held by Winlator Secure. Requiring it on AGM's exported receiver prevents unrelated apps from spoofing completion events.

Event types:

```text
game_exited
install_progress
install_completed
install_failed
settings_changed
dependency_installed
dependency_failed
```

Common extras:

| Extra | Type | Meaning |
|---|---|---|
| `api_version` | `Int` | Winlator API version |
| `event_type` | `String` | One of the event types above |
| `game_id` | `String` | Managed game identifier |
| `container_id` | `Int` | Winlator container identifier |
| `success` | `Boolean` | Final/progress status |
| `started_at` | `Long` | Session start timestamp in epoch milliseconds |
| `ended_at` | `Long` | Final event timestamp in epoch milliseconds |
| `percent` | `Int` | Optional progress percentage |
| `stage` | `String` | Optional progress description |
| `error_code` | `String` | Present on failure |
| `error_message` | `String` | Present on failure |
| `report_id` | `String` | Optional v4 reference to the full provider report |
| `outcome` | `String` | Optional bounded diagnostic outcome |
| `phase` | `String` | Optional phase reached by the managed session |
| `category` | `String` | Optional deterministic failure category |
| `confidence` | `String` | `low`, `medium`, or `high` |
| `config_health` | `String` | `good`, `bad`, or `unknown` |
| `duration_millis` | `Long` | Managed session duration |
| `applied_config_sha256` | `String` | Configuration hash used by the session |
| `exit_code` | `Int` | Optional guest process exit status |
| `signal` | `Int` | Optional inferred POSIX signal |
| `termination_origin` | `String` | Natural, user, replacement, Winlator teardown, or external-signal origin |
| `runtime_reached` | `Boolean` | Whether a renderable game window appeared |
| `settings_sha256` | `String` | New authoritative settings hash for `settings_changed` |
| `changed_namespace` | `String` | Changed settings namespace |
| `provider_path` | `String` | Relative provider resource to refetch |
| `dependency_id` | `String` | Runtime dependency associated with a dependency event |

Progress example:

```json
{
  "api_version": 7,
  "event_type": "install_progress",
  "game_id": "agm-game-id",
  "container_id": 4,
  "success": true,
  "started_at": 1783827000000,
  "stage": "installer_running"
}
```

Completion example:

```json
{
  "api_version": 7,
  "event_type": "install_completed",
  "game_id": "agm-game-id",
  "container_id": 4,
  "success": true,
  "started_at": 1783827000000,
  "ended_at": 1783827065000
}
```

Failure example:

```json
{
  "api_version": 7,
  "event_type": "install_failed",
  "game_id": "agm-game-id",
  "container_id": 4,
  "success": false,
  "error_code": "INSTALL_FAILED",
  "error_message": "The installer Wine session exited with status 1.",
  "started_at": 1783827000000,
  "ended_at": 1783827065000
}
```

Game exit example:

```json
{
  "api_version": 7,
  "event_type": "game_exited",
  "game_id": "agm-game-id",
  "container_id": 4,
  "success": true,
  "started_at": 1783827000000,
  "ended_at": 1783829000000
}
```

Broadcasts are notifications, not trusted commands. AGM should match `game_id` against its records before triggering post-play scanning or backup.

The provider report is the source of truth. Broadcasts intentionally contain only a bounded summary and report reference.

`ACTION_CONFIGURE_GAME` returns the authoritative mutation outcome. Its successful
`game_json` already contains the committed `configJson`, `configSha256`,
`settingsJson`, and `settingsSha256`. A successful `settings_changed` event is a
post-commit invalidation/refetch notification and always carries `success=true`.
Failed mutations return synchronously with an error code and do not emit
`settings_changed`.

## Managed-session diagnostics

For managed games and installers, Winlator captures bounded Wine and Box64 output even when the global debug UI is disabled. It classifies failures locally from exit status, session phase, runtime state, and fixed log signatures; no AI or network service is involved.

Reports are retained for the newest 20 sessions per game and 500 sessions globally. Each report is at most 32 KiB and contains at most eight redacted evidence entries of 512 characters each. Private app paths and Android profile IDs are redacted.

Example report:

```json
{
  "classificationVersion": 1,
  "reportId": "b607...",
  "sessionId": "643e...",
  "gameId": "agm-game-id",
  "containerId": 4,
  "startedAt": 1783827000000,
  "endedAt": 1783827005000,
  "durationMillis": 5000,
  "outcome": "crashed",
  "phase": "startup",
  "category": "box64_segfault",
  "confidence": "high",
  "terminationOrigin": "external_signal",
  "exitCode": 139,
  "signal": 11,
  "runtimeReached": false,
  "configHealth": "bad",
  "appliedConfigSha256": "9F9BBD8D4F...",
  "appliedConfig": {},
  "evidence": [
    {"source": "guest", "message": "Segmentation fault ..."}
  ],
  "suggestions": [
    {
      "id": "box64_stability",
      "label": "Use the Box64 stability preset",
      "reason": "The guest process crashed inside Box64.",
      "baseConfigSha256": "9F9BBD8D4F...",
      "set": {"box64Preset": "STABILITY"}
    }
  ]
}
```

`terminationOrigin` records whether the process ended naturally, through an explicit
`user_exit`, because a `session_replaced` it, during `winlator_teardown`, or through
an unrequested `external_signal`. It is independent from the failure category, so a
later teardown SIGKILL does not hide stronger guest evidence.

Known categories include guest virtual-address OOM, Media Foundation/GStreamer
pipeline failures, missing native or Windows dependencies, segmentation faults,
illegal instructions, possible memory pressure, graphics/audio initialization,
path or permission failures, early process exits, abnormal runtime exits, clean
exits, and interrupted app sessions. Specific OOM, media, and missing-library
evidence takes precedence over a generic exit status 137.

`configHealth=good` requires a renderable runtime window, at least 60 seconds of execution, and a clean or explicit user exit. Suggestions are advisory conflict-safe patches. AGM must show the diff and require explicit user confirmation before applying or retrying; Winlator never changes settings or retries automatically.

## Secure Folder and multi-user paths

All absolute paths are resolved by Winlator inside its own Android profile. AGM and Winlator must be installed in the same profile.

For emulated storage, Winlator canonicalizes the path and verifies that it is under the current profile's external-storage root. A Secure Folder installation may therefore use paths such as:

```text
/storage/emulated/150/Games/MyGame
/storage/emulated/150/Download/setup.exe
```

A path under another profile's `/storage/emulated/{userId}` fails with `PATH_OUTSIDE_PROFILE`. Other paths must be visible and accessible to Winlator's process. Profile isolation and normal Android filesystem permissions remain enforced.

API v4 does not accept SAF tree/document URIs. `safUri=false` explicitly advertises this. AGM must resolve files to accessible absolute paths or keep using its own SAF workflow for copying/deleting shared data.

## Configure

`CONFIGURE_GAME` accepts common mutable fields and either legacy `config_json` or v4 `config_update_json`. Do not send both in one request.

`config_json` keeps its original merge semantics for compatibility. It accepts known fields and preserves omitted values.

For settings UI, AGM must query `/config-schema`, preserve values it does not understand, refetch the current game immediately before saving, and send a conflict-safe update:

```json
{
  "baseConfigSha256": "9F9BBD8D4F...",
  "set": {
    "box64Preset": "STABILITY",
    "screenSize": "1280x720"
  }
}
```

`baseConfigSha256` must equal the current game's `configSha256`. On mismatch, Winlator returns `CONFIG_CONFLICT` plus the current `game_json`; AGM should show the changed values and require an explicit retry. There is no remove operation: reset a field by setting the default advertised by the schema.

The schema is authoritative for field types, editor types, defaults, options, dependencies, and serialization formats. Do not hardcode driver or Box64 identifiers. Current unknown values should remain visible and unchanged unless the user explicitly selects a supported replacement.

The complete configuration shape currently includes:

```json
{
  "screenSize": "1280x720",
  "envVars": "WINEESYNC=1",
  "cpuList": "0,1,2,3",
  "cpuListWoW64": "0,1,2,3",
  "graphicsDriver": "turnip,gladio",
  "graphicsDriverConfig": "",
  "dxwrapper": "dxvk",
  "dxwrapperConfig": "",
  "audioDriver": "alsa",
  "audioDriverConfig": "",
  "wincomponents": "direct3d=0,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,vcrun2005=0,vcrun2010=0,wmdecoder=0",
  "hudMode": 0,
  "startupSelection": 1,
  "box64Preset": "INTERMEDIATE",
  "desktopTheme": "LIGHT,IMAGE,#0277bd",
  "forceFullscreen": true,
  "unityTextureLimit": "off"
}
```

Only send fields AGM intends to override. Unknown configuration fields and invalid v4 option values are rejected.
`forceFullscreen` is a Boolean equivalent to Winlator shortcut settings' **Force Fullscreen** option.
`unityTextureLimit` accepts `off`, `1`, `2`, or `3`. Values 1-3 reduce Unity texture
resolution by selecting lower existing mip levels. Winlator creates a content-addressed
shadow game tree inside the container and launches the patched
`globalgamemanagers` copy from there; shared game files are never modified. Unsupported
Unity layouts, versions outside Unity 2019.4–2022.3 LTS, big-endian files, and files
without embedded type trees log a diagnostic and launch the original game unchanged.
Independently of `unityTextureLimit`, managed Unity launches also detect the specific
case where CP932 product-name bytes were extracted as CP437 mojibake. When `_Data/app.info`
exactly confirms the reversible original product name, Winlator adds corrected `.exe`,
`_Data`, and optional `_BurstDebugInformation_DoNotShip` aliases in the same
container-private immutable shadow tree and launches the corrected alias. Ambiguous or
non-reversible names are never changed, and shared game files remain untouched.

## Game records

```json
{
  "id": "agm-game-id",
  "title": "Game title",
  "containerId": 4,
  "containerPolicy": "shared_default",
  "containerKey": "agm.default",
  "containerShared": true,
  "containerReferenceCount": 3,
  "containerAllocatedSizeBytes": 343932928,
  "gamePath": "/storage/emulated/150/Games/Game",
  "executablePath": "/storage/emulated/150/Games/Game/Game.exe",
  "arguments": "",
  "agm_metadata": "{\"catalogMappingId\":\"catalog-12345\",\"version\":\"0.9.4\"}",
  "state": "ready",
  "createdAt": 1783827000000,
  "updatedAt": 1783827000000,
  "containerPresent": true,
  "configJson": {},
  "configSha256": "9F9BBD8D4F...",
  "containerConfig": {},
  "settingsJson": {},
  "settingsSha256": "A81C..."
}
```

`configJson` is the complete effective API v4 runtime configuration snapshot. `configSha256` is its conflict digest and `containerConfig` is a compatibility alias. `settingsJson` is the complete effective v5 settings object and `settingsSha256` is its independent conflict digest. `containerAllocatedSizeBytes` is omitted when Winlator cannot calculate allocated storage safely. `containerKey` is omitted for isolated records. Legacy records migrate to `containerPolicy: "isolated"` without changing their game or container IDs.

Only one executable-path field is present. Stable states are:

```text
ready
setup_required
installing
```

`installing` is used only for an opted-in asynchronous installer. Final installer completion restores the record to `ready` or `setup_required`; failure restores its previous state.

## Launch

```kotlin
winlatorApi.launch(
    winlatorRequest("com.winlator.secure.action.LAUNCH_GAME")
        .putExtra("game_id", gameId)
)
```

Winlator validates the container and executable before launching. A managed launch emits `game_exited` when the Wine session ends, closes the Winlator session, and returns to the calling app instead of opening Winlator's containers window. Launch settings are read from the saved game configuration; `LAUNCH_GAME` itself currently accepts only `game_id`.

For API v5 managed launches, `game_exited.success` reflects the classified session
outcome. Non-zero process exits and startup aborts report `success=false`; callers should
use `outcome`, `phase`, `runtime_reached`, and `report_id` to distinguish startup failure
from a crash after the game reached runtime.

## Delete

```kotlin
winlatorApi.launch(
    winlatorRequest("com.winlator.secure.action.DELETE_GAME")
        .putExtra("game_id", gameId)
)
```

This always deletes only the selected managed-game record first:

- Shared containers are preserved, including after their final game record is deleted.
- A container referenced by another managed game is preserved.
- An isolated container is removed only when the selected game is its final reference.
- Shared Android game/download files are never deleted.

Successful deletion returns `container_deleted`, `container_preserved`, and the post-delete `container_reference_count`.

## Move a shared game to isolation

Action:

```text
com.winlator.secure.action.MOVE_GAME_TO_ISOLATED
```

Request:

```kotlin
winlatorApi.launch(
    winlatorRequest("com.winlator.secure.action.MOVE_GAME_TO_ISOLATED")
        .putExtra("game_id", gameId)
)
```

Winlator checks available internal storage, clones the complete current container through a staged directory, atomically publishes the clone, applies the selected game's saved configuration, and reassigns only that game record. Other games remain on the original shared container. A failed copy or registry reassignment removes the clone and leaves the original association unchanged. Success returns the new `container_id`.

## Activity results

Success uses `Activity.RESULT_OK`:

| Result extra | Type |
|---|---|
| `success` | `Boolean`, always `true` |
| `api_version` | `Int` |
| `game_id` | `String`, when applicable |
| `container_id` | `Int`, when applicable |
| `container_deleted` | `Boolean`, for `DELETE_GAME` |
| `container_preserved` | `Boolean`, for `DELETE_GAME` |
| `container_reference_count` | `Int`, for `DELETE_GAME` |
| `launch_deferred` | `Boolean`, true if creation succeeded but another session won the launch race |
| `create_reconciled` | `Boolean`, true if `CREATE_GAME` returned an identical existing record without repeating side effects |
| `game_json` | `String`, when applicable |
| `games_json` | `String`, for `LIST_GAMES` |
| `capabilities_json` | `String`, for `GET_CAPABILITIES` |
| `has_more` | `Boolean`, for a paged `LIST_GAMES` request |
| `next_offset` | `Int`, for a paged `LIST_GAMES` request |

Failure uses `Activity.RESULT_CANCELED`:

| Result extra | Type |
|---|---|
| `success` | `Boolean`, always `false` |
| `api_version` | `Int` |
| `error_code` | `String` |
| `error_message` | `String` |
| `game_json` | `String`, current game on `CONFIG_CONFLICT` |

Stable error codes:

```text
UNAUTHORIZED
UNSUPPORTED_ACTION
INVALID_ARGUMENT
ROOTFS_NOT_READY
GAME_NOT_FOUND
GAME_ALREADY_EXISTS
CONTAINER_NOT_FOUND
CREATE_FAILED
DELETE_FAILED
CONTAINER_IN_USE
CLONE_FAILED
INSUFFICIENT_STORAGE
SIZE_UNAVAILABLE
EXECUTABLE_NOT_CONFIGURED
PATH_NOT_ACCESSIBLE
WINLATOR_BUSY
OPERATION_IN_PROGRESS
STORAGE_FAILED
METADATA_TOO_LARGE
PATH_OUTSIDE_PROFILE
INSTALL_FAILED
INSTALL_ABORTED
LAUNCH_FAILED
CONFIG_CONFLICT
DIAGNOSTIC_NOT_FOUND
```

Treat unknown codes as generic failures so future API versions remain compatible.

## Compatibility summary

- All v1-v3 Activity actions remain available with the same names.
- Existing extras and result fields retain their meanings.
- Legacy `config_json` retains merge semantics; `config_update_json` is additive.
- Existing `CREATE_GAME` callers that omit `container_policy` still create isolated containers.
- Existing callers that omit `async_installer` retain the prior installer result behavior.
- Existing records migrate without changing game IDs, container IDs, paths, metadata, or lifecycle state.
- Existing error codes are unchanged.
- The Activity-based read actions remain available; the provider is an additional silent path.
