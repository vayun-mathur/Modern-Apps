package com.vayunmathur.messages.signal.media

import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.web.SignalHttpClient
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AttachmentManager {

    private const val TAG = "AttachmentManager"

    suspend fun upload(
        data: ByteArray,
        contentType: String,
        fileName: String?,
    ): SignalServiceProtos.AttachmentPointer? {
        return try {
            val random = SecureRandom()
            val aesKey = ByteArray(32).also { random.nextBytes(it) }
            val macKey = ByteArray(32).also { random.nextBytes(it) }
            val iv = ByteArray(16).also { random.nextBytes(it) }

            val encrypted = encryptAttachment(data, aesKey, macKey, iv)
            val digest = MessageDigest.getInstance("SHA-256").digest(encrypted)

            val formResponse = SignalHttpClient.request(
                host = SignalHttpClient.CDN1_HOST,
                method = "GET",
                path = "/v4/attachments/form/upload",
            )
            if (!formResponse.isSuccessful) return null

            val formJson = JSONObject(formResponse.body!!.string())
            val uploadUrl = formJson.getString("signedUploadLocation")
            val cdnId = formJson.getLong("cdn")

            val uploadResponse = SignalHttpClient.request(
                host = uploadUrl.substringAfter("://").substringBefore("/"),
                method = "PUT",
                path = "/" + uploadUrl.substringAfter("://").substringAfter("/"),
                body = encrypted,
                contentType = contentType,
            )
            if (!uploadResponse.isSuccessful) return null

            SignalServiceProtos.AttachmentPointer.newBuilder()
                .setCdnId(cdnId)
                .setKey(com.google.protobuf.ByteString.copyFrom(aesKey + macKey))
                .setDigest(com.google.protobuf.ByteString.copyFrom(digest))
                .setSize(data.size)
                .setContentType(contentType)
                .apply { if (fileName != null) setFileName(fileName) }
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload attachment", e)
            null
        }
    }

    private fun encryptAttachment(data: ByteArray, aesKey: ByteArray, macKey: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val ciphertext = cipher.doFinal(data)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)
        val hmac = mac.doFinal()

        return iv + ciphertext + hmac
    }
}
