package com.vayunmathur.findfamily

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.runtime.Composable
import androidx.core.content.PermissionChecker
import androidx.core.database.getStringOrNull

class Platform(private val context: Context) {
    @SuppressLint("Range")
    @Composable
    fun requestPickContact(callback: (String, String?)->Unit): ()->Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            if(uri == null) return@rememberLauncherForActivityResult
            val cur = context.contentResolver.query(uri, null, null, null)!!
            if (cur.moveToFirst()) {
                val name = cur.getString(cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                val photo = cur.getStringOrNull(cur.getColumnIndex(ContactsContract.Contacts.PHOTO_URI))
                callback(name, photo)
            }
            cur.close()
        }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if(it) {
                launcher.launch()
            }
        }
        return {
            if(PermissionChecker.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PermissionChecker.PERMISSION_GRANTED) {
                launcher.launch()
            } else {
                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    fun copy(content: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = android.content.ClipData.newPlainText("text", content)
        clipboardManager.setPrimaryClip(clipData)
    }
}