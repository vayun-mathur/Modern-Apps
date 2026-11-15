package com.vayunmathur.share.platform.airdrop.link

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import com.vayunmathur.library.log.Log

private const val TAG = "MoseyHal"

/**
 * Google's AWDL ("Mosey") HAL — the daemon that actually puts AirDrop on the air and exposes
 * it as the `mosey0` interface. This app only drives it; without it AirDrop can reach Apple
 * devices only if they happen to share our Wi-Fi network, which in practice they don't.
 *
 * Three ways in, tried in Mosey's own order (`bwz`): the `com.google.android.moseylib`
 * shared library, then the two `IMoseyService/default` binder names. All take the same
 * arguments; only the plumbing differs.
 */
interface MoseyHal {
    val backend: MoseyBackend
    val version: String

    /** Serialized `btc` proto, or null if the backend cannot report it. */
    fun hardwareCapability(): ByteArray?
    fun start(channels: IntArray, token: IBinder, config: ByteArray)
    fun update(countryCode: String, config: ByteArray)
    fun stop()
}

/** Kinds of mosey backend. */
enum class MoseyBackend(val label: String) {
    SharedLibrary("moseylib"),
    AidlAndroid(MoseyDetector.AIDL_ANDROID),
    AidlPixel(MoseyDetector.AIDL_PIXEL),
}

/** What [MoseyDetector.detect] found, for the UI and for logs. */
data class MoseyStatus(
    val backend: MoseyBackend?,
    val detail: String,
) {
    val available: Boolean get() = backend != null
}

/** Singleton holding mosey detector state and operations. */
object MoseyDetector {

    const val SHARED_LIBRARY = "com.google.android.moseylib"
    private const val MANAGER_CLASS = "com.google.android.moseylib.MoseyManager"
    private const val DESCRIPTOR_ANDROID = "com.google.android.moseyservice.IMoseyService"
    private const val DESCRIPTOR_PIXEL = "com.google.pixel.moseyservice.IMoseyService"
    const val AIDL_ANDROID = "$DESCRIPTOR_ANDROID/default"
    const val AIDL_PIXEL = "$DESCRIPTOR_PIXEL/default"

    /** Cheap presence check: no binder transaction, nothing started. */
    fun detect(context: Context): MoseyStatus {
        if (hasSharedLibrary(context) && managerClass() != null) {
            return MoseyStatus(MoseyBackend.SharedLibrary, "shared library $SHARED_LIBRARY")
        }
        val sm = ServiceManagerProxy.instance ?: return MoseyStatus(
            null,
            "ServiceManager unreachable (hidden API blocked; :share must be a whitelisted system app)",
        )
        for ((name, backend) in listOf(
            AIDL_ANDROID to MoseyBackend.AidlAndroid,
            AIDL_PIXEL to MoseyBackend.AidlPixel,
        )) {
            if (sm.isDeclared(name)) return MoseyStatus(backend, "VINTF service $name")
        }
        return MoseyStatus(null, "no Mosey HAL on this device (no $SHARED_LIBRARY, $AIDL_ANDROID or $AIDL_PIXEL)")
    }

    /** Connects to the backend [detect] found, or null if it has gone or cannot be reached. */
    // Broad catch is deliberate: shared-library linkage and binder lookups fail with
    // LinkageError and undocumented RuntimeExceptions, which all mean "not available".
    @Suppress("TooGenericExceptionCaught")
    fun open(context: Context): MoseyHal? {
        val status = detect(context)
        return try {
            when (status.backend) {
                MoseyBackend.SharedLibrary -> managerClass()?.getConstructor()?.newInstance()?.let(::LibraryMoseyHal)
                MoseyBackend.AidlAndroid -> ServiceManagerProxy.instance?.waitForDeclared(AIDL_ANDROID)
                    ?.let { AidlMoseyHal(it, DESCRIPTOR_ANDROID, MoseyBackend.AidlAndroid) }
                MoseyBackend.AidlPixel -> ServiceManagerProxy.instance?.waitForDeclared(AIDL_PIXEL)
                    ?.let { AidlMoseyHal(it, DESCRIPTOR_PIXEL, MoseyBackend.AidlPixel) }
                null -> null
            }
        } catch (e: Throwable) {
            Log.status(TAG, "cannot open ${status.backend}", e)
            null
        }
    }

    private fun hasSharedLibrary(context: Context): Boolean =
        runCatching { context.packageManager.systemSharedLibraryNames?.contains(SHARED_LIBRARY) == true }
            .getOrDefault(false)

    private fun managerClass(): Class<*>? =
        runCatching { Class.forName(MANAGER_CLASS) }.getOrNull()
}

/** Reflective view of the hidden `android.os.ServiceManager`. */
@SuppressLint("PrivateApi")
private class ServiceManagerProxy(private val cls: Class<*>) {

    fun isDeclared(name: String): Boolean =
        runCatching { cls.getMethod("isDeclared", String::class.java).invoke(null, name) as Boolean }
            .getOrDefault(false)

    fun waitForDeclared(name: String): IBinder? =
        cls.getMethod("waitForDeclaredService", String::class.java).invoke(null, name) as IBinder?

    companion object {
        val instance: ServiceManagerProxy? by lazy {
            runCatching { ServiceManagerProxy(Class.forName("android.os.ServiceManager")) }.getOrNull()
        }
    }
}

/**
 * Hand-marshalled `IMoseyService` client. Transaction codes and parcelable layouts are the
 * generated AIDL's (`IMoseyService.Proxy`, `StartMoseyParam`, ...): every parcelable is
 * written as a non-null marker followed by a size-prefixed body.
 */
private class AidlMoseyHal(
    private val binder: IBinder,
    private val descriptor: String,
    override val backend: MoseyBackend,
) : MoseyHal {

    override val version: String by lazy {
        val v = runCatching { transact(TRANSACTION_GET_INTERFACE_VERSION) { readInt() } }.getOrDefault(-1)
        "A$v"
    }

    /** The Pixel-namespaced service predates `queryMoseyHardwareCapability` and the update blob. */
    private val isModern: Boolean get() = backend == MoseyBackend.AidlAndroid

    override fun hardwareCapability(): ByteArray? {
        if (!isModern) return null
        return transact(TRANSACTION_QUERY_CAPABILITY) {
            if (readInt() == 0) return@transact null
            readSized { createByteArray() }
        }
    }

    override fun start(channels: IntArray, token: IBinder, config: ByteArray) {
        transact(TRANSACTION_START, write = {
            writeParcelable {
                writeIntArray(channels)
                writeStrongBinder(token)
                writeInt(Int.MAX_VALUE) // maxSyntheticMdnsResponses, as Mosey passes it
                writeByteArray(config)
            }
        }) { }
    }

    override fun update(countryCode: String, config: ByteArray) {
        transact(TRANSACTION_UPDATE, write = {
            writeParcelable {
                writeString(countryCode)
                if (isModern) writeByteArray(config)
            }
        }) { }
    }

    override fun stop() {
        transact(TRANSACTION_STOP, write = { writeParcelable { } }) { }
    }

    private fun <T> transact(code: Int, write: Parcel.() -> Unit = {}, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            if (!binder.transact(code, data, reply, 0)) throw android.os.RemoteException("Unimplemented")
            reply.readException()
            return reply.read()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private inline fun Parcel.writeParcelable(body: Parcel.() -> Unit) {
        writeInt(1)
        val start = dataPosition()
        writeInt(0)
        body()
        val end = dataPosition()
        setDataPosition(start)
        writeInt(end - start)
        setDataPosition(end)
    }

    private inline fun <T> Parcel.readSized(body: Parcel.() -> T): T? {
        val start = dataPosition()
        val size = readInt()
        val value = if (size > 4) body() else null
        setDataPosition(start + size)
        return value
    }

    private companion object {
        const val TRANSACTION_START = 1
        const val TRANSACTION_STOP = 2
        const val TRANSACTION_UPDATE = 3
        const val TRANSACTION_QUERY_CAPABILITY = 4
        const val TRANSACTION_GET_INTERFACE_VERSION = 16777215
    }
}

/** The `com.google.android.moseylib.MoseyManager` shared library, reached reflectively. */
private class LibraryMoseyHal(private val manager: Any) : MoseyHal {
    private val cls = manager.javaClass
    override val backend = MoseyBackend.SharedLibrary

    override val version: String by lazy {
        "S" + runCatching { cls.getMethod("getVersion").invoke(manager) }.getOrNull()
    }

    override fun hardwareCapability(): ByteArray? {
        val cap = cls.getMethod("queryMoseyHardwareCapability").invoke(manager) ?: return null
        return cap.javaClass.getField("moseyHardwareCapability").get(cap) as ByteArray?
    }

    override fun start(channels: IntArray, token: IBinder, config: ByteArray) {
        cls.getMethod(
            "start",
            IntArray::class.java,
            IBinder::class.java,
            Int::class.javaPrimitiveType,
            ByteArray::class.java,
        ).invoke(manager, channels, token, Int.MAX_VALUE, config)
    }

    override fun update(countryCode: String, config: ByteArray) {
        cls.getMethod("update", String::class.java, ByteArray::class.java).invoke(manager, countryCode, config)
    }

    override fun stop() {
        cls.getMethod("stop").invoke(manager)
    }
}
