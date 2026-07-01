package com.vayunmathur.messages.telegram.api.functions

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// auth.sendCode
data class AuthSendCode(
    val phoneNumber: String,
    val apiId: Int,
    val apiHash: String,
) : TlMethod<TlObject> {
    override val typeId = 0xa677244f.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putString(phoneNumber)
        buf.putInt32(apiId)
        buf.putString(apiHash)
        // settings: codeSettings
        buf.putId(0xad253d78.toInt()) // codeSettings constructor
        buf.putInt32(0) // flags
    }
}

// auth.signIn
data class AuthSignIn(val phoneNumber: String, val phoneCodeHash: String, val phoneCode: String) : TlMethod<TlObject> {
    override val typeId = 0x8d52a951.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(1) // flags: has phone_code (bit 0)
        buf.putString(phoneNumber)
        buf.putString(phoneCodeHash)
        buf.putString(phoneCode)
    }
}

// auth.checkPassword
data class AuthCheckPassword(val password: TlObject) : TlMethod<TlObject> {
    override val typeId = 0xd18b4d16.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        password.encode(buf)
    }
}

// auth.logOut
object AuthLogOut : TlMethod<TlObject> {
    override val typeId = 0x3e72ba19.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

// auth.exportAuthorization
data class AuthExportAuthorization(val dcId: Int) : TlMethod<TlObject> {
    override val typeId = 0xe5bfffcd.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt32(dcId) }
}

// auth.importAuthorization
data class AuthImportAuthorization(val id: Long, val bytes: ByteArray) : TlMethod<TlObject> {
    override val typeId = 0xa57a7dad.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(id); buf.putBytes(bytes) }
}

// auth.exportLoginToken — request a QR-login token on the current DC.
data class AuthExportLoginToken(
    val apiId: Int,
    val apiHash: String,
    val exceptIds: List<Long> = emptyList(),
) : TlMethod<TlObject> {
    override val typeId = 0xb7e085fe.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(apiId)
        buf.putString(apiHash)
        buf.putVectorHeader(exceptIds.size)
        for (id in exceptIds) buf.putInt64(id)
    }
}

// auth.importLoginToken — finish QR login on the DC named by loginTokenMigrateTo.
data class AuthImportLoginToken(val token: ByteArray) : TlMethod<TlObject> {
    override val typeId = 0x95ac5ce4.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putBytes(token) }
}

// account.getPassword
object AccountGetPassword : TlMethod<TlObject> {
    override val typeId = 0x548a30f5.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

// initConnection + invokeWithLayer wrapper
data class InitConnection(
    val apiId: Int,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val systemLangCode: String,
    val langPack: String,
    val langCode: String,
    val inner: TlObject,
) : TlMethod<TlObject> {
    override val typeId = 0xc1cd5ea9.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(0xda9b0d0d.toInt()) // invokeWithLayer
        buf.putInt32(225) // layer
        buf.putId(typeId)
        buf.putInt32(0) // flags
        buf.putInt32(apiId)
        buf.putString(deviceModel)
        buf.putString(systemVersion)
        buf.putString(appVersion)
        buf.putString(systemLangCode)
        buf.putString(langPack)
        buf.putString(langCode)
        inner.encode(buf)
    }
}

// help.getConfig
object HelpGetConfig : TlMethod<TlObject> {
    override val typeId = 0xc4f9186b.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}
