package com.vayunmathur.communicate.rcs

import android.telephony.ims.ProvisioningManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Framework ProvisioningManager stand-in reporting single-registration
 * capable and no provisioning required, so the provisioning probe's
 * "available" path can run on the JVM.
 */
@Implements(ProvisioningManager::class)
class RcsSlice3Pass2ProvisioningShadow {
    @Implementation
    fun isRcsVolteSingleRegistrationCapable(): Boolean = capable

    // The capability/tech arguments mirror the framework signature; the shadow ignores them.
    @Implementation
    fun isRcsProvisioningRequiredForCapability(
        @Suppress("UnusedParameter") capability: Int,
        @Suppress("UnusedParameter") tech: Int,
    ): Boolean = provisioningRequired

    // The app queries call-composer (an MmTel capability) through the
    // non-RCS provisioning pair, so the shadow reports the flag there too.
    @Implementation
    fun isProvisioningRequiredForCapability(capability: Int, tech: Int): Boolean = provisioningRequired

    @Implementation
    fun getProvisioningStatusForCapability(capability: Int, tech: Int): Boolean = !provisioningRequired

    @Implementation
    fun getRcsProvisioningStatusForCapability(
        @Suppress("UnusedParameter") capability: Int,
        @Suppress("UnusedParameter") tech: Int,
    ): Boolean = !provisioningRequired

    companion object {
        @Volatile var capable = true
        @Volatile var provisioningRequired = false
        fun reset() { capable = true; provisioningRequired = false }
    }
}
