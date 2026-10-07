package com.winlator.api;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

final class ApiEventDispatcher {
    private static final String TAG = "WinlatorApiEvents";

    private ApiEventDispatcher() {
    }

    static void send(Context context, Intent event, String requiredScope) {
        try {
            for (ApiApprovalEntry approval : new ApiApprovalStore(context).list()) {
                if (!approval.hasScope(requiredScope)) continue;
                GameApiAuthorization.AuthResult authorized =
                        GameApiAuthorization.authorizePackage(
                                context,
                                approval.packageName,
                                requiredScope
                        );
                if (!authorized.authorized) continue;
                Intent targeted = new Intent(event);
                targeted.setPackage(approval.packageName);
                context.sendBroadcast(targeted);
            }
        }
        catch (Exception error) {
            Log.e(TAG, "Unable to dispatch an API event", error);
        }
    }
}
