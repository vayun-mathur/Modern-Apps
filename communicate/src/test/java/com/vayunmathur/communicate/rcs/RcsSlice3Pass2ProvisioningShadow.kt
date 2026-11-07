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

    @Implementation
    fun isRcsProvisioningRequiredForCapability(capability: Int, tech: Int): Boolean = provisioningRequired

    @Implementation
    fun getRcsProvisioningStatusForCapability(capability: Int, tech: Int): Boolean = !provisioningRequired

    companion object {
        @Volatile var capable = true
        @Volatile var provisioningRequired = false
        fun reset() { capable = true; provisioningRequired = false }
    }
}
