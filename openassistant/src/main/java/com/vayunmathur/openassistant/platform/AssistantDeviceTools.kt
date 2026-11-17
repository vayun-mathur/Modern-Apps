package com.vayunmathur.openassistant.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.MemoryDao
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import com.vayunmathur.openassistant.util.InferenceService

/**
 * Device-local tools (app list, messaging, memories, clock helpers) backing
 * [com.vayunmathur.openassistant.platform.AssistantTools]. Split from [AssistantToolIntents] so neither class
 * exceeds the TooManyFunctions limit.
 */
internal class AssistantDeviceTools(private val context: Context) {

    fun getAppList(): String {
        val pm = context.packageManager
        // Launcher activities only: since Android 11 the full installed-package list is
        // filtered unless QUERY_ALL_PACKAGES is held, while the manifest's <queries>
        // MAIN/LAUNCHER declaration already makes every launchable app visible. A package
        // with no launcher entry is not an app the model can open anyway.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { it.loadLabel(pm).toString() }
            .toString()
    }

    fun openApp(packageId: String, onMissing: (String) -> String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId)
            ?: return onMissing(packageId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Success: Opened $packageId"
    }

    fun sendMessage(recipient: String, message: String): String = try {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO).apply {
                data = "smsto:$recipient".toUri()
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        "Opened messaging app."
    } catch (expected: Exception) {
        "Error: ${expected.message}"
    }

    fun makePhoneCall(recipient: String): String = try {
        context.startActivity(
            Intent(Intent.ACTION_DIAL).apply {
                data = "tel:$recipient".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        "Opened dialer."
    } catch (expected: Exception) {
        "Error: ${expected.message}"
    }

    fun getLocalCurrentDateTime(): String {
        val now = Clock.System.now()
        val local = now.toLocalDateTime(TimeZone.currentSystemDefault())
        val zoneId = TimeZone.currentSystemDefault().id
        val epochMillis = now.toEpochMilliseconds()
        return "$zoneId: $local (epochMillis=$epochMillis)"
    }

    fun setConversationTitle(newTitle: String): String {
        InferenceService.newTitle = newTitle
        return "Conversation title set successfully"
    }

    suspend fun getMemories(memoryDao: MemoryDao?): String =
        memoryDao?.getAll()?.joinToString("\n") { "[${it.id}] ${it.content}" }
            ?: "Error: MemoryDao is null"

    suspend fun removeMemory(memoryDao: MemoryDao?, id: Double): String {
        if (memoryDao == null) return "Error: MemoryDao is null"
        memoryDao.deleteById(id.toLong())
        return "Success: Removed memory with id ${id.toLong()}"
    }

    suspend fun addToMemory(memoryDao: MemoryDao?, content: String): String {
        if (memoryDao == null) return "Error: MemoryDao is null"
        memoryDao.upsert(Memory(content))
        return "Success: Added memory"
    }
}
