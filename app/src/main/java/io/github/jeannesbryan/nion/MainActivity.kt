package io.github.jeannesbryan.nion

import android.util.Base64

import android.graphics.drawable.BitmapDrawable

import android.graphics.BitmapFactory

import android.graphics.Bitmap

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import org.torproject.jni.TorService
import java.io.File
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private var faviconBridge: FaviconBridge? = null


    companion object {
        private const val SESSION_PREFS =
            "nion_session"

        private const val SESSION_TABS =
            "tabs"

        private const val SESSION_ACTIVE_TAB =
            "active_tab"

        /*
         * Deliberately conservative for mobile memory.
         * Popups are handled separately by Gecko.
         */
        private const val MAX_USER_TABS = 8

        private const val BOOKMARK_PREFS =
            "nion_bookmarks"

        private const val BOOKMARK_ITEMS =
            "items"

        private const val MAX_BOOKMARKS = 256

        private var runtime: GeckoRuntime? = null
        private var runtimeSocksPort: Int = -1
    }

    private data class Bookmark(
        val url: String,
        val title: String
    )

    private data class BrowserTab(
        val session: GeckoSession,
        var url: String = "about:blank",
        var title: String = "",
        var favicon: Bitmap? = null,
        var loadingProgress: Int = 0,
        var restoredPendingLoad: Boolean = false,
        var pendingHttpsFallbackUrl: String? = null,
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false
    )

    private lateinit var geckoView: GeckoView
    private lateinit var addressBar: EditText

    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var reloadButton: Button
    private lateinit var goButton: Button

    private lateinit var newTabButton: Button
    private lateinit var closeTabButton: Button
    private lateinit var bookmarkButton: Button
    private lateinit var clearSiteDataButton: Button
    private lateinit var siteInfoButton: Button
    private lateinit var tabStrip: LinearLayout

    private lateinit var torStatus: TextView
    private lateinit var pageProgress: ProgressBar

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = -1
    private var pendingPopupSession: GeckoSession? = null
    private var restoringSession = false

    private val insecureHttpOnce =
        java.util.IdentityHashMap<
            GeckoSession,
            String
        >()

    private var torService: TorService? = null
    private var serviceBound = false
    private var torWatchThread: Thread? = null

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var browserReady = false

    @Volatile
    private var torEverReady = false

    private val serviceConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?
            ) {
                val localBinder =
                    binder as TorService.LocalBinder

                torService = localBinder.service
                serviceBound = true

                watchTor()
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {
                serviceBound = false
                torService = null

                runOnUiThread {
                    failClosed(
                        "Tor service disconnected"
                    )
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        geckoView =
            findViewById(R.id.geckoview)

        addressBar =
            findViewById(R.id.addressBar)

        backButton =
            findViewById(R.id.backButton)

        forwardButton =
            findViewById(R.id.forwardButton)

        reloadButton =
            findViewById(R.id.reloadButton)

        goButton =
            findViewById(R.id.goButton)

        newTabButton =
            findViewById(R.id.newTabButton)

        closeTabButton =
            findViewById(R.id.closeTabButton)

        bookmarkButton =
            findViewById(R.id.bookmarkButton)

        clearSiteDataButton =
            findViewById(R.id.clearSiteDataButton)

        siteInfoButton =
            findViewById(R.id.siteInfoButton)

        tabStrip =
            findViewById(R.id.tabStrip)

        torStatus =
            findViewById(R.id.torStatus)

        pageProgress =
            findViewById(R.id.pageProgress)

        setBrowserControlsEnabled(false)

        backButton.setOnClickListener {
            currentTab()?.session?.goBack()
        }

        forwardButton.setOnClickListener {
            currentTab()?.session?.goForward()
        }

        reloadButton.setOnClickListener {
            currentTab()?.session?.reload()
        }

        goButton.setOnClickListener {
            loadAddress()
        }

        newTabButton.setOnClickListener {
            if (!browserReady) {
                return@setOnClickListener
            }

            if (tabs.size >= MAX_USER_TABS) {
                Toast.makeText(
                    this,
                    "Maximum $MAX_USER_TABS tabs",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            createTab(
                "about:blank",
                true
            )
        }

        closeTabButton.setOnClickListener {
            if (
                browserReady &&
                currentTabIndex >= 0
            ) {
                closeTab(currentTabIndex)
            }
        }

        bookmarkButton.setOnClickListener {
            if (browserReady) {
                toggleCurrentBookmark()
            }
        }

        bookmarkButton.setOnLongClickListener {
            if (browserReady) {
                showBookmarksDialog()
            }

            true
        }

        clearSiteDataButton.setOnClickListener {
            if (browserReady) {
                confirmClearCurrentSiteData()
            }
        }

        siteInfoButton.setOnClickListener {
            if (browserReady) {
                showCurrentSiteInfo()
            }
        }

        addressBar.setOnEditorActionListener {
                _,
                actionId,
                _ ->

            if (
                actionId ==
                EditorInfo.IME_ACTION_GO
            ) {
                loadAddress()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val tab = currentTab()

                    if (
                        browserReady &&
                        tab?.canGoBack == true
                    ) {
                        tab.session.goBack()
                        return
                    }

                    /*
                     * No web history left:
                     * hand Back to Android.
                     */
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        )

        torStatus.text =
            "Tor: starting..."

        bindService(
            Intent(
                this,
                TorService::class.java
            ),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun currentTab(): BrowserTab? {
        return tabs.getOrNull(
            currentTabIndex
        )
    }

    private fun watchTor() {
        torWatchThread =
            Thread {
                while (
                    !shuttingDown &&
                    serviceBound
                ) {
                    try {
                        val service =
                            torService

                        if (service == null) {
                            runOnUiThread {
                                if (torEverReady) {
                                    failClosed(
                                        "Tor unavailable"
                                    )
                                }
                            }

                            Thread.sleep(500)
                            continue
                        }

                        val control =
                            service
                                .getTorControlConnection()

                        if (control == null) {
                            runOnUiThread {
                                if (torEverReady) {
                                    failClosed(
                                        "Tor connection lost"
                                    )
                                } else {
                                    torStatus.text =
                                        "Tor: starting..."
                                }
                            }

                            Thread.sleep(500)
                            continue
                        }

                        val phase =
                            service.getInfo(
                                "status/bootstrap-phase"
                            )

                        if (phase == null) {
                            runOnUiThread {
                                if (torEverReady) {
                                    failClosed(
                                        "Tor connection lost"
                                    )
                                }
                            }

                            Thread.sleep(500)
                            continue
                        }

                        val progress =
                            Regex(
                                """PROGRESS=(\d+)"""
                            )
                                .find(phase)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()

                        val summary =
                            Regex(
                                """SUMMARY="([^"]*)""""
                            )
                                .find(phase)
                                ?.groupValues
                                ?.getOrNull(1)

                        if (
                            progress != null &&
                            !browserReady
                        ) {
                            runOnUiThread {
                                torStatus.text =
                                    if (
                                        summary.isNullOrBlank()
                                    ) {
                                        "Tor: $progress%"
                                    } else {
                                        "Tor: $progress% — $summary"
                                    }
                            }
                        }

                        if (
                            progress == 100 &&
                            !browserReady
                        ) {
                            val socksPort =
                                service.getSocksPort()

                            if (socksPort > 0) {
                                runOnUiThread {
                                    initializeBrowserThroughTor(
                                        socksPort
                                    )
                                }
                            }
                        }

                        Thread.sleep(500)

                    } catch (
                        _: InterruptedException
                    ) {
                        Thread.currentThread()
                            .interrupt()

                        return@Thread

                    } catch (
                        _: Exception
                    ) {
                        runOnUiThread {
                            if (torEverReady) {
                                failClosed(
                                    "Tor monitoring failure"
                                )
                            }
                        }

                        try {
                            Thread.sleep(500)
                        } catch (
                            _: InterruptedException
                        ) {
                            Thread.currentThread()
                                .interrupt()

                            return@Thread
                        }
                    }
                }
            }.apply {
                name = "NiOn-Tor-Watch"
                start()
            }
    }

    private fun initializeBrowserThroughTor(
        socksPort: Int
    ) {
        if (browserReady) {
            return
        }

        if (runtime == null) {
            val configFile =
                createGeckoTorConfig(
                    socksPort
                )

            val runtimeSettings =
                GeckoRuntimeSettings
                    .Builder()
                    .configFilePath(
                        configFile.absolutePath
                    )
                    .build()

            runtime =
                GeckoRuntime.create(
                    applicationContext,
                    runtimeSettings
                )

            runtimeSocksPort =
                socksPort

        } else if (
            runtimeSocksPort != socksPort
        ) {
            failClosed(
                "Tor proxy port changed — restart NiOn"
            )
            return
        }

        torEverReady = true
        browserReady = true

        setBrowserControlsEnabled(true)

        torStatus.text =
            "Tor: connected — SOCKS 127.0.0.1:$socksPort"

        if (!restoreSessionSnapshot()) {
            createTab(
                "https://check.torproject.org/",
                true
            )
        }
    }

    private fun createTab(
        initialUrl: String,
        makeActive: Boolean,
        loadInitial: Boolean = true,
        openSession: Boolean = true
    ): BrowserTab? {
        val currentRuntime =
            runtime ?: return null

        val newSession =
            GeckoSession()

        newSession.setContentDelegate(
            object :
                GeckoSession.ContentDelegate {

                override fun onTitleChange(
                    session: GeckoSession,
                    title: String?
                ) {
                    val index =
                        findTabIndex(session)

                    if (index < 0) {
                        return
                    }

                    tabs[index].title =
                        title
                            ?.trim()
                            .orEmpty()

                    saveSessionSnapshot()
                    renderTabStrip()
                }

                override fun onCloseRequest(
                    session: GeckoSession
                ) {
                    val index =
                        findTabIndex(session)

                    if (index >= 0) {
                        closeTab(index)
                    }
                }

                override fun onExternalResponse(
                    session: GeckoSession,
                    response: WebResponse
                ) {
                    handleDownloadResponse(
                        response
                    )
                }
            }
        )

        newSession.setPermissionDelegate(
            StrictPermissionDelegate()
        )

        newSession.setNavigationDelegate(
            TabNavigationDelegate(
                object :
                    TabNavigationDelegate.Listener {

                    override fun onLocationChanged(
                        session: GeckoSession,
                        url: String?
                    ) {
                        val index =
                            findTabIndex(session)

                        if (index < 0) {
                            return
                        }

                        tabs[index].url =
                            url ?: "about:blank"

                        saveSessionSnapshot()

                        if (
                            index ==
                            currentTabIndex
                        ) {
                            showCurrentAddress()
                        }

                        renderTabStrip()
                    }

                    override fun onCanGoBackChanged(
                        session: GeckoSession,
                        canGoBack: Boolean
                    ) {
                        val index =
                            findTabIndex(session)

                        if (index < 0) {
                            return
                        }

                        tabs[index].canGoBack =
                            canGoBack

                        if (
                            index ==
                            currentTabIndex
                        ) {
                            updateNavigationButtons()
                        }
                    }

                    override fun onCanGoForwardChanged(
                        session: GeckoSession,
                        canGoForward: Boolean
                    ) {
                        val index =
                            findTabIndex(session)

                        if (index < 0) {
                            return
                        }

                        tabs[index].canGoForward =
                            canGoForward

                        if (
                            index ==
                            currentTabIndex
                        ) {
                            updateNavigationButtons()
                        }
                    }

                    override fun onNewSessionRequested(
                        session: GeckoSession,
                        uri: String
                    ): GeckoSession? {
                        if (
                            tabs.size >=
                            MAX_USER_TABS
                        ) {
                            Toast.makeText(
                                this@MainActivity,
                                "Maximum $MAX_USER_TABS tabs",
                                Toast.LENGTH_SHORT
                            ).show()

                            return null
                        }

                        /*
                         * Gecko requires a newly-created,
                         * still-closed session here.
                         */
                        val newTab =
                            createTab(
                                uri,
                                false,
                                false,
                                false
                            )
                                ?: return null

                        pendingPopupSession =
                            newTab.session

                        return newTab.session
                    }


                    override fun shouldAllowInsecureHttp(
                        session: GeckoSession,
                        uri: String
                    ): Boolean {
                        val allowed =
                            insecureHttpOnce[
                                session
                            ]

                        if (allowed != uri) {
                            return false
                        }

                        insecureHttpOnce.remove(
                            session
                        )

                        return true
                    }

                    override fun onHttpsUpgradeRequested(
                        session: GeckoSession,
                        originalHttpUri: String,
                        httpsUri: String
                    ) {
                        val index =
                            findTabIndex(
                                session
                            )

                        if (index < 0) {
                            return
                        }

                        tabs[index]
                            .pendingHttpsFallbackUrl =
                            originalHttpUri

                        /*
                         * Let onLoadRequest() return DENY
                         * before initiating the replacement
                         * navigation.
                         */
                        geckoView.post {
                            if (
                                browserReady &&
                                findTabIndex(
                                    session
                                ) >= 0
                            ) {
                                session.loadUri(
                                    httpsUri
                                )
                            }
                        }
                    }
                }
            )
        )

        newSession.setProgressDelegate(
            object : GeckoSession.ProgressDelegate {

                override fun onPageStart(
                    session: GeckoSession,
                    url: String
                ) {
                    ensureFaviconBridge()

                    faviconBridge
                        ?.registerSession(session)

                    val faviconTabIndex =
                        findTabIndex(session)

                    if (faviconTabIndex >= 0) {
                        tabs[faviconTabIndex].favicon =
                            null

                        renderTabStrip()
                    }

                    val index =
                        findTabIndex(session)

                    if (index < 0) {
                        return
                    }

                    tabs[index].loadingProgress = 1

                    /*
                     * Gecko has now accepted and opened the
                     * popup session. It is finally safe to
                     * attach it to GeckoView.
                     */
                    if (
                        pendingPopupSession === session
                    ) {
                        pendingPopupSession = null
                        switchToTab(index)
                    }

                    updateLoadingUi()
                }

                override fun onProgressChange(
                    session: GeckoSession,
                    progress: Int
                ) {
                    val index =
                        findTabIndex(session)

                    if (index < 0) {
                        return
                    }

                    tabs[index].loadingProgress =
                        progress.coerceIn(0, 100)

                    if (
                        index ==
                        currentTabIndex
                    ) {
                        updateLoadingUi()
                    }
                }

                override fun onPageStop(
                    session: GeckoSession,
                    success: Boolean
                ) {
                    val index =
                        findTabIndex(session)

                    if (index < 0) {
                        return
                    }

                    val tab =
                        tabs[index]

                    tab.loadingProgress = 100

                    val fallbackUrl =
                        tab.pendingHttpsFallbackUrl

                    if (success) {
                        tab.pendingHttpsFallbackUrl =
                            null
                    }

                    if (
                        index ==
                        currentTabIndex
                    ) {
                        updateLoadingUi()
                    }

                    if (
                        !success &&
                        fallbackUrl != null
                    ) {
                        tab.pendingHttpsFallbackUrl =
                            null

                        if (
                            index ==
                            currentTabIndex &&
                            browserReady
                        ) {
                            showHttpsFallbackDialog(
                                session,
                                fallbackUrl
                            )
                        }
                    }
                }
            }
        )

        if (openSession) {
            newSession.open(currentRuntime)

            /*
             * Background tabs start inactive.
             */
            newSession.setActive(false)
            newSession.setFocused(false)
        }

        val tab =
            BrowserTab(
                session = newSession,
                url = initialUrl
            )

        tabs.add(tab)

        saveSessionSnapshot()

        val newIndex =
            tabs.lastIndex

        renderTabStrip()

        if (makeActive && openSession) {
            switchToTab(newIndex)
        }

        if (loadInitial && openSession) {
            newSession.loadUri(initialUrl)
        }

        return tab
    }

    private fun findTabIndex(
        session: GeckoSession
    ): Int {
        return tabs.indexOfFirst {
            it.session === session
        }
    }

    private fun switchToTab(
        index: Int
    ) {
        if (index !in tabs.indices) {
            return
        }

        val targetTab =
            tabs[index]

        /*
         * Nothing to detach when selecting the tab that is
         * already attached. Still refresh UI state.
         */
        if (
            geckoView.session ===
            targetTab.session
        ) {
            currentTabIndex = index

            targetTab.session.setActive(true)
            targetTab.session.setFocused(true)
            targetTab.session.setPriorityHint(
                GeckoSession.PRIORITY_HIGH
            )

            showCurrentAddress()
            updateNavigationButtons()
            updateLoadingUi()
            renderTabStrip()
            saveSessionSnapshot()

            return
        }

        /*
         * Detach the old display first.
         *
         * releaseSession() does not close or deactivate the
         * GeckoSession, so that is our responsibility.
         */
        geckoView
            .releaseSession()
            ?.apply {
                setFocused(false)
                setActive(false)
                setPriorityHint(
                    GeckoSession.PRIORITY_DEFAULT
                )
            }

        currentTabIndex = index

        /*
         * A restored tab is deliberately kept CLOSED until
         * first selected. Popup sessions may already have
         * been opened by Gecko itself.
         */
        if (!targetTab.session.isOpen) {
            val currentRuntime =
                runtime ?: return

            targetTab.session.open(
                currentRuntime
            )
        }

        /*
         * Do not leave the previous tab's SurfaceView frame
         * visible while this session paints its first frame.
         */
        geckoView.coverUntilFirstPaint(
            Color.WHITE
        )

        geckoView.setSession(
            targetTab.session
        )

        /*
         * Only after it is the visible tab do we mark the
         * session active/focused.
         */
        targetTab.session.setActive(true)
        targetTab.session.setFocused(true)
        targetTab.session.setPriorityHint(
            GeckoSession.PRIORITY_HIGH
        )

        if (targetTab.restoredPendingLoad) {
            targetTab.restoredPendingLoad =
                false

            targetTab.session.loadUri(
                targetTab.url
            )
        }

        showCurrentAddress()
        updateNavigationButtons()
        updateLoadingUi()
        renderTabStrip()

        saveSessionSnapshot()
    }

    private fun closeTab(
        index: Int
    ) {
        if (
            index !in
            tabs.indices
        ) {
            return
        }

        val wasCurrent =
            index == currentTabIndex

        if (wasCurrent) {
            geckoView
                .releaseSession()
                ?.apply {
                    setFocused(false)
                    setActive(false)
                }
        }

        insecureHttpOnce.remove(
            tabs[index].session
        )

        tabs[index]
            .session
            .close()

        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            currentTabIndex = -1

            createTab(
                "about:blank",
                true
            )

            return
        }

        if (wasCurrent) {
            currentTabIndex = -1

            switchToTab(
                min(
                    index,
                    tabs.lastIndex
                )
            )
        } else {
            if (
                index <
                currentTabIndex
            ) {
                currentTabIndex--
            }

            renderTabStrip()
            saveSessionSnapshot()
        }

        saveSessionSnapshot()
    }

    private fun saveSessionSnapshot() {
        if (restoringSession) {
            return
        }

        /*
         * Persist only navigation metadata.
         *
         * Deliberately do NOT persist Gecko SessionState,
         * form values, scroll position, or page contents.
         */
        val array = JSONArray()

        tabs.forEach { tab ->
            val item = JSONObject()

            item.put(
                "url",
                tab.url
            )

            item.put(
                "title",
                tab.title
            )

            array.put(item)
        }

        getSharedPreferences(
            SESSION_PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putString(
                SESSION_TABS,
                array.toString()
            )
            .putInt(
                SESSION_ACTIVE_TAB,
                currentTabIndex
            )
            .apply()
    }

    private fun restoreSessionSnapshot(): Boolean {
        val prefs =
            getSharedPreferences(
                SESSION_PREFS,
                MODE_PRIVATE
            )

        val raw =
            prefs.getString(
                SESSION_TABS,
                null
            )
                ?: return false

        return try {
            val array =
                JSONArray(raw)

            if (array.length() == 0) {
                return false
            }

            /*
             * Read this before creating any tabs.
             */
            val savedIndex =
                prefs.getInt(
                    SESSION_ACTIVE_TAB,
                    0
                )

            restoringSession = true

            /*
             * Background restored tabs are opened as Gecko
             * sessions but not loaded yet. This avoids
             * firing several Tor requests simultaneously
             * during startup.
             */
            val restoreCount =
                min(
                    array.length(),
                    MAX_USER_TABS
                )

            for (i in 0 until restoreCount) {
                val item =
                    array.getJSONObject(i)

                val url =
                    item.optString(
                        "url",
                        "about:blank"
                    )
                        .ifBlank {
                            "about:blank"
                        }

                val title =
                    item.optString(
                        "title",
                        ""
                    )

                val tab =
                    createTab(
                        url,
                        false,
                        false,
                        false
                    )
                        ?: continue

                tab.title = title

                tab.restoredPendingLoad =
                    url != "about:blank"
            }

            if (tabs.isEmpty()) {
                return false
            }

            restoringSession = false

            val safeIndex =
                savedIndex.coerceIn(
                    0,
                    tabs.lastIndex
                )

            switchToTab(safeIndex)

            renderTabStrip()

            true

        } catch (_: Exception) {
            restoringSession = false

            /*
             * Corrupt/old session data must never prevent
             * NiOn from starting.
             */
            prefs.edit()
                .remove(SESSION_TABS)
                .remove(SESSION_ACTIVE_TAB)
                .apply()

            false
        }
    }

    private fun handleDownloadResponse(
        response: WebResponse
    ) {
        val body =
            response.body

        if (body == null) {
            Toast.makeText(
                this,
                "Download contains no data",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val fileName =
            TorDownloadHandler
                .suggestedFileName(
                    response
                )

        val size =
            TorDownloadHandler
                .contentLength(
                    response
                )

        val host =
            try {
                Uri.parse(
                    response.uri
                ).host
            } catch (_: Exception) {
                null
            }

        val details =
            buildString {
                append(fileName)

                if (size != null) {
                    append(
                        "\n"
                    )

                    append(
                        formatDownloadSize(
                            size
                        )
                    )
                }

                if (
                    !host.isNullOrBlank()
                ) {
                    append(
                        "\nFrom: "
                    )

                    append(host)
                }
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Download file?"
            )
            .setMessage(details)
            .setPositiveButton(
                "Download"
            ) { _, _ ->

                startTorDownload(
                    response,
                    fileName
                )
            }
            .setNegativeButton(
                "Cancel"
            ) { _, _ ->

                TorDownloadHandler
                    .discard(response)
            }
            .setOnCancelListener {
                TorDownloadHandler
                    .discard(response)
            }
            .show()
    }

    private fun startTorDownload(
        response: WebResponse,
        fileName: String
    ) {
        Toast.makeText(
            this,
            "Downloading $fileName",
            Toast.LENGTH_SHORT
        ).show()

        TorDownloadHandler.save(
            context = applicationContext,
            response = response,
            fileName = fileName,

            onSuccess = { result ->

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Downloaded: ${result.fileName}\n${result.location}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },

            onFailure = { reason ->

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Download failed: $reason",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun formatDownloadSize(
        bytes: Long
    ): String {
        if (bytes < 1024L) {
            return "$bytes B"
        }

        val kib =
            bytes / 1024.0

        if (kib < 1024.0) {
            return String.format(
                "%.1f KiB",
                kib
            )
        }

        val mib =
            kib / 1024.0

        if (mib < 1024.0) {
            return String.format(
                "%.1f MiB",
                mib
            )
        }

        return String.format(
            "%.2f GiB",
            mib / 1024.0
        )
    }

    private fun updateSiteInfoButton() {
        val tab =
            currentTab()

        if (
            !browserReady ||
            tab == null
        ) {
            siteInfoButton.isEnabled =
                false
            return
        }

        val scheme =
            try {
                Uri.parse(tab.url)
                    .scheme
                    ?.lowercase()
            } catch (_: Exception) {
                null
            }

        siteInfoButton.isEnabled =
            scheme == "http" ||
            scheme == "https"
    }

    private fun showCurrentSiteInfo() {
        val tab =
            currentTab()
                ?: return

        val uri =
            try {
                Uri.parse(tab.url)
            } catch (_: Exception) {
                return
            }

        val scheme =
            uri.scheme
                ?.lowercase()
                ?: return

        if (
            scheme != "http" &&
            scheme != "https"
        ) {
            return
        }

        val host =
            uri.host
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return

        val isOnion =
            host.endsWith(
                ".onion",
                ignoreCase = true
            )

        val type =
            if (isOnion) {
                "Onion Service"
            } else {
                "Clearnet via Tor"
            }

        val schemeLabel =
            scheme.uppercase()

        val socks =
            if (
                runtimeSocksPort > 0 &&
                browserReady
            ) {
                "Tor / SOCKS 127.0.0.1:$runtimeSocksPort"
            } else {
                "Tor unavailable"
            }

        val title =
            tab.title
                .trim()
                .takeIf {
                    it.isNotEmpty()
                }

        val message =
            buildString {
                if (title != null) {
                    append("Title\n")
                    append(title)
                    append("\n\n")
                }

                append("Host\n")
                append(host)

                append("\n\nType\n")
                append(type)

                append("\n\nScheme\n")
                append(schemeLabel)

                append("\n\nRouting\n")
                append(socks)

                append("\n\nURL\n")
                append(tab.url)
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Site Information"
            )
            .setMessage(message)
            .setPositiveButton(
                "Close",
                null
            )
            .show()
    }

    private fun currentSiteHost():
        String? {

        val tab =
            currentTab()
                ?: return null

        return try {
            val uri =
                Uri.parse(tab.url)

            val scheme =
                uri.scheme
                    ?.lowercase()

            if (
                scheme != "http" &&
                scheme != "https"
            ) {
                return null
            }

            uri.host
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        } catch (_: Exception) {
            null
        }
    }

    private fun updateClearSiteDataButton() {
        clearSiteDataButton.isEnabled =
            browserReady &&
            currentSiteHost() != null
    }

    private fun confirmClearCurrentSiteData() {
        val host =
            currentSiteHost()
                ?: return

        AlertDialog.Builder(this)
            .setTitle(
                "Clear site data?"
            )
            .setMessage(
                "Clear cookies, storage, cache, and permissions for:\n\n$host"
            )
            .setPositiveButton(
                "Clear"
            ) { _, _ ->
                clearCurrentSiteData(
                    host
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun clearCurrentSiteData(
        host: String
    ) {
        val currentRuntime =
            runtime

        val tab =
            currentTab()

        if (
            currentRuntime == null ||
            tab == null
        ) {
            return
        }

        clearSiteDataButton.isEnabled =
            false

        Toast.makeText(
            this,
            "Clearing data for $host…",
            Toast.LENGTH_SHORT
        ).show()

        currentRuntime
            .getStorageController()
            .clearDataFromHost(
                host,
                StorageController
                    .ClearFlags
                    .SITE_DATA
            )
            .accept(
                {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Site data cleared",
                            Toast.LENGTH_SHORT
                        ).show()

                        /*
                         * Reload so the visible page starts
                         * again after its stored data was
                         * cleared.
                         */
                        if (
                            browserReady &&
                            currentTab() === tab
                        ) {
                            tab.session.reload()
                        }

                        updateClearSiteDataButton()
                    }
                },
                { error ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Could not clear site data: " +
                                (
                                    error?.message
                                        ?: "unknown error"
                                ),
                            Toast.LENGTH_LONG
                        ).show()

                        updateClearSiteDataButton()
                    }
                }
            )
    }

    private fun isBookmarkable(
        url: String
    ): Boolean {
        return try {
            val scheme =
                Uri.parse(url)
                    .scheme
                    ?.lowercase()

            scheme == "http" ||
                scheme == "https"

        } catch (_: Exception) {
            false
        }
    }

    private fun readBookmarks():
        MutableList<Bookmark> {

        val raw =
            getSharedPreferences(
                BOOKMARK_PREFS,
                MODE_PRIVATE
            )
                .getString(
                    BOOKMARK_ITEMS,
                    null
                )
                ?: return mutableListOf()

        return try {
            val array =
                JSONArray(raw)

            val bookmarks =
                mutableListOf<Bookmark>()

            val seen =
                mutableSetOf<String>()

            val count =
                min(
                    array.length(),
                    MAX_BOOKMARKS
                )

            for (i in 0 until count) {
                val item =
                    array.optJSONObject(i)
                        ?: continue

                val url =
                    item.optString(
                        "url",
                        ""
                    )
                        .trim()

                if (
                    !isBookmarkable(url) ||
                    !seen.add(url)
                ) {
                    continue
                }

                val title =
                    item.optString(
                        "title",
                        ""
                    )
                        .trim()

                bookmarks.add(
                    Bookmark(
                        url = url,
                        title = title
                    )
                )
            }

            bookmarks

        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeBookmarks(
        bookmarks: List<Bookmark>
    ) {
        val array =
            JSONArray()

        bookmarks
            .take(MAX_BOOKMARKS)
            .forEach { bookmark ->

                val item =
                    JSONObject()

                item.put(
                    "url",
                    bookmark.url
                )

                item.put(
                    "title",
                    bookmark.title
                )

                array.put(item)
            }

        getSharedPreferences(
            BOOKMARK_PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putString(
                BOOKMARK_ITEMS,
                array.toString()
            )
            .apply()
    }

    private fun bookmarkTitle(
        tab: BrowserTab
    ): String {
        val title =
            tab.title.trim()

        if (title.isNotEmpty()) {
            return title
        }

        val host =
            try {
                Uri.parse(tab.url).host
            } catch (_: Exception) {
                null
            }

        return host
            ?.takeIf {
                it.isNotBlank()
            }
            ?: tab.url
    }

    private fun toggleCurrentBookmark() {
        val tab =
            currentTab()
                ?: return

        val url =
            tab.url.trim()

        if (!isBookmarkable(url)) {
            Toast.makeText(
                this,
                "This page cannot be bookmarked",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val bookmarks =
            readBookmarks()

        val existing =
            bookmarks.indexOfFirst {
                it.url == url
            }

        if (existing >= 0) {
            bookmarks.removeAt(existing)

            writeBookmarks(bookmarks)
            updateBookmarkButton()

            Toast.makeText(
                this,
                "Bookmark removed",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (
            bookmarks.size >=
            MAX_BOOKMARKS
        ) {
            Toast.makeText(
                this,
                "Maximum $MAX_BOOKMARKS bookmarks",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        bookmarks.add(
            Bookmark(
                url = url,
                title = bookmarkTitle(tab)
            )
        )

        writeBookmarks(bookmarks)
        updateBookmarkButton()

        Toast.makeText(
            this,
            "Bookmarked — long-press ★ to view",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateBookmarkButton() {
        val tab =
            currentTab()

        if (
            !browserReady ||
            tab == null ||
            !isBookmarkable(tab.url)
        ) {
            bookmarkButton.text = "☆"
            bookmarkButton.isEnabled = false
            return
        }

        val bookmarked =
            readBookmarks()
                .any {
                    it.url == tab.url
                }

        bookmarkButton.text =
            if (bookmarked) {
                "★"
            } else {
                "☆"
            }

        bookmarkButton.isEnabled = true
    }

    private fun bookmarkDisplayName(
        bookmark: Bookmark
    ): String {
        val title =
            bookmark.title
                .trim()
                .takeIf {
                    it.isNotEmpty()
                }

        val host =
            try {
                Uri.parse(
                    bookmark.url
                ).host
            } catch (_: Exception) {
                null
            }

        val primary =
            title
                ?: host
                ?: bookmark.url

        val shortTitle =
            if (primary.length > 40) {
                primary.take(37) + "…"
            } else {
                primary
            }

        return "$shortTitle\n${bookmark.url}"
    }

    private fun showBookmarksDialog() {
        val bookmarks =
            readBookmarks()

        if (bookmarks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Bookmarks")
                .setMessage(
                    "No bookmarks yet."
                )
                .setPositiveButton(
                    "Close",
                    null
                )
                .show()

            return
        }

        val labels =
            bookmarks
                .map {
                    bookmarkDisplayName(it)
                }
                .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(labels) {
                    _,
                    which ->

                val bookmark =
                    bookmarks.getOrNull(
                        which
                    )
                        ?: return@setItems

                openBookmark(
                    bookmark.url
                )
            }
            .setNeutralButton(
                "Delete…"
            ) { _, _ ->
                showDeleteBookmarksDialog(
                    bookmarks
                )
            }
            .setNegativeButton(
                "Close",
                null
            )
            .show()
    }

    private fun showDeleteBookmarksDialog(
        bookmarks: List<Bookmark>
    ) {
        if (bookmarks.isEmpty()) {
            return
        }

        val labels =
            bookmarks
                .map {
                    bookmarkDisplayName(it)
                }
                .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(
                "Delete bookmark"
            )
            .setItems(labels) {
                    _,
                    which ->

                val bookmark =
                    bookmarks.getOrNull(
                        which
                    )
                        ?: return@setItems

                confirmDeleteBookmark(
                    bookmark
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun confirmDeleteBookmark(
        bookmark: Bookmark
    ) {
        AlertDialog.Builder(this)
            .setTitle(
                "Delete bookmark?"
            )
            .setMessage(
                bookmarkDisplayName(
                    bookmark
                )
            )
            .setPositiveButton(
                "Delete"
            ) { _, _ ->

                val bookmarks =
                    readBookmarks()

                bookmarks.removeAll {
                    it.url ==
                        bookmark.url
                }

                writeBookmarks(
                    bookmarks
                )

                updateBookmarkButton()

                Toast.makeText(
                    this,
                    "Bookmark deleted",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun openBookmark(
        url: String
    ) {
        if (
            !browserReady ||
            !isBookmarkable(url)
        ) {
            return
        }

        val tab =
            currentTab()
                ?: return

        tab.url = url

        addressBar.setText(url)

        hideKeyboard()

        tab.session.loadUri(url)

        updateBookmarkButton()
        renderTabStrip()
    }

    private fun showCurrentAddress() {
        val tab =
            currentTab()
                ?: return

        addressBar.setText(
            if (
                tab.url ==
                "about:blank"
            ) {
                ""
            } else {
                tab.url
            }
        )

        updateBookmarkButton()
        updateClearSiteDataButton()
        updateSiteInfoButton()
    }

    private fun updateNavigationButtons() {
        val tab =
            currentTab()

        backButton.isEnabled =
            browserReady &&
            tab?.canGoBack == true

        forwardButton.isEnabled =
            browserReady &&
            tab?.canGoForward == true

        reloadButton.isEnabled =
            browserReady &&
            tab != null

        closeTabButton.isEnabled =
            browserReady &&
            tab != null
    }

    private fun ensureFaviconBridge() {
        if (faviconBridge != null) {
            return
        }

        val currentRuntime =
            runtime ?: return

        faviconBridge =
            FaviconBridge(
                currentRuntime,
                object : FaviconBridge.Listener {
                    override fun onFavicon(
                        session: GeckoSession,
                        pageUrl: String,
                        dataUrl: String
                    ) {
                        runOnUiThread {
                            val index =
                                findTabIndex(session)

                            if (index < 0) {
                                return@runOnUiThread
                            }

                            val tab =
                                tabs[index]

                            if (
                                normalizedFaviconUrl(tab.url) !=
                                normalizedFaviconUrl(pageUrl)
                            ) {
                                return@runOnUiThread
                            }

                            val bitmap =
                                decodeFavicon(dataUrl)
                                    ?: return@runOnUiThread

                            tab.favicon =
                                bitmap

                            renderTabStrip()
                        }
                    }
                }
            )
    }

    private fun normalizedFaviconUrl(
        value: String
    ): String {
        return value
            .substringBefore('#')
            .trimEnd('/')
    }

    private fun decodeFavicon(
        value: String
    ): Bitmap? {
        if (
            !value.startsWith(
                "data:image/",
                ignoreCase = true
            )
        ) {
            return null
        }

        val comma =
            value.indexOf(',')

        if (
            comma <= 0 ||
            comma >= value.lastIndex
        ) {
            return null
        }

        return try {
            val bytes =
                Base64.decode(
                    value.substring(comma + 1),
                    Base64.DEFAULT
                )

            if (
                bytes.isEmpty() ||
                bytes.size > 262144
            ) {
                null
            } else {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(
        value: Int
    ): Int {
        return (
            value *
            resources.displayMetrics.density
        ).toInt()
    }

    private fun renderTabStrip() {
        tabStrip.removeAllViews()

        tabs.forEachIndexed { index, tab ->
            val active =
                index == currentTabIndex

            val button =
                Button(this).apply {
                    text =
                        tabLabel(
                            tab,
                            index
                        )

                    isAllCaps =
                        false

                    maxLines =
                        1

                    ellipsize =
                        android.text.TextUtils
                            .TruncateAt.END

                    maxWidth =
                        dp(180)

                    minimumWidth =
                        0

                    minWidth =
                        0

                    setPadding(
                        dp(8),
                        0,
                        dp(8),
                        0
                    )

                    alpha =
                        if (active) {
                            1.0f
                        } else {
                            0.72f
                        }

                    setTypeface(
                        null,
                        if (active) {
                            Typeface.BOLD
                        } else {
                            Typeface.NORMAL
                        }
                    )

                    val icon =
                        tab.favicon

                    if (icon != null) {
                        val drawable =
                            BitmapDrawable(
                                resources,
                                icon
                            )

                        drawable.setBounds(
                            0,
                            0,
                            dp(18),
                            dp(18)
                        )

                        setCompoundDrawables(
                            drawable,
                            null,
                            null,
                            null
                        )

                        compoundDrawablePadding =
                            dp(6)
                    }

                    setOnClickListener {
                        if (browserReady) {
                            switchToTab(index)
                        }
                    }
                }

            button.layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    marginStart =
                        dp(2)

                    marginEnd =
                        dp(2)
                }

            tabStrip.addView(
                button
            )
        }
    }

    private fun tabLabel(
        tab: BrowserTab,
        index: Int
    ): String {
        val title =
            tab.title
                .trim()
                .takeIf {
                    it.isNotEmpty()
                }

        val host =
            if (
                tab.url.isBlank() ||
                tab.url == "about:blank"
            ) {
                null
            } else {
                try {
                    Uri.parse(tab.url).host
                } catch (
                    _: Exception
                ) {
                    null
                }
            }

        val raw =
            title
                ?: host
                ?: "Tab ${index + 1}"

        return if (
            raw.length > 22
        ) {
            raw.take(19) + "…"
        } else {
            raw
        }
    }


    private fun updateLoadingUi() {
        val tab = currentTab()

        if (
            !browserReady ||
            tab == null ||
            tab.loadingProgress <= 0 ||
            tab.loadingProgress >= 100
        ) {
            pageProgress.visibility =
                View.GONE
            pageProgress.progress = 0
            return
        }

        pageProgress.visibility =
            View.VISIBLE

        pageProgress.progress =
            tab.loadingProgress
    }

    private fun showHttpsFallbackDialog(
        session: GeckoSession,
        httpUrl: String
    ) {
        val host =
            try {
                Uri.parse(httpUrl)
                    .host
            } catch (_: Exception) {
                null
            }
                ?: httpUrl

        AlertDialog.Builder(this)
            .setTitle(
                "HTTPS unavailable"
            )
            .setMessage(
                "A secure HTTPS connection to:\n\n" +
                    host +
                    "\n\ncould not be established.\n\n" +
                    "Open this site using insecure HTTP?"
            )
            .setPositiveButton(
                "Open HTTP"
            ) { _, _ ->

                if (!browserReady) {
                    return@setPositiveButton
                }

                insecureHttpOnce[
                    session
                ] = httpUrl

                session.loadUri(
                    httpUrl
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun resolveInput(
        input: String
    ): String {
        val value = input.trim()

        if (
            value.startsWith(
                "http://",
                ignoreCase = true
            ) ||
            value.startsWith(
                "https://",
                ignoreCase = true
            ) ||
            value.startsWith(
                "about:",
                ignoreCase = true
            )
        ) {
            return value
        }

        /*
         * Bare onion addresses use http://.
         * The Onion Service connection itself remains
         * inside Tor.
         */
        if (
            value.endsWith(
                ".onion",
                ignoreCase = true
            )
        ) {
            return "http://$value"
        }

        /*
         * Something that looks like a hostname is opened
         * HTTPS-first.
         */
        if (
            !value.contains(" ") &&
            value.contains(".")
        ) {
            return "https://$value"
        }

        /*
         * Otherwise treat input as a search query.
         */
        return "https://duckduckgo.com/?q=" +
            Uri.encode(value)
    }

    private fun hideKeyboard() {
        val manager =
            getSystemService(
                INPUT_METHOD_SERVICE
            ) as InputMethodManager

        manager.hideSoftInputFromWindow(
            addressBar.windowToken,
            0
        )

        addressBar.clearFocus()
    }

    private fun loadAddress() {
        if (!browserReady) {
            return
        }

        val tab =
            currentTab()
                ?: return

        val input =
            addressBar
                .text
                .toString()
                .trim()

        if (input.isEmpty()) {
            return
        }

        val uri =
            resolveInput(input)

        hideKeyboard()

        tab.url = uri

        addressBar.setText(uri)

        tab.session.loadUri(uri)

        renderTabStrip()
    }

    private fun failClosed(
        reason: String
    ) {
        geckoView
            .releaseSession()
            ?.apply {
                setFocused(false)
                setActive(false)
            }

        tabs.forEach {
            try {
                it.session.close()
            } catch (
                _: Exception
            ) {
            }
        }

        tabs.clear()
        insecureHttpOnce.clear()

        currentTabIndex = -1
        pendingPopupSession = null

        pageProgress.visibility =
            View.GONE

        browserReady = false

        renderTabStrip()
        setBrowserControlsEnabled(false)

        addressBar.setText("")
        addressBar.hint =
            "Tor unavailable"

        torStatus.text =
            "BLOCKED — $reason"
    }

    private fun setBrowserControlsEnabled(
        enabled: Boolean
    ) {
        addressBar.isEnabled =
            enabled

        goButton.isEnabled =
            enabled

        newTabButton.isEnabled =
            enabled

        if (!enabled) {
            bookmarkButton.isEnabled =
                false

            bookmarkButton.text =
                "☆"

            clearSiteDataButton.isEnabled =
                false

            siteInfoButton.isEnabled =
                false
            backButton.isEnabled =
                false

            forwardButton.isEnabled =
                false

            reloadButton.isEnabled =
                false

            closeTabButton.isEnabled =
                false
        } else {
            updateNavigationButtons()
            updateBookmarkButton()
            updateClearSiteDataButton()
            updateSiteInfoButton()
        }
    }

    private fun createGeckoTorConfig(
        socksPort: Int
    ): File {
        val configFile =
            File(
                filesDir,
                "geckoview-tor.yaml"
            )

        configFile.writeText(
            """
            prefs:
              network.proxy.type: 1
              network.proxy.socks: "127.0.0.1"
              network.proxy.socks_port: $socksPort
              network.proxy.socks_version: 5
              network.proxy.socks_remote_dns: true
              network.proxy.socks5_remote_dns: true
              network.proxy.no_proxies_on: ""
              network.trr.mode: 5

              media.peerconnection.enabled: false
              media.navigator.enabled: false
              geo.enabled: false
              dom.webnotifications.enabled: false
              dom.push.enabled: false
              webgl.disabled: true

              network.dns.disablePrefetch: true
              network.prefetch-next: false
              network.predictor.enabled: false
              network.http.speculative-parallel-limit: 0
              browser.send_pings: false
            """.trimIndent()
        )

        return configFile
    }

    override fun onPause() {
        currentTab()
            ?.session
            ?.setFocused(false)

        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        if (browserReady) {
            currentTab()
                ?.session
                ?.apply {
                    setActive(true)
                    setFocused(true)
                    setPriorityHint(
                        GeckoSession.PRIORITY_HIGH
                    )
                }
        }
    }

    override fun onStop() {
        if (browserReady) {
            saveSessionSnapshot()

            currentTab()
                ?.session
                ?.apply {
                    setFocused(false)
                    setActive(false)
                    setPriorityHint(
                        GeckoSession.PRIORITY_DEFAULT
                    )
                }
        }

        super.onStop()
    }

    override fun onDestroy() {
        if (browserReady) {
            saveSessionSnapshot()
        }

        shuttingDown = true

        torWatchThread?.interrupt()
        torWatchThread = null

        geckoView
            .releaseSession()
            ?.apply {
                setFocused(false)
                setActive(false)
            }

        tabs.forEach {
            try {
                it.session.close()
            } catch (
                _: Exception
            ) {
            }
        }

        tabs.clear()

        if (serviceBound) {
            unbindService(
                serviceConnection
            )

            serviceBound = false
        }

        super.onDestroy()
    }
}
