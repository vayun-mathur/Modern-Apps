package com.vayunmathur.communicate.service.car

import androidx.core.graphics.createBitmap
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.SmsThread

/**
 * Merged conversation list: SIM + GoogleVoice + WhatsApp + Signal threads,
 * newest first.
 *
 * Component choice (car design guide):
 * - API 8+: [SectionedItemTemplate] with one [RowSection] per line (SIM,
 *   Google Voice, WhatsApp, Signal) — section headers label each group, so
 *   the driver sees where each thread lives without opening it.
 * - Older hosts: the flat [ListTemplate] fallback.
 *
 * Rows show the display name, snippet, and unread state; tapping opens
 * [CommunicateCarConversationScreen]. GoogleVoice rows carry no reply
 * affordance (read-only until the token follow-up) but stay readable.
 */
class CommunicateCarThreadListScreen(
    carContext: CarContext,
    private val session: CommunicateCarSession,
) : Screen(carContext) {

    private var threads: List<SmsThread> = emptyList()
    private var loading = true

    init {
        session.refreshThreads {
            threads = it
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        // ListTemplate.build() throws when loading==hasList, and an empty
        // section set is legal — but while loading there is nothing to show,
        // so both paths branch on it.
        if (carContext.getCarAppApiLevel() >= CAR_API_SECTIONED_LIST) {
            runCatching { return sectionedTemplate() }
        }
        return legacyListTemplate()
    }

    private fun sectionedTemplate(): Template {
        val builder = SectionedItemTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.APP_ICON)
                    .setTitle(if (loading) "Loading…" else "Messages")
                    .build(),
            )
            .setLoading(loading)
        if (!loading) {
            for ((line, label) in LINE_ORDER) {
                val rows = threads.filter { it.line == line }.map { threadRow(it) }
                if (rows.isNotEmpty()) {
                    builder.addSection(
                        RowSection.Builder()
                            .setTitle(label)
                            .setItems(rows)
                            .build(),
                    )
                }
            }
        }
        return builder.build()
    }

    private fun legacyListTemplate(): Template {
        val listBuilder = ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.APP_ICON)
                    .setTitle(if (loading) "Loading…" else "Messages")
                    .build(),
            )
            .setLoading(loading)
        if (!loading) {
            val list = ItemList.Builder()
            for (thread in threads) {
                list.addItem(threadRow(thread))
            }
            listBuilder.setSingleList(list.build())
        }
        return listBuilder.build()
    }

    private fun threadRow(thread: SmsThread): Row {
        val title = thread.displayName?.takeIf { it.isNotBlank() } ?: thread.address
        val row = Row.Builder()
            .setTitle(title)
        thread.snippet.takeIf { it.isNotBlank() }?.let { row.addText(it) }
        if (thread.unreadCount > 0) {
            row.addText("${thread.unreadCount} unread")
        }
        runCatching { row.setImage(avatarIcon(thread, title), Row.IMAGE_TYPE_LARGE) }
        row.setBrowsable(true)
        row.setOnClickListener {
            screenManager.push(CommunicateCarConversationScreen(carContext, session, thread))
        }
        return row.build()
    }

    /** Contact photo when the thread has one, else a generated initials monogram. */
    private fun avatarIcon(thread: SmsThread, name: String): CarIcon {
        val photo = thread.avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching { IconCompat.createWithContentUri(url.toUri()) }.getOrNull()
        }
        return CarIcon.Builder(photo ?: IconCompat.createWithBitmap(monogram(name))).build()
    }

    private fun monogram(name: String): android.graphics.Bitmap {
        val initials = name.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifBlank { "#" }
        val size = AVATAR_SIZE_PX
        val bitmap = createBitmap(size, size)
        val canvas = android.graphics.Canvas(bitmap)
        val palette = AVATAR_PALETTE
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = palette[(name.hashCode() and INT_POSITIVE_MASK) % palette.size]
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size * AVATAR_TEXT_FRACTION
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val baseline = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, baseline, textPaint)
        return bitmap
    }

    private companion object {
        const val CAR_API_SECTIONED_LIST = 8
        const val AVATAR_SIZE_PX = 128
        const val INT_POSITIVE_MASK = 0x7fffffff
        const val AVATAR_TEXT_FRACTION = 0.42f
        val AVATAR_PALETTE = intArrayOf(
            0xFF5C6BC0.toInt(),
            0xFF26A69A.toInt(),
            0xFFEF5350.toInt(),
            0xFFAB47BC.toInt(),
            0xFF66BB6A.toInt(),
            0xFFFFA726.toInt())
        val LINE_ORDER: List<Pair<CommunicateLine, String>> = listOf(
            CommunicateLine.Sim to "SIM",
            CommunicateLine.GoogleVoice to "Google Voice",
            CommunicateLine.WhatsApp to "WhatsApp",
            CommunicateLine.Signal to "Signal",
        )
    }
}
