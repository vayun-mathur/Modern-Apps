package com.vayunmathur.backup.service

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport
import android.app.backup.RestoreDescription
import android.app.backup.RestoreSet
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.vayunmathur.backup.MainActivity
import com.vayunmathur.backup.R
import com.vayunmathur.backup.data.backend.BackendFactory
import com.vayunmathur.backup.data.backend.BackupRepository
import com.vayunmathur.backup.domain.crypto.Crypto
import com.vayunmathur.backup.platform.crypto.KeyManager
import com.vayunmathur.backup.data.BackupConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The system app-data backup transport: the framework's BackupManager streams each
 * app's key/value entities and full-data tar streams through here, and we encrypt them
 * with [Crypto] and store them via the active [BackupRepository] backend; restore
 * reverses it. Bound by the platform through [ConfigurableBackupTransportService].
 *
 * Live behavior requires a platform-signed priv-app install whitelisted for
 * android.permission.BACKUP; it cannot be exercised on a stock device. The framework
 * callbacks are synchronous on a binder thread, so suspend backend calls are bridged
 * with [runBlocking].
 *
 * Ported concept (Seedvault ConfigurableBackupTransport) — see backup/LICENSE-Seedvault.
 */
class ConfigurableBackupTransport(private val context: Context) : BackupTransport() {

    private val config = BackupConfig(context)
    private val keyManager = KeyManager(context)

    // --- Full-backup streaming state (spans performFullBackup → sendBackupData* → finishBackup) ---
    private var fullBackupPackage: String? = null
    private var fullBackupInput: InputStream? = null
    private var fullBackupTemp: File? = null
    private var fullBackupTempOut: OutputStream? = null

    // --- Restore state ---
    private var restorePackages: List<String> = emptyList()
    private var restoreIndex = 0
    private var currentRestorePackage: String? = null
    private var fullRestoreTemp: File? = null
    private var fullRestoreInput: InputStream? = null
    private var fullRestoreOutput: OutputStream? = null

    // --- Identity / configuration ---

    override fun name(): String = COMPONENT
    override fun transportDirName(): String = "com.vayunmathur.backup"

    /**
     * Settings' backup page is this app's only entry point - there is no launcher icon - so
     * both intents point at [MainActivity].
     *
     * The framework distinguishes the two: [configurationIntent] is opened by tapping the
     * destination row (where a cloud transport would put account selection), while
     * [dataManagementIntent] is the "manage" affordance labelled with
     * [dataManagementIntentLabel]. This transport has a single screen covering both, so both
     * lead there rather than one of them dead-ending.
     *
     * NEW_TASK because the framework starts these from Settings' context, not an activity.
     */
    override fun configurationIntent(): Intent = settingsIntent()

    override fun dataManagementIntent(): Intent = settingsIntent()

    override fun dataManagementIntentLabel(): CharSequence =
        context.getString(R.string.data_management_label)

    private fun settingsIntent(): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun currentDestinationString(): String =
        repoOrNull()?.backend?.displayName ?: context.getString(R.string.destination_not_configured)

    override fun getTransportFlags(): Int = 0
    override fun requestBackupTime(): Long = 0
    override fun requestFullBackupTime(): Long = 0
    override fun getBackupQuota(packageName: String, isFullBackup: Boolean): Long = Long.MAX_VALUE

    override fun initializeDevice(): Int = tryOp {
        val repo = repoOrNull() ?: return@tryOp TRANSPORT_NOT_INITIALIZED
        runBlocking {
            // Wipe any previous backup set so the device starts clean.
            for (pkg in repo.list(BackupRepository.KV_DIR)) repo.delete(BackupRepository.kvPackageDir(pkg))
            for (pkg in repo.list(BackupRepository.FULL_DIR)) repo.delete(BackupRepository.fullPath(pkg))
        }
        TRANSPORT_OK
    }

    // --- Key/value backup ---

    override fun performBackup(packageInfo: PackageInfo, inFd: ParcelFileDescriptor): Int =
        performBackup(packageInfo, inFd, 0)

    override fun performBackup(packageInfo: PackageInfo, inFd: ParcelFileDescriptor, flags: Int): Int = tryOp {
        val repo = repoOrNull() ?: return@tryOp TRANSPORT_NOT_INITIALIZED
        val pkg = packageInfo.packageName
        val reader = backupDataInput(inFd.fileDescriptor)
        runBlocking {
            // A non-incremental pass streams the full dataset with no per-key deletions,
            // so drop the previously stored keys first to avoid leaving stale entities.
            if (flags and FLAG_NON_INCREMENTAL != 0) {
                repo.delete(BackupRepository.kvPackageDir(pkg))
            }
            while (reader.readNextHeader()) {
                val key = reader.key
                val keyId = encodeKey(key)
                val size = reader.dataSize
                if (size < 0) {
                    // A negative size marks a deleted key (incremental pass).
                    repo.delete(BackupRepository.kvPath(pkg, keyId))
                } else {
                    val data = ByteArray(size)
                    // readEntityData may return the entity in several chunks.
                    var offset = 0
                    while (offset < size) {
                        val n = reader.readEntityData(data, offset, size - offset)
                        if (n <= 0) break
                        offset += n
                    }
                    if (offset < size) {
                        throw IOException("truncated entity $key for $pkg: $offset of $size bytes")
                    }
                    repo.writeEncrypted(BackupRepository.kvPath(pkg, keyId), data)
                }
            }
        }
        TRANSPORT_OK
    }

    override fun clearBackupData(packageInfo: PackageInfo): Int = tryOp {
        val repo = repoOrNull() ?: return@tryOp TRANSPORT_NOT_INITIALIZED
        val pkg = packageInfo.packageName
        runBlocking {
            repo.delete(BackupRepository.kvPackageDir(pkg))
            repo.delete(BackupRepository.fullPath(pkg))
        }
        TRANSPORT_OK
    }

    override fun finishBackup(): Int = tryOp {
        val pkg = fullBackupPackage ?: return@tryOp TRANSPORT_OK // key/value: writes already flushed
        val temp = fullBackupTemp
        try {
            fullBackupTempOut?.flush()
            fullBackupTempOut?.close()
            fullBackupInput?.close()
            val repo = repoOrNull() ?: return@tryOp TRANSPORT_NOT_INITIALIZED
            if (temp != null && temp.exists()) {
                runBlocking {
                    FileInputStream(temp).use { repo.writeEncrypted(BackupRepository.fullPath(pkg), it) }
                }
            }
            TRANSPORT_OK
        } finally {
            temp?.delete()
            resetFullBackup()
        }
    }

    // --- Full-data backup ---

    override fun checkFullBackupSize(size: Long): Int = TRANSPORT_OK

    override fun performFullBackup(targetPackage: PackageInfo, socket: ParcelFileDescriptor): Int =
        performFullBackup(targetPackage, socket, 0)

    override fun performFullBackup(targetPackage: PackageInfo, socket: ParcelFileDescriptor, flags: Int): Int =
        tryOp {
            if (repoOrNull() == null) return@tryOp TRANSPORT_NOT_INITIALIZED
            resetFullBackup()
            val pkg = targetPackage.packageName
            val temp = File.createTempFile("full-backup", ".tmp", context.cacheDir)
            fullBackupPackage = pkg
            fullBackupInput = ParcelFileDescriptor.AutoCloseInputStream(socket)
            fullBackupTemp = temp
            fullBackupTempOut = FileOutputStream(temp)
            TRANSPORT_OK
        }

    override fun sendBackupData(numBytes: Int): Int = tryOp {
        val input = fullBackupInput ?: return@tryOp TRANSPORT_ERROR
        val out = fullBackupTempOut ?: return@tryOp TRANSPORT_ERROR
        val buffer = ByteArray(CHUNK)
        var remaining = numBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        // A short stream must not be reported as consumed: finishBackup would
        // otherwise encrypt a truncated tar as a complete full backup.
        if (remaining > 0) TRANSPORT_ERROR else TRANSPORT_OK
    }

    override fun cancelFullBackup() {
        fullBackupTemp?.delete()
        resetFullBackup()
    }

    // --- Restore-set discovery ---

    override fun getAvailableRestoreSets(): Array<RestoreSet> =
        arrayOf(RestoreSet(name(), Build.MODEL ?: "device", CURRENT_TOKEN))

    override fun getCurrentRestoreSet(): Long = CURRENT_TOKEN

    override fun startRestore(token: Long, packages: Array<PackageInfo>): Int = tryOp {
        if (repoOrNull() == null) return@tryOp TRANSPORT_NOT_INITIALIZED
        restorePackages = packages.map { it.packageName }
        restoreIndex = 0
        currentRestorePackage = null
        resetFullRestore()
        TRANSPORT_OK
    }

    override fun nextRestorePackage(): RestoreDescription {
        val repo = repoOrNull() ?: return RestoreDescription.NO_MORE_PACKAGES
        return runBlocking {
            while (restoreIndex < restorePackages.size) {
                val pkg = restorePackages[restoreIndex++]
                val hasKv = repo.list(BackupRepository.kvPackageDir(pkg)).isNotEmpty()
                if (hasKv) {
                    currentRestorePackage = pkg
                    return@runBlocking RestoreDescription(pkg, RestoreDescription.TYPE_KEY_VALUE)
                }
                if (repo.exists(BackupRepository.fullPath(pkg))) {
                    currentRestorePackage = pkg
                    resetFullRestore()
                    return@runBlocking RestoreDescription(pkg, RestoreDescription.TYPE_FULL_STREAM)
                }
            }
            currentRestorePackage = null
            RestoreDescription.NO_MORE_PACKAGES
        }
    }

    // --- Key/value restore ---

    override fun getRestoreData(outFd: ParcelFileDescriptor): Int = tryOp {
        val repo = repoOrNull() ?: return@tryOp TRANSPORT_NOT_INITIALIZED
        val pkg = currentRestorePackage ?: return@tryOp TRANSPORT_ERROR
        val writer = backupDataOutput(outFd.fileDescriptor)
        runBlocking {
            for (keyId in repo.list(BackupRepository.kvPackageDir(pkg))) {
                val data = repo.readEncrypted(BackupRepository.kvPath(pkg, keyId))
                writer.writeEntityHeader(decodeKey(keyId), data.size)
                writer.writeEntityData(data, data.size)
            }
        }
        TRANSPORT_OK
    }

    // --- Full-data restore ---

    override fun getNextFullRestoreDataChunk(socket: ParcelFileDescriptor): Int {
        return try {
            val pkg = currentRestorePackage ?: return NO_MORE_DATA
            if (fullRestoreInput == null) {
                val repo = repoOrNull() ?: return TRANSPORT_ERROR
                if (!runBlocking { repo.exists(BackupRepository.fullPath(pkg)) }) return NO_MORE_DATA
                val temp = File.createTempFile("full-restore", ".tmp", context.cacheDir)
                runBlocking {
                    FileOutputStream(temp).use { repo.readEncryptedTo(BackupRepository.fullPath(pkg), it) }
                }
                fullRestoreTemp = temp
                fullRestoreInput = FileInputStream(temp)
                fullRestoreOutput = ParcelFileDescriptor.AutoCloseOutputStream(socket)
            }
            val input = fullRestoreInput ?: return TRANSPORT_ERROR
            val out = fullRestoreOutput ?: return TRANSPORT_ERROR
            val buffer = ByteArray(CHUNK)
            val read = input.read(buffer)
            if (read < 0) {
                resetFullRestore()
                NO_MORE_DATA
            } else {
                out.write(buffer, 0, read)
                out.flush()
                read
            }
        } catch (_: Exception) {
            resetFullRestore()
            TRANSPORT_ERROR
        }
    }

    override fun abortFullRestore(): Int {
        resetFullRestore()
        return TRANSPORT_OK
    }

    override fun finishRestore() {
        resetFullRestore()
        currentRestorePackage = null
    }

    // --- Internals ---

    private fun repoOrNull(): BackupRepository? {
        return try {
            val settings = runBlocking { config.settings.first() }
            val backend = BackendFactory.create(context, settings) ?: return null
            if (!keyManager.hasMasterKey()) return null
            BackupRepository(backend, Crypto(keyManager.getMasterKey()))
        } catch (_: Exception) {
            null
        }
    }

    private inline fun tryOp(block: () -> Int): Int = try {
        block()
    } catch (_: Exception) {
        TRANSPORT_ERROR
    }

    private fun resetFullBackup() {
        fullBackupPackage = null
        fullBackupInput = null
        fullBackupTemp = null
        fullBackupTempOut = null
    }

    private fun resetFullRestore() {
        runCatching { fullRestoreInput?.close() }
        runCatching { fullRestoreOutput?.close() }
        fullRestoreTemp?.delete()
        fullRestoreInput = null
        fullRestoreOutput = null
        fullRestoreTemp = null
    }

    // The FileDescriptor constructors of BackupDataInput/Output are hidden from the
    // public SDK (only the read/write methods are public API), so build them reflectively.
    private fun backupDataInput(fd: java.io.FileDescriptor): BackupDataInput =
        BackupDataInput::class.java.getDeclaredConstructor(java.io.FileDescriptor::class.java)
            .apply { isAccessible = true }.newInstance(fd)

    private fun backupDataOutput(fd: java.io.FileDescriptor): BackupDataOutput =
        BackupDataOutput::class.java.getDeclaredConstructor(java.io.FileDescriptor::class.java)
            .apply { isAccessible = true }.newInstance(fd)

    private fun encodeKey(key: String): String =
        Base64.encodeToString(key.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeKey(keyId: String): String =
        String(Base64.decode(keyId, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    companion object {
        private const val COMPONENT = "com.vayunmathur.backup/.service.ConfigurableBackupTransportService"
        private const val CHUNK = 32 * 1024
        // A single current backup set; token is stable so restore can find it.
        private const val CURRENT_TOKEN = 1L
    }
}
