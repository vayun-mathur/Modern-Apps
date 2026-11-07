package com.vayunmathur.speech.testing

import android.os.ParcelFileDescriptor
import java.io.IOException
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric's host JDK lacks the libcore `FileDescriptor.getOwnerId$` that
 * the real `ParcelFileDescriptor.detachFd` calls, so detaching throws an
 * Error the production catch-of-Exception cannot see. Throwing an IOException
 * here reproduces the on-device "asset cannot be detached" failure instead.
 */
@Implements(ParcelFileDescriptor::class)
class ThrowingDetachFdShadow {
    @Implementation
    fun detachFd(): Int = throw IOException("detachFd unavailable in tests")
}
