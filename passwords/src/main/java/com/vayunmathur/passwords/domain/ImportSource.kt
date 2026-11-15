package com.vayunmathur.passwords.domain

import androidx.annotation.StringRes
import com.vayunmathur.passwords.R

/** Kinds of import source. */
enum class ImportSource(
    @StringRes val label: Int,
    val nameHeaders: Array<String>,
    val usernameHeaders: Array<String>,
    val passwordHeaders: Array<String>,
    val urlHeaders: Array<String>,
    val totpHeaders: Array<String>,
    val emailHeaders: Array<String> = arrayOf(),
    val noteHeaders: Array<String> = arrayOf(),
    val typeHeaders: Array<String> = arrayOf(),
    // Some exporters put several URLs in a single field separated by commas
    // (kept as one CSV field via text qualifiers).
    val splitUrlsOnComma: Boolean = false,
) {
    BITWARDEN(
        label = R.string.import_source_bitwarden,
        nameHeaders = arrayOf("name"),
        usernameHeaders = arrayOf("login_username", "username"),
        passwordHeaders = arrayOf("login_password", "password"),
        urlHeaders = arrayOf("login_uri", "uri"),
        totpHeaders = arrayOf("login_totp", "totp"),
    ),
    CHROME(
        label = R.string.import_source_chrome,
        nameHeaders = arrayOf("name"),
        usernameHeaders = arrayOf("username"),
        passwordHeaders = arrayOf("password"),
        urlHeaders = arrayOf("url"),
        totpHeaders = arrayOf("note"),
    ),
    FIREFOX(
        label = R.string.import_source_firefox,
        nameHeaders = arrayOf("url"),
        usernameHeaders = arrayOf("username"),
        passwordHeaders = arrayOf("password"),
        urlHeaders = arrayOf("url"),
        totpHeaders = arrayOf(),
    ),
    LASTPASS(
        label = R.string.import_source_lastpass,
        nameHeaders = arrayOf("name"),
        usernameHeaders = arrayOf("username"),
        passwordHeaders = arrayOf("password"),
        urlHeaders = arrayOf("url"),
        totpHeaders = arrayOf("totp"),
    ),
    ONE_PASSWORD(
        label = R.string.import_source_one_password,
        nameHeaders = arrayOf("title"),
        usernameHeaders = arrayOf("username"),
        passwordHeaders = arrayOf("password"),
        urlHeaders = arrayOf("url"),
        totpHeaders = arrayOf("otpauth", "otp"),
    ),
    DASHLANE(
        label = R.string.import_source_dashlane,
        nameHeaders = arrayOf("title"),
        usernameHeaders = arrayOf("username", "username2", "username3"),
        passwordHeaders = arrayOf("password"),
        urlHeaders = arrayOf("url"),
        totpHeaders = arrayOf("otpsecret"),
    ),
    APPLE(
        label = R.string.import_source_apple,
        nameHeaders = arrayOf("title"),
        usernameHeaders = arrayOf("username"),
        passwordHeaders = arrayOf("password"),
        urlHeaders = arrayOf("url"),
        totpHeaders = arrayOf("otpauth", "otp"),
    ),
    PROTON_PASS(
        label = R.string.import_source_proton_pass,
        nameHeaders = arrayOf("name"),
        usernameHeaders = arrayOf("username"),
        passwordHeaders = arrayOf("password"),
        urlHeaders = arrayOf("url"),
        totpHeaders = arrayOf("totp"),
        emailHeaders = arrayOf("email"),
        noteHeaders = arrayOf("note"),
        typeHeaders = arrayOf("type"),
        splitUrlsOnComma = true,
    ),
}
