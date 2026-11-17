package com.vayunmathur.communicate.service.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateCallLogEntry
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.call.isLiveCall
import com.vayunmathur.communicate.data.loadCallLogsMerged
import com.vayunmathur.communicate.data.placeCallForLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Recent calls plus the live call card.
 *
 * Rows redial through `placeCallForLine` (same path as the phone dialer);
 * the live card mirrors `InAppCallRegistry` phases with answer / reject /
 * hangup / mute / speaker actions. Carrier (SIM) calls surface through
 * Telecom like every other line — no new registration here.
 */
class CommunicateCarCallScreen(
    carContext: CarContext,
    private val session: CommunicateCarSession,
) : Screen(carContext) {

    private var entries: List<CommunicateCallLogEntry> = emptyList()
    private var loading = true
    private var dialNumber: String = ""

    init {
        session.scope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                session.repository.loadCallLogsMerged(session.carContext)
            }.getOrDefault(emptyList())
            session.scope.launch(Dispatchers.Main) {
                entries = loaded.take(MAX_SHOWN)
                loading = false
                invalidate()
            }
        }
    }

    @ExperimentalCarApi
    @OptIn(ExperimentalCarApi::class)
    override fun onGetTemplate(): Template {
        val live = InAppCallRegistry.state.value
        if (live.phase.isLiveCall()) {
            // Experimental dialer templates need an icon-only action and a
            // resolvable drawable; fall back to the Pane card when unavailable.
            runCatching { return inCallTemplate(live.peerName.ifBlank { live.peerId }, live) }
            return callCard(live.peerName.ifBlank { live.peerId }, live)
        }
        return recentCallsTemplate()
    }

    /** Car-native in-call view (experimental). Falls back on any failure. */
    @ExperimentalCarApi
    @OptIn(ExperimentalCarApi::class)
    private fun inCallTemplate(
        title: String,
        live: com.vayunmathur.communicate.data.call.InAppCallState,
    ): Template {
        val builder = androidx.car.app.dialer.InCallTemplate.Builder()
            .setTitle(title.ifBlank { "Call" })
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(title.ifBlank { "Call" })
                    .build(),
            )
        builder.addText(live.phase.name)
        if (live.phase == InAppCallPhase.Incoming) {
            builder.addAction(callAction { InAppCallRegistry.answer("car") })
            builder.addAction(callAction { InAppCallRegistry.reject("car") })
        } else {
            builder.addAction(
                callAction { InAppCallRegistry.toggleMuted() },
            )
            builder.addAction(callAction { InAppCallRegistry.toggleSpeaker() })
            builder.addAction(callAction { InAppCallRegistry.hangup("car") })
        }
        return builder.build()
    }

    /** Dialpad for placing a new call (experimental). */
    @ExperimentalCarApi
    @OptIn(ExperimentalCarApi::class)
    fun keypadTemplate(): Template {
        val callIcon = runCatching {
            CarIcon.Builder(
                IconCompat.createWithResource(carContext, R.mipmap.ic_launcher),
            ).build()
        }.getOrNull() ?: return recentCallsTemplate()
        return androidx.car.app.dialer.TelephoneKeypadTemplate.Builder(
            Action.Builder()
                .setIcon(callIcon)
                .setOnClickListener { placeCall(dialNumber) }
                .build(),
            object : androidx.car.app.dialer.TelephoneKeypadTemplate.PhoneNumberChangeListener {
                override fun onPhoneNumberChanged(number: String) {
                    dialNumber = number
                }
            },
        )
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle("Dial")
                    .build(),
            )
            .setPhoneNumber(dialNumber)
            .build()
    }

    private fun callAction(onClick: () -> Unit): Action {
        // IN_CALL_CONTENT requires icons and allows zero custom titles, so
        // actions are icon-only. The launcher icon stands in: music ships no
        // playback drawables and titles are rejected here.
        val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)).build()
        return Action.Builder()
            .setIcon(icon)
            .setOnClickListener(onClick)
            .build()
    }

    private fun placeCall(number: String) {
        if (number.isBlank()) return
        session.scope.launch(Dispatchers.IO) {
            runCatching {
                session.repository.placeCallForLine(
                    session.carContext,
                    com.vayunmathur.communicate.data.CommunicateLine.Sim,
                    number,
                    number,
                    video = false,
                )
            }
        }
    }

    @ExperimentalCarApi
    @OptIn(ExperimentalCarApi::class)
    private fun recentCallsTemplate(): Template {
        val list = ItemList.Builder()
        list.addItem(
            Row.Builder()
                .setTitle("Dialpad")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(CommunicateCarKeypadScreen(carContext, this)) }
                .build(),
        )
        for (entry in entries) {
            val title = entry.displayName?.takeIf { it.isNotBlank() } ?: entry.phoneNumber
            val row = Row.Builder()
                .setTitle(title)
            row.addText(entry.type.name)
            row.setOnClickListener { redial(entry) }
            list.addItem(row.build())
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(if (loading) "Loading…" else "Recent calls")
            .setHeaderAction(Action.BACK)
            .build()
    }

    /** Wrapper so the dialpad renders the keypad template directly. */
    @ExperimentalCarApi
    @OptIn(ExperimentalCarApi::class)
    private class CommunicateCarKeypadScreen(
        carContext: CarContext,
        private val parent: CommunicateCarCallScreen,
    ) : Screen(carContext) {
        override fun onGetTemplate(): Template = parent.keypadTemplate()
    }

    private fun callCard(
        title: String,
        live: com.vayunmathur.communicate.data.call.InAppCallState,
    ): Template {
        val pane = Pane.Builder()
        pane.addRow(Row.Builder().setTitle(title.ifBlank { "Call" }).build())
        if (live.phase == InAppCallPhase.Incoming) {
            pane.addAction(
                Action.Builder().setTitle("Answer")
                    .setOnClickListener { InAppCallRegistry.answer("car") }
                    .build(),
            )
            pane.addAction(
                Action.Builder().setTitle("Decline")
                    .setOnClickListener { InAppCallRegistry.reject("car") }
                    .build(),
            )
        } else {
            pane.addAction(
                Action.Builder().setTitle(if (live.muted) "Unmute" else "Mute")
                    .setOnClickListener { InAppCallRegistry.toggleMuted() }
                    .build(),
            )
            pane.addAction(
                Action.Builder().setTitle("Speaker")
                    .setOnClickListener { InAppCallRegistry.toggleSpeaker() }
                    .build(),
            )
            pane.addAction(
                Action.Builder().setTitle("End")
                    .setOnClickListener { InAppCallRegistry.hangup("car") }
                    .build(),
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Call")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun redial(entry: CommunicateCallLogEntry) {
        session.scope.launch(Dispatchers.IO) {
            runCatching {
                session.repository.placeCallForLine(
                    session.carContext,
                    entry.line,
                    entry.phoneNumber,
                    entry.phoneNumber,
                    video = false,
                )
            }
        }
    }

    private companion object {
        const val MAX_SHOWN = 25
    }
}
