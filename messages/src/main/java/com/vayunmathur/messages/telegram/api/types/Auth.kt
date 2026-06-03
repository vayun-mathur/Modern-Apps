package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class AuthSentCode(
    val phoneCodeHash: String,
) : TlObject {
    override val typeId = 0x2390fe44.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): AuthSentCode {
            val flags = Fields.decode(buf)
            val typeId = buf.int32() // type constructor id
            // Only some code types have a length field
            when (typeId) {
                0x3dbb5986.toInt(), // sentCodeTypeApp
                0xc000bba2.toInt(), // sentCodeTypeSms
                0x5353e5a7.toInt(), // sentCodeTypeCall
                -> buf.int32() // length
                0xab03c6d9.toInt(), // sentCodeTypeFlashCall
                -> buf.string() // pattern
            }
            val phoneCodeHash = buf.string()
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
            if (flags.has(0)) buf.int32() // tmp_sessions (flags.0)
            if (flags.has(1)) buf.int32() // otherwise_relogin_days (flags.1)
            if (flags.has(2)) buf.bytes() // future_auth_token (flags.2)
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
