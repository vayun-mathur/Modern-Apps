package com.vayunmathur.email.data

import com.vayunmathur.email.ServerConfig

/**
 * Built-in email provider with pre-filled IMAP/SMTP server settings + a short
 * instruction list for users who need to create an app password.
 *
 * Every preset authenticates with an app password (a long random string the
 * user generates in their provider's account-security settings). `custom` is
 * the escape hatch — host/port/security are entered manually.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val imap: ServerConfig?,
    val smtp: ServerConfig?,
    val authType: String,
    /** Link to the provider's help page on creating an app password. */
    val appPasswordHelpUrl: String?,
    /** Short bullet-point steps shown above the help link. */
    val instructions: List<String>,
)

const val PROVIDER_GMAIL = "gmail"
const val PROVIDER_OUTLOOK = "outlook"
const val PROVIDER_YAHOO = "yahoo"
const val PROVIDER_ICLOUD = "icloud"
const val PROVIDER_FASTMAIL = "fastmail"
const val PROVIDER_CUSTOM = "custom"

val PROVIDER_PRESETS: List<ProviderPreset> = listOf(
    ProviderPreset(
        id = PROVIDER_GMAIL,
        displayName = "Gmail",
        imap = ServerConfig("imap.gmail.com", 993, useSsl = true),
        smtp = ServerConfig("smtp.gmail.com", 465, useSsl = true),
        authType = "password",
        appPasswordHelpUrl = "https://support.google.com/accounts/answer/185833",
        instructions = listOf(
            "Two-step verification must be enabled on your Google account.",
            "Go to https://myaccount.google.com/apppasswords.",
            "Pick \"Mail\" and \"Other\" — name it \"Email\".",
            "Copy the 16-character app password Google shows and paste it below.",
        ),
    ),
    ProviderPreset(
        id = PROVIDER_OUTLOOK,
        displayName = "Outlook / Microsoft 365",
        imap = ServerConfig("outlook.office365.com", 993, useSsl = true),
        smtp = ServerConfig("smtp-mail.outlook.com", 587, useSsl = false),
        authType = "oauth2",
        appPasswordHelpUrl = null,
        instructions = listOf(
            "Microsoft no longer allows app passwords for mail.",
            "Tap \"Sign in with Microsoft\" and approve access.",
        ),
    ),
    ProviderPreset(
        id = PROVIDER_YAHOO,
        displayName = "Yahoo Mail",
        imap = ServerConfig("imap.mail.yahoo.com", 993, useSsl = true),
        smtp = ServerConfig("smtp.mail.yahoo.com", 465, useSsl = true),
        authType = "password",
        appPasswordHelpUrl = "https://help.yahoo.com/kb/SLN15241.html",
        instructions = listOf(
            "Sign in to Yahoo and open Account Info → Account security.",
            "Choose \"Generate app password\" (or \"Manage app passwords\").",
            "Pick \"Other app\" and name it \"Email\".",
            "Copy the 16-character password Yahoo shows and paste it below.",
        ),
    ),
    ProviderPreset(
        id = PROVIDER_ICLOUD,
        displayName = "iCloud Mail",
        imap = ServerConfig("imap.mail.me.com", 993, useSsl = true),
        smtp = ServerConfig("smtp.mail.me.com", 587, useSsl = false),
        authType = "password",
        appPasswordHelpUrl = "https://support.apple.com/en-us/102654",
        instructions = listOf(
            "Two-factor authentication must be enabled on your Apple ID.",
            "Go to https://account.apple.com → Sign-in and Security → App-Specific Passwords.",
            "Tap \"Generate an app-specific password\" and name it \"Email\".",
            "Copy the password and paste it below.",
            "Use your full iCloud email address as the username.",
        ),
    ),
    ProviderPreset(
        id = PROVIDER_FASTMAIL,
        displayName = "Fastmail",
        imap = ServerConfig("imap.fastmail.com", 993, useSsl = true),
        smtp = ServerConfig("smtp.fastmail.com", 465, useSsl = true),
        authType = "password",
        appPasswordHelpUrl = "https://www.fastmail.help/hc/en-us/articles/1500000278342",
        instructions = listOf(
            "Sign in at https://www.fastmail.com and open Settings → Password & Security.",
            "Under \"App passwords\", click \"New app password\".",
            "Give it a name like \"Email\" and grant IMAP + SMTP access.",
            "Copy the password and paste it below.",
        ),
    ),
    ProviderPreset(
        id = PROVIDER_CUSTOM,
        displayName = "Other (IMAP / SMTP)",
        imap = null,
        smtp = null,
        authType = "password",
        appPasswordHelpUrl = null,
        instructions = listOf(
            "Enter the IMAP and SMTP server details from your email provider.",
            "Most providers list these on a \"Server settings\" or \"Mail client setup\" page.",
            "If your provider requires an app password instead of your regular password, generate one there first.",
        ),
    ),
)

fun providerPreset(id: String): ProviderPreset? = PROVIDER_PRESETS.firstOrNull { it.id == id }
