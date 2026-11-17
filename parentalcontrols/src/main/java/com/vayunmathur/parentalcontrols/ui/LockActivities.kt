package com.vayunmathur.parentalcontrols.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.data.BonusGrant
import com.vayunmathur.parentalcontrols.data.SupervisionRules
import com.vayunmathur.parentalcontrols.platform.Enforcer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen restriction notices, one per block reason.
 *
 * Reached from the "why is this blocked" entry point and from limit-spent notifications - not
 * auto-shown on window entry, which would interrupt whatever allowed app the child is using.
 * Bedtime, downtime and school-time locks explain and wait out the window; daily and per-app
 * limit locks additionally offer bonus time, granted on-device by the parent's PIN.
 *
 * None of these is exported: they are started explicitly by our own receivers and
 * notifications. The bonus path reuses [PinGateActivity] rather than taking a PIN here, so
 * the secret is entered in exactly one place.
 */
abstract class BaseLockActivity : ComponentActivity() {

    protected abstract val reason: Enforcer.BlockReason

    /** Null for device-wide (daily-limit) locks. */
    protected open val blockedPackage: String? = null

    private val bonusGate =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == PinGateActivity.RESULT_VERIFIED) grantBonus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = blockedPackage?.let { pkg -> packageLabel(pkg) }
        enableEdgeToEdge()
        // Lock screens show over the keyguard: the child meets the reason, not a home screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            DynamicTheme {
                LockScreen(
                    reason = reason,
                    appLabel = label,
                    actions = LockActions(
                        onAskParent = {
                            bonusGate.launch(Intent(this, PinGateActivity::class.java))
                        },
                        onClose = { finish() },
                    ),
                )
            }
        }
    }

    private fun packageLabel(blockedPackage: String): String? =
        runCatching {
            val info = packageManager.getApplicationInfo(blockedPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()

    private fun grantBonus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rules = SupervisionRules.get(this@BaseLockActivity)
            rules.grantBonus(
                BonusGrant(
                    packageName = blockedPackage,
                    bonusMinutes = BONUS_MINUTES,
                    day = LocalDate.now().toString(),
                ),
            )
            Enforcer(this@BaseLockActivity).reconcile()
            finish()
        }
    }

    /** Bonus amount, intent extras and factory for lock-screen activities. */
    companion object {
        /** Minutes each on-device bonus grant adds. Matches the smallest fixed limit choice. */
        const val BONUS_MINUTES = 15

        const val EXTRA_PACKAGE = "com.vayunmathur.parentalcontrols.extra.BLOCKED_PACKAGE"

        fun intent(context: Context, activity: Class<out BaseLockActivity>, blockedPackage: String?): Intent =
            Intent(context, activity).putExtra(EXTRA_PACKAGE, blockedPackage)
    }
}

/** Shown for bedtime-blocked apps. The window ends on its own schedule; no bonus offered. */
class BedtimeLockActivity : BaseLockActivity() {
    override val reason = Enforcer.BlockReason.Bedtime
    override val blockedPackage: String?
        get() = intent.getStringExtra(EXTRA_PACKAGE)
}

/** Shown for downtime-blocked apps. Allowed-list apps never land here. */
class DowntimeLockActivity : BaseLockActivity() {
    override val reason = Enforcer.BlockReason.Downtime
    override val blockedPackage: String?
        get() = intent.getStringExtra(EXTRA_PACKAGE)
}

/** Shown for school-time-blocked apps. Allowed learning apps never land here. */
class SchoolTimeLockActivity : BaseLockActivity() {
    override val reason = Enforcer.BlockReason.SchoolTime
    override val blockedPackage: String?
        get() = intent.getStringExtra(EXTRA_PACKAGE)
}

/** Shown when the device-wide daily budget is spent. Bonus extends today for everything. */
class DailyLimitLockActivity : BaseLockActivity() {
    override val reason = Enforcer.BlockReason.DailyLimit
    override val blockedPackage: String? = null
}

/** Shown when a single app's daily cap is spent. Bonus extends today for that app. */
class AppLimitLockActivity : BaseLockActivity() {
    override val reason = Enforcer.BlockReason.AppLimit
    override val blockedPackage: String?
        get() = intent.getStringExtra(EXTRA_PACKAGE)
}
