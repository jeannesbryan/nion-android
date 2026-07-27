package io.github.jeannesbryan.nion

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoSession

object BrowserEssentials {

    fun clipboardText(
        context: Context
    ): String? {
        val clipboard =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        val clip =
            clipboard.primaryClip
                ?: return null

        if (clip.itemCount <= 0) {
            return null
        }

        return clip
            .getItemAt(0)
            .text
            ?.toString()
            ?.trim()
            ?.take(4096)
            ?.takeIf { it.isNotEmpty() }
    }

    fun copyText(
        context: Context,
        label: String,
        text: String
    ) {
        val clipboard =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                label,
                text
            )
        )

        Toast.makeText(
            context,
            "Copied",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun shareUrl(
        activity: Activity,
        url: String
    ) {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    url
                )
            }

        try {
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    "Share URL"
                )
            )
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "No app available to share URL",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showLinkMenu(
        activity: Activity,
        url: String,
        label: String?,
        onOpenNewTab: () -> Unit
    ) {
        val scheme =
            try {
                android.net.Uri.parse(url)
                    .scheme
                    ?.lowercase()
            } catch (_: Exception) {
                null
            }

        val canOpen =
            scheme == "http" ||
                scheme == "https"

        val actions =
            if (canOpen) {
                arrayOf(
                    "Open in New Tab",
                    "Copy Link"
                )
            } else {
                arrayOf(
                    "Copy Link"
                )
            }

        val title =
            label
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    if (it.length > 80) {
                        it.take(77) + "…"
                    } else {
                        it
                    }
                }
                ?: "Link"

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(actions) {
                    _,
                    which ->

                if (
                    canOpen &&
                    which == 0
                ) {
                    onOpenNewTab()
                } else {
                    copyText(
                        activity,
                        "Link",
                        url
                    )
                }
            }
            .show()
    }

    fun showFindInPage(
        activity: Activity,
        session: GeckoSession
    ) {
        if (!session.isOpen) {
            return
        }

        val density =
            activity.resources
                .displayMetrics
                .density

        fun dp(value: Int): Int {
            return (value * density)
                .toInt()
        }

        val finder =
            session.finder

        finder.setDisplayFlags(
            GeckoSession
                .FINDER_DISPLAY_HIGHLIGHT_ALL
        )

        val input =
            EditText(activity).apply {
                hint = "Text to find"
                isSingleLine = true
                setSelectAllOnFocus(true)
            }

        val status =
            TextView(activity).apply {
                text = "Type to search"
                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

        val container =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(24),
                    dp(8),
                    dp(24),
                    0
                )

                addView(input)
                addView(status)
            }

        fun showResult(
            result: GeckoSession.FinderResult
        ) {
            activity.runOnUiThread {
                val activeQuery =
                    input.text
                        .toString()

                if (
                    result.searchString !=
                    activeQuery
                ) {
                    return@runOnUiThread
                }

                status.text =
                    when {
                        !result.found ->
                            "No matches"

                        result.total >= 0 ->
                            "${result.current} / ${result.total}" +
                                if (result.wrapped) {
                                    " — wrapped"
                                } else {
                                    ""
                                }

                        else ->
                            "Match ${result.current}" +
                                if (result.wrapped) {
                                    " — wrapped"
                                } else {
                                    ""
                                }
                    }
            }
        }

        fun find(
            query: String?,
            flags: Int
        ) {
            finder.find(
                query,
                flags
            ).accept(
                { result ->
                    if (result != null) {
                        showResult(result)
                    }
                },
                {
                    activity.runOnUiThread {
                        status.text =
                            "Search unavailable"
                    }
                }
            )
        }

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(
                    "Find in Page"
                )
                .setView(container)
                .setPositiveButton(
                    "Next",
                    null
                )
                .setNeutralButton(
                    "Previous",
                    null
                )
                .setNegativeButton(
                    "Close",
                    null
                )
                .create()

        dialog.setOnDismissListener {
            finder.clear()
        }

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {
                if (
                    input.text
                        .toString()
                        .isNotEmpty()
                ) {
                    find(
                        null,
                        GeckoSession
                            .FINDER_FIND_FORWARD
                    )
                }
            }

            dialog.getButton(
                AlertDialog.BUTTON_NEUTRAL
            ).setOnClickListener {
                if (
                    input.text
                        .toString()
                        .isNotEmpty()
                ) {
                    find(
                        null,
                        GeckoSession
                            .FINDER_FIND_BACKWARDS
                    )
                }
            }

            input.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        text: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {
                    }

                    override fun onTextChanged(
                        text: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        val query =
                            text
                                ?.toString()
                                .orEmpty()

                        if (query.isEmpty()) {
                            finder.clear()
                            status.text =
                                "Type to search"
                            return
                        }

                        find(
                            query,
                            GeckoSession
                                .FINDER_FIND_FORWARD
                        )
                    }

                    override fun afterTextChanged(
                        text: Editable?
                    ) {
                    }
                }
            )

            input.requestFocus()
            input.post {
                val manager =
                    activity.getSystemService(
                        Context.INPUT_METHOD_SERVICE
                    ) as InputMethodManager

                manager.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT
                )
            }
        }

        dialog.show()
    }
}
