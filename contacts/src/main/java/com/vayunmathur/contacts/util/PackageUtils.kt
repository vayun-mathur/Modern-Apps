package com.vayunmathur.contacts.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log

/** Package-name constants and installed-app / messaging-platform lookups for contacts. */
object PackageUtils {
    const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val TELEGRAM_PACKAGE = "org.telegram.messenger"
    const val GOOGLE_MEET_PACKAGE = "com.google.android.apps.tachyon"

    private const val TAG = "ContactPlatforms"

    fun isAppInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isSignalInstalled(context: Context) = isAppInstalled(context, SIGNAL_PACKAGE)
    fun isWhatsAppInstalled(context: Context) = isAppInstalled(context, WHATSAPP_PACKAGE)
    fun isTelegramInstalled(context: Context) = isAppInstalled(context, TELEGRAM_PACKAGE)
    fun isGoogleMeetInstalled(context: Context) = isAppInstalled(context, GOOGLE_MEET_PACKAGE)

    private fun getAggregateContactId(context: Context, rawContactId: Long): Long? {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: android.database.SQLException) {
            Log.error(TAG, "Error getting aggregate contact ID", e)
            null
        } catch (e: SecurityException) {
            Log.error(TAG, "Error getting aggregate contact ID", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Error getting aggregate contact ID", e)
            null
        }
    }

    fun getContactPlatforms(context: Context, rawContactId: Long): ContactPlatforms {
        val aggregateContactId = getAggregateContactId(context, rawContactId)
            ?: return ContactPlatforms()

        var result = ContactPlatforms()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.MIMETYPE
                ),
                buildPlatformSelection(),
                arrayOf(aggregateContactId.toString(), "%whatsapp%", "%securesms%", "%telegram%"),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val mime = cursor.getString(mimeIdx) ?: continue
                    result = result.withPlatformRow(mime, id)
                }
            }
        } catch (e: android.database.SQLException) {
            Log.error(TAG, "Error querying platform data rows", e)
        } catch (e: SecurityException) {
            Log.error(TAG, "Error querying platform data rows", e)
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Error querying platform data rows", e)
        }

        return result
    }

    private fun buildPlatformSelection(): String =
        "${ContactsContract.Data.CONTACT_ID} = ? AND (" +
            "${ContactsContract.Data.MIMETYPE} LIKE ? OR " +
            "${ContactsContract.Data.MIMETYPE} LIKE ? OR " +
            "${ContactsContract.Data.MIMETYPE} LIKE ?)"

    private fun ContactPlatforms.withPlatformRow(mime: String, id: Long): ContactPlatforms =
        when {
            mime.isWhatsAppCall() -> copy(whatsAppCallId = id)
            mime.isWhatsAppVideo() -> copy(whatsAppVideoId = id)
            mime.isWhatsAppMessage() -> copy(whatsAppMessageId = id)
            mime.isSignalVideo() -> copy(signalVideoId = id)
            mime.isSignalCall() -> copy(signalCallId = id)
            mime.isSignalMessage() -> copy(signalMessageId = id)
            mime.isTelegramVideo() -> copy(telegramVideoId = id)
            mime.isTelegramCall() -> copy(telegramCallId = id)
            mime.isTelegramMessage() -> copy(telegramMessageId = id)
            else -> this
        }

    private fun String.isWhatsAppCall(): Boolean = contains("whatsapp") && contains("voip")
    private fun String.isWhatsAppVideo(): Boolean = contains("whatsapp") && contains("video")
    private fun String.isWhatsAppMessage(): Boolean =
        contains("whatsapp") && (contains("profile") || contains("contact"))
    private fun String.isSignalVideo(): Boolean = contains("securesms") && contains("video")
    private fun String.isSignalCall(): Boolean = contains("securesms") && contains("call")
    private fun String.isSignalMessage(): Boolean =
        contains("securesms") && (contains("contact") || contains("profile"))
    private fun String.isTelegramVideo(): Boolean = contains("telegram") && contains("video")
    private fun String.isTelegramCall(): Boolean = contains("telegram") && contains("call")
    private fun String.isTelegramMessage(): Boolean =
        contains("telegram") && (contains("profile") || contains("contact"))
}

/** Data-row ids for a contact's WhatsApp, Signal and Telegram rows, grouped by call, video and message. */
data class ContactPlatforms(
    val whatsAppCallId: Long? = null,
    val whatsAppVideoId: Long? = null,
    val whatsAppMessageId: Long? = null,
    val signalCallId: Long? = null,
    val signalVideoId: Long? = null,
    val signalMessageId: Long? = null,
    val telegramCallId: Long? = null,
    val telegramVideoId: Long? = null,
    val telegramMessageId: Long? = null,
) {
    val hasWhatsApp get() = whatsAppCallId != null || whatsAppVideoId != null || whatsAppMessageId != null
    val hasSignal get() = signalCallId != null || signalVideoId != null || signalMessageId != null
    val hasTelegram get() = telegramCallId != null || telegramVideoId != null || telegramMessageId != null
    val hasAnyPlatform get() = hasWhatsApp || hasSignal || hasTelegram
}
