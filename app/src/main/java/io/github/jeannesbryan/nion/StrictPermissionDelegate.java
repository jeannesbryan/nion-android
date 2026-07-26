package io.github.jeannesbryan.nion;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

public final class StrictPermissionDelegate
        implements GeckoSession.PermissionDelegate {

    @Override
    public void onAndroidPermissionsRequest(
            GeckoSession session,
            String[] permissions,
            Callback callback) {
        callback.reject();
    }

    @Override
    public GeckoResult<Integer> onContentPermissionRequest(
            GeckoSession session,
            ContentPermission permission) {
        return GeckoResult.fromValue(
                ContentPermission.VALUE_DENY
        );
    }

    @Override
    public void onMediaPermissionRequest(
            GeckoSession session,
            String uri,
            MediaSource[] video,
            MediaSource[] audio,
            MediaCallback callback) {
        callback.reject();
    }
}
