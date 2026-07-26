package io.github.jeannesbryan.nion;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class FaviconBridge {
    private static final String LOCATION =
            "resource://android/assets/nion-favicon/";
    private static final String ID =
            "nion-favicon@local";
    private static final String APP =
            "nion.favicon";
    private static final int MAX_DATA_URL =
            400000;

    public interface Listener {
        void onFavicon(
                GeckoSession session,
                String pageUrl,
                String dataUrl
        );
    }

    private final Listener listener;
    private final Set<GeckoSession> sessions =
            Collections.newSetFromMap(
                    new IdentityHashMap<>()
            );

    private volatile WebExtension extension;

    private final WebExtension.MessageDelegate delegate =
            new WebExtension.MessageDelegate() {
        @Override
        public GeckoResult<Object> onMessage(
                String nativeApp,
                Object message,
                WebExtension.MessageSender sender
        ) {
            if (!APP.equals(nativeApp)) {
                return null;
            }
            if (!(message instanceof JSONObject)) {
                return null;
            }
            if (sender.session == null) {
                return null;
            }

            JSONObject json = (JSONObject) message;
            if (!"favicon".equals(json.optString("type", ""))) {
                return null;
            }

            String pageUrl =
                    json.optString("pageUrl", "");
            String dataUrl =
                    json.optString("dataUrl", "");

            if (
                    pageUrl.isEmpty() ||
                    !(pageUrl.startsWith("http://") ||
                      pageUrl.startsWith("https://"))
            ) {
                return null;
            }

            if (
                    !dataUrl.startsWith("data:image/") ||
                    dataUrl.length() > MAX_DATA_URL
            ) {
                return null;
            }

            listener.onFavicon(
                    sender.session,
                    pageUrl,
                    dataUrl
            );
            return null;
        }
    };

    public FaviconBridge(
            GeckoRuntime runtime,
            Listener listener
    ) {
        this.listener = listener;

        runtime.getWebExtensionController()
                .ensureBuiltIn(LOCATION, ID)
                .accept(
                        this::extensionReady,
                        error -> { }
                );
    }

    private void extensionReady(
            WebExtension value
    ) {
        extension = value;

        List<GeckoSession> copy;
        synchronized (sessions) {
            copy = new ArrayList<>(sessions);
        }

        for (GeckoSession session : copy) {
            attach(session, value);
        }
    }

    public void registerSession(
            GeckoSession session
    ) {
        synchronized (sessions) {
            sessions.add(session);
        }

        WebExtension current = extension;
        if (current != null) {
            attach(session, current);
        }
    }

    public void unregisterSession(
            GeckoSession session
    ) {
        synchronized (sessions) {
            sessions.remove(session);
        }

        WebExtension current = extension;
        if (current == null) {
            return;
        }

        try {
            session.getWebExtensionController()
                    .setMessageDelegate(
                            current,
                            null,
                            APP
                    );
        } catch (Exception ignored) {
        }
    }

    private void attach(
            GeckoSession session,
            WebExtension value
    ) {
        session.getWebExtensionController()
                .setMessageDelegate(
                        value,
                        delegate,
                        APP
                );
    }
}
