package com.vayunmathur.communicate.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** A physical SIM subscription available for texting/calling. */
data class CommunicateSim(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
)

/**
 * Enumerates the device's active SIM subscriptions so the app can offer per-SIM texting/calling
 * (like the system dialer/messages) and label call/message history by SIM.
 *
 * Reads require READ_PHONE_STATE; when it isn't granted we fall back to a single default SIM
 * choice so the app still works on the primary line.
 */
object SimManager {

    fun activeSims(context: Context): List<CommunicateSim> {
        if (!context.hasPhoneStatePermission()) return emptyList()
        // The service lookup itself can throw where no telephony stack exists
        // (Layoutlib screenshot renders have no subscription service) — treat
        // that like zero SIMs rather than crashing composition.
        val sm = runCatching { context.getSystemService(SubscriptionManager::class.java) }
            .getOrNull() ?: return emptyList()
        return try {
            sm.activeSubscriptionInfoList?.map { info ->
                CommunicateSim(
                    subscriptionId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    displayName = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: "SIM ${info.simSlotIndex + 1}",
                )
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Selectable SIM lines. Falls back to a single default SIM when none can be enumerated. */
    fun simLineChoices(context: Context): List<LineChoice.Sim> {
        val sims = activeSims(context)
        if (sims.isNotEmpty()) {
            val multi = sims.size > 1
            return sims.map { sim ->
                // With multiple SIMs, disambiguate by slot; with one, just show its name.
                val label = if (multi) "${sim.displayName}" else sim.displayName
                LineChoice.Sim(sim.subscriptionId, label)
            }
        }
        val defaultSub = defaultSmsSubscriptionId()
        return listOf(LineChoice.Sim(defaultSub, "SIM"))
    }

    fun labelForSubscription(context: Context, subscriptionId: Int?): String? {
        if (subscriptionId == null) return null
        return activeSims(context).firstOrNull { it.subscriptionId == subscriptionId }?.displayName
    }

    fun defaultSmsSubscriptionId(): Int = runCatching {
        SubscriptionManager.getDefaultSmsSubscriptionId()
    }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private fun Context.hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}
