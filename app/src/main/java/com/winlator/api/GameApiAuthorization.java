package com.winlator.api;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.winlator.BuildConfig;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class GameApiAuthorization {
    private GameApiAuthorization() {
    }

    static boolean isAuthorizedPackage(Context context, String packageName) {
        return authorizePackage(context, packageName, ApiScope.READ).authorized;
    }

    static AuthResult authorizePackage(
            Context context,
            String packageName,
            String requiredScope
    ) {
        if (packageName == null || packageName.isEmpty()) {
            return AuthResult.unauthorized();
        }
        try {
            ApiApprovalEntry approval = new ApiApprovalStore(context).get(packageName);
            if (approval == null || approval.revoked) return AuthResult.unauthorized();
            Set<String> installedCertificates = installedCertificates(context, packageName);
            boolean certificateMatches = false;
            for (String trusted : approval.certificateSha256) {
                if (installedCertificates.contains(trusted)) {
                    certificateMatches = true;
                    break;
                }
            }
            if (!certificateMatches) return AuthResult.unauthorized();
            if (requiredScope != null && !approval.hasScope(requiredScope)) {
                return AuthResult.scopeDenied(packageName, approval.scopes);
            }
            return AuthResult.authorized(packageName, approval.scopes);
        }
        catch (PackageManager.NameNotFoundException |
               NoSuchAlgorithmException |
               org.json.JSONException |
               IOException error) {
            return AuthResult.unauthorized();
        }
    }

    static AuthResult authorizeUid(Context context, int uid, String requiredScope) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return AuthResult.unauthorized();
        AuthResult denied = AuthResult.unauthorized();
        for (String packageName : packages) {
            AuthResult result = authorizePackage(context, packageName, requiredScope);
            if (result.authorized) return result;
            if (result.authenticated) denied = result;
        }
        return denied;
    }

    static boolean isAuthorizedUid(Context context, int uid) {
        return authorizeUid(context, uid, ApiScope.READ).authorized;
    }

    static Set<String> installedCertificates(Context context, String packageName)
            throws PackageManager.NameNotFoundException, NoSuchAlgorithmException {
        PackageManager packageManager = context.getPackageManager();
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
            );
            if (packageInfo.signingInfo == null) return Collections.emptySet();
            signatures = packageInfo.signingInfo.hasMultipleSigners()
                    ? packageInfo.signingInfo.getApkContentsSigners()
                    : packageInfo.signingInfo.getSigningCertificateHistory();
        }
        else {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
            );
            signatures = packageInfo.signatures;
        }
        HashSet<String> result = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                result.add(sha256(signature.toByteArray()));
            }
        }
        return result;
    }

    static String requiredScope(String action) {
        if (GameApiContract.ACTION_GET_CAPABILITIES.equals(action) ||
                GameApiContract.ACTION_LIST_GAMES.equals(action) ||
                GameApiContract.ACTION_GET_GAME.equals(action)) {
            return ApiScope.READ;
        }
        if (GameApiContract.ACTION_CREATE_GAME.equals(action) ||
                GameApiContract.ACTION_DELETE_GAME.equals(action) ||
                GameApiContract.ACTION_MOVE_GAME_TO_ISOLATED.equals(action)) {
            return ApiScope.MANAGE_GAMES;
        }
        if (GameApiContract.ACTION_CONFIGURE_GAME.equals(action)) {
            return ApiScope.SETTINGS;
        }
        if (GameApiContract.ACTION_CLEAR_TRANSLATION_CACHE.equals(action)) {
            return ApiScope.SETTINGS;
        }
        if (GameApiContract.ACTION_CREATE_SNAPSHOT.equals(action) ||
                GameApiContract.ACTION_OPEN_RECOVERY.equals(action)) {
            return ApiScope.RECOVERY;
        }
        if (GameApiContract.ACTION_CONFIGURE_GLOBAL_SETTINGS.equals(action)) {
            return ApiScope.GLOBAL_UI;
        }
        if (GameApiContract.ACTION_OPEN_MOD_MANAGER.equals(action)) {
            return ApiScope.MODS;
        }
        if (GameApiContract.ACTION_OPEN_DEPENDENCY_MANAGER.equals(action)) {
            return ApiScope.DEPENDENCIES;
        }
        if (GameApiContract.ACTION_RUN_INSTALLER.equals(action) ||
                GameApiContract.ACTION_LAUNCH_GAME.equals(action)) {
            return ApiScope.LAUNCH;
        }
        return null;
    }

    private static String sha256(byte[] data) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    static final class AuthResult {
        final boolean authenticated;
        final boolean authorized;
        final String packageName;
        final Set<String> scopes;

        private AuthResult(
                boolean authenticated,
                boolean authorized,
                String packageName,
                Set<String> scopes
        ) {
            this.authenticated = authenticated;
            this.authorized = authorized;
            this.packageName = packageName;
            this.scopes = Collections.unmodifiableSet(new HashSet<>(scopes));
        }

        static AuthResult authorized(String packageName, Set<String> scopes) {
            return new AuthResult(true, true, packageName, scopes);
        }

        static AuthResult scopeDenied(String packageName, Set<String> scopes) {
            return new AuthResult(true, false, packageName, scopes);
        }

        static AuthResult unauthorized() {
            return new AuthResult(false, false, null, Collections.emptySet());
        }
    }
}
