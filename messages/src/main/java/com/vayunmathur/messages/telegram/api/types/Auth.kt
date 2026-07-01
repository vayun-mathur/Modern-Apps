package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class AuthSentCode(
    val phoneCodeHash: String,
) : TlObject {
    override val typeId = 0x5e002502.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): AuthSentCode {
            val flags = Fields.decode(buf)
            val typeId = buf.int32() // type constructor id
            when (typeId) {
                0x3dbb5986.toInt(), // sentCodeTypeApp
                0xc000bba2.toInt(), // sentCodeTypeSms
                0x5353e5a7.toInt(), // sentCodeTypeCall
                -> buf.int32() // length
                0xab03c6d9.toInt(), // sentCodeTypeFlashCall
                -> buf.string() // pattern
                0x82006484.toInt() -> { // sentCodeTypeMissedCall
                    buf.string() // prefix
                    buf.int32() // length
                }
                0xd9565c39.toInt() -> { // sentCodeTypeFragmentSms
                    buf.string() // url
                    buf.int32() // length
                }
                0xa416ac81.toInt() -> { // sentCodeTypeSmsWord
                    val f = Fields.decode(buf)
                    if (f.has(0)) buf.string() // beginning
                }
                0xb37794af.toInt() -> { // sentCodeTypeSmsPhrase
                    val f = Fields.decode(buf)
                    if (f.has(0)) buf.string() // beginning
                }
                0x009fd736.toInt() -> { // sentCodeTypeFirebaseSms
                    val f = Fields.decode(buf)
                    if (f.has(0)) buf.bytes() // nonce
                    if (f.has(2)) buf.int64() // play_integrity_project_id
                    if (f.has(2)) buf.bytes() // play_integrity_nonce
                    if (f.has(1)) buf.string() // receipt
                    if (f.has(1)) buf.int32() // push_timeout
                    buf.int32() // length
                }
                0xf450f59b.toInt() -> { // sentCodeTypeEmailCode
                    val f = Fields.decode(buf)
                    buf.string() // email_pattern
                    buf.int32() // length
                    if (f.has(3)) buf.int32() // reset_available_period
                    if (f.has(4)) buf.int32() // reset_pending_date
                }
                0xa5491dea.toInt() -> { // sentCodeTypeSetUpEmailRequired
                    Fields.decode(buf)
                }
            }
            val phoneCodeHash = buf.string()
            if (flags.has(1)) buf.int32() // next_type constructor
            if (flags.has(2)) buf.int32() // timeout
            return AuthSentCode(phoneCodeHash)
        }
    }
}

data class AuthAuthorization(val user: User) : TlObject {
    override val typeId = 0x2ea2c0d4.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): AuthAuthorization {
            val flags = Fields.decode(buf)
            if (flags.has(1)) buf.int32() // otherwise_relogin_days
            if (flags.has(0)) buf.int32() // tmp_sessions
            if (flags.has(2)) buf.bytes() // future_auth_token
            val userTypeId = buf.int32()
            val user = User.decode(buf)
            return AuthAuthorization(user)
        }
    }
}

data class AuthPassword(
    val hasPassword: Boolean,
    val srpId: Long = 0,
    val srpB: ByteArray = ByteArray(0),
    val hint: String = "",
    val currentAlgo: PasswordAlgo? = null,
) : TlObject {
    override val typeId = 0x957b50fb.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): AuthPassword {
            val flags = Fields.decode(buf)
            val hasPassword = flags.has(2)
            val currentAlgo = if (flags.has(2)) PasswordAlgo.decode(buf) else null
            val srpB = if (flags.has(2)) buf.bytes() else ByteArray(0)
            val srpId = if (flags.has(2)) buf.int64() else 0L
            val hint = if (flags.has(3)) buf.string() else ""
            if (flags.has(4)) buf.string() // email_unconfirmed_pattern
            TlSkip.skipBoxedType(buf) // new_algo (mandatory)
            TlSkip.skipBoxedType(buf) // new_secure_algo (mandatory)
            buf.bytes() // secure_random (mandatory)
            if (flags.has(5)) buf.int32() // pending_reset_date
            if (flags.has(6)) buf.string() // login_email_pattern
            return AuthPassword(hasPassword, srpId, srpB, hint, currentAlgo)
        }
    }
}

data class PasswordAlgo(
    val salt1: ByteArray,
    val salt2: ByteArray,
    val g: Int,
    val p: ByteArray,
) {
    companion object {
        fun decode(buf: TlBuffer): PasswordAlgo {
            val algoId = buf.int32() // SHA256SHA256PBKDF2... constructor
            val salt1 = buf.bytes()
            val salt2 = buf.bytes()
            val g = buf.int32()
            val p = buf.bytes()
            return PasswordAlgo(salt1, salt2, g, p)
        }
    }
}

data class InputCheckPasswordSRP(val srpId: Long, val a: ByteArray, val m1: ByteArray) : TlObject {
    override val typeId = 0xd27ff082.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(srpId)
        buf.putBytes(a)
        buf.putBytes(m1)
    }
}

data class AuthExportedAuthorization(val id: Long, val bytes: ByteArray) : TlObject {
    override val typeId = 0xb434e2b8.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer) = AuthExportedAuthorization(buf.int64(), buf.bytes())
    }
}

// ---- QR login (auth.LoginToken) ----

sealed interface AuthLoginToken : TlObject

// auth.loginToken#629f1980 expires:int token:bytes
data class AuthLoginTokenResult(val expires: Int, val token: ByteArray) : AuthLoginToken {
    override val typeId = 0x629f1980.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer) = AuthLoginTokenResult(buf.int32(), buf.bytes())
    }
}

// auth.loginTokenMigrateTo#068e9916 dc_id:int token:bytes — re-import on dc_id.
data class AuthLoginTokenMigrateTo(val dcId: Int, val token: ByteArray) : AuthLoginToken {
    override val typeId = 0x068e9916
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer) = AuthLoginTokenMigrateTo(buf.int32(), buf.bytes())
    }
}

// auth.loginTokenSuccess#390d5c5e authorization:auth.Authorization
data class AuthLoginTokenSuccess(val authorization: TlObject) : AuthLoginToken {
    override val typeId = 0x390d5c5e.toInt()
    override fun encode(buf: TlBuffer) {}
}

// auth.authorizationSignUpRequired#44747e9a — account has no user yet (must sign up).
object AuthAuthorizationSignUpRequired : TlObject {
    override val typeId = 0x44747e9a.toInt()
    override fun encode(buf: TlBuffer) {}
}
