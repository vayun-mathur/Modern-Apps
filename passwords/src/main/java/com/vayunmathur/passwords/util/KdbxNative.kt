// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.passwords.util

import com.vayunmathur.library.log.NativeLoader

/**
 * JNI surface for the native Rust KDBX library (libpasswords_kdbx.so), backed
 * by the Rust `keepass` crate. Replaces keepassjava2 + Bouncy Castle.
 *
 * A vault crosses the boundary as a JSON array of `{ field: value }` objects
 * (standard KeePass fields like Title/UserName/Password/URL plus arbitrary
 * custom string fields). Both calls return null on failure (wrong password,
 * corrupt vault, or bad JSON).
 */
internal object KdbxNative {
    external fun nativeImport(password: String, kdbx: ByteArray): String?
    external fun nativeExport(password: String, entriesJson: String): ByteArray?

    val available: Boolean = NativeLoader.load("passwords_kdbx", "KdbxNative")
}
