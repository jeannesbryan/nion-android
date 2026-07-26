package io.github.jeannesbryan.nion;

import android.net.Uri;

import java.util.List;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

public final class TabNavigationDelegate
        implements GeckoSession.NavigationDelegate {

    public interface Listener {
        void onLocationChanged(
                GeckoSession session,
                String url
        );

        void onCanGoBackChanged(
                GeckoSession session,
                boolean canGoBack
        );

        void onCanGoForwardChanged(
                GeckoSession session,
                boolean canGoForward
        );

        GeckoSession onNewSessionRequested(
                GeckoSession session,
                String uri
        );

        boolean shouldAllowInsecureHttp(
                GeckoSession session,
                String uri
        );

        void onHttpsUpgradeRequested(
                GeckoSession session,
                String originalHttpUri,
                String httpsUri
        );
    }

    private final Listener listener;

    public TabNavigationDelegate(Listener listener) {
        this.listener = listener;
    }

    private boolean isAllowedTopLevelUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            String scheme =
                    Uri.parse(value).getScheme();

            if (scheme == null) {
                return false;
            }

            return scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("about");

        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isHttpClearnet(String value) {
        try {
            Uri uri =
                    Uri.parse(value);

            String scheme =
                    uri.getScheme();

            String host =
                    uri.getHost();

            if (
                    scheme == null ||
                    host == null
            ) {
                return false;
            }

            return scheme.equalsIgnoreCase("http")
                    && !host.toLowerCase()
                    .endsWith(".onion");

        } catch (Exception ignored) {
            return false;
        }
    }

    private String toHttps(String value) {
        try {
            return Uri.parse(value)
                    .buildUpon()
                    .scheme("https")
                    .build()
                    .toString();

        } catch (Exception ignored) {
            return value;
        }
    }

    @Override
    public GeckoResult<AllowOrDeny> onLoadRequest(
            GeckoSession session,
            LoadRequest request
    ) {
        String uri =
                request.uri;

        if (!isAllowedTopLevelUri(uri)) {
            return GeckoResult.fromValue(
                    AllowOrDeny.DENY
            );
        }

        if (isHttpClearnet(uri)) {
            if (
                    listener.shouldAllowInsecureHttp(
                            session,
                            uri
                    )
            ) {
                return GeckoResult.fromValue(
                        AllowOrDeny.ALLOW
                );
            }

            listener.onHttpsUpgradeRequested(
                    session,
                    uri,
                    toHttps(uri)
            );

            return GeckoResult.fromValue(
                    AllowOrDeny.DENY
            );
        }

        return GeckoResult.fromValue(
                AllowOrDeny.ALLOW
        );
    }

    @Override
    public void onLocationChange(
            GeckoSession session,
            String url,
            List<GeckoSession.PermissionDelegate.ContentPermission> perms,
            Boolean hasUserGesture
    ) {
        listener.onLocationChanged(
                session,
                url
        );
    }

    @Override
    public void onCanGoBack(
            GeckoSession session,
            boolean canGoBack
    ) {
        listener.onCanGoBackChanged(
                session,
                canGoBack
        );
    }

    @Override
    public void onCanGoForward(
            GeckoSession session,
            boolean canGoForward
    ) {
        listener.onCanGoForwardChanged(
                session,
                canGoForward
        );
    }

    @Override
    public GeckoResult<GeckoSession> onNewSession(
            GeckoSession session,
            String uri
    ) {
        if (!isAllowedTopLevelUri(uri)) {
            return null;
        }

        GeckoSession newSession =
                listener.onNewSessionRequested(
                        session,
                        uri
                );

        if (newSession == null) {
            return null;
        }

        return GeckoResult.fromValue(
                newSession
        );
    }
}
