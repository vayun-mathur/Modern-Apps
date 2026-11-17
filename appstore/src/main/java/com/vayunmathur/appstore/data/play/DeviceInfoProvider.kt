package com.vayunmathur.appstore.data.play
import com.vayunmathur.appstore.R

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale
import java.util.Properties

/**
 * Port of Aurora's NativeDeviceInfoProvider.
 * Builds device Properties required by Aurora dispenser anonymous auth.
 * Also wraps gplayapi's DeviceInfoProvider by converting props.
 */
object DeviceInfoProvider {

    /**
     * The exact device profile Aurora ships and its dispenser is tuned to:
     * gplayapi's bundled `gplayapi_px_9a.properties` (Pixel 9a, Finsky 45.8.21-31,
     * Vending 84582130, sdk 35). The AAR merges its res/raw into this module, so we
     * load it by name.
     *
     * Prefer this over [buildDeviceProperties]: the shared anonymous accounts the
     * dispenser hands out are minted against this standard profile, and gplayapi's
     * hardcoded `X-DFE-Encoded-Targets`/`X-DFE-Phenotype` headers are tuned for this
     * Finsky version. A runtime-built native profile (esp. on de-Googled ROMs, where
     * [GsfVersionProvider] falls back to a 2020-era Finsky 21.5.17) is inconsistent
     * with those headers and gets auth/purchase rejected — i.e. "keys not working".
     * Falls back to the native profile only if the bundled resource is missing.
     */
    fun auroraProfile(context: Context): Properties = try {
        val id = com.aurora.gplayapi.R.raw.gplayapi_px_9a
        if (id != 0) {
            context.resources.openRawResource(id).use { input -> Properties().apply { load(input) } }
        } else {
            buildDeviceProperties(context)
        }
    } catch (_: android.content.res.Resources.NotFoundException) {
        buildDeviceProperties(context)
    } catch (_: java.io.IOException) {
        buildDeviceProperties(context)
    }

    fun buildDeviceProperties(context: Context): Properties {
        val props = Properties()
        val pm = context.packageManager
        val gsf = GsfVersionProvider.get(context)

        setBuildProps(props)
        setConfigProps(context, props)
        setDisplayProps(context, props)

        // ABIs
        props.setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(","))
        setFeatureProps(pm, props)
        setLocaleProps(context, props)
        setSharedLibProps(pm, props)
        setGlProps(props)
        setGsfProps(gsf, props)

        props.setProperty("Client", "android-google")
        props.setProperty("Roaming", "mobile-notroaming")
        props.setProperty("TimeZone", "UTC-10")
        props.setProperty("CellOperator", "310")
        props.setProperty("SimOperator", "38")

        return props
    }

    private fun setBuildProps(props: Properties) {
        // Build.*
        props.setProperty("Ro.product.brand", Build.BRAND)
        props.setProperty("Ro.product.name", Build.PRODUCT)
        props.setProperty("Ro.product.device", Build.DEVICE)
        props.setProperty("Ro.product.model", Build.MODEL)
        props.setProperty("Ro.product.manufacturer", Build.MANUFACTURER)
        props.setProperty("Ro.product.id", Build.ID)
        props.setProperty("Ro.build.fingerprint", Build.FINGERPRINT)
        props.setProperty("Ro.build.bootloader", Build.BOOTLOADER)
        props.setProperty("Ro.build.hardware", Build.HARDWARE)
        props.setProperty("Ro.product.board", Build.BOARD)
        props.setProperty("Build.HARDWARE", Build.HARDWARE)
        props.setProperty("Build.BRAND", Build.BRAND)
        props.setProperty("Build.DEVICE", Build.DEVICE)
        props.setProperty("Build.FINGERPRINT", Build.FINGERPRINT)
        props.setProperty("Build.MANUFACTURER", Build.MANUFACTURER)
        props.setProperty("Build.MODEL", Build.MODEL)
        props.setProperty("Build.PRODUCT", Build.PRODUCT)
        props.setProperty("Build.ID", Build.ID)
        props.setProperty("Build.BOOTLOADER", Build.BOOTLOADER)
        props.setProperty("Build.RADIO", Build.getRadioVersion() ?: "unknown")
        props.setProperty("Build.SDK_INT", Build.VERSION.SDK_INT.toString())
        props.setProperty("Build.RELEASE", Build.VERSION.RELEASE ?: "13")
    }

    private fun setConfigProps(context: Context, props: Properties) {
        // Config
        val config = context.resources.configuration
        props.setProperty("TouchScreen", config.touchscreen.toString())
        props.setProperty("Keyboard", config.keyboard.toString())
        props.setProperty("Navigation", config.navigation.toString())
        val screenSize = (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK).toString()
        props.setProperty("ScreenLayout", screenSize)
        props.setProperty("HasHardKeyboard", (config.keyboard == Configuration.KEYBOARD_QWERTY).toString())
        val hasDpad = (config.navigation == Configuration.NAVIGATION_DPAD).toString()
        props.setProperty("HasFiveWayNavigation", hasDpad)
    }

    private fun setDisplayProps(context: Context, props: Properties) {
        // Display
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            props.setProperty("Screen.Density", metrics.densityDpi.toString())
            props.setProperty("Screen.Width", metrics.widthPixels.toString())
            props.setProperty("Screen.Height", metrics.heightPixels.toString())
        } catch (_: SecurityException) {
            setFallbackDisplayProps(props)
        } catch (_: IllegalStateException) {
            setFallbackDisplayProps(props)
        }
    }

    private fun setFallbackDisplayProps(props: Properties) {
        props.setProperty("Screen.Density", "420")
        props.setProperty("Screen.Width", "1080")
        props.setProperty("Screen.Height", "1920")
    }

    private fun setFeatureProps(pm: PackageManager, props: Properties) {
        // Features
        try {
            val features = pm.systemAvailableFeatures.mapNotNull { it.name }.joinToString(",")
            props.setProperty("Features", features)
        } catch (_: SecurityException) {
            props.setProperty("Features", "")
        }
    }

    private fun setLocaleProps(context: Context, props: Properties) {
        // Locales
        try {
            val locales = context.assets.locales?.joinToString(",") ?: Locale.getDefault().toString()
            props.setProperty("Locales", locales)
        } catch (_: java.io.IOException) {
            props.setProperty("Locales", "en_US")
        }
    }

    private fun setSharedLibProps(pm: PackageManager, props: Properties) {
        // Shared libs
        try {
            val libs = pm.systemSharedLibraryNames?.joinToString(",") ?: ""
            props.setProperty("SharedLibraries", libs)
        } catch (_: SecurityException) {
            props.setProperty("SharedLibraries", "")
        }
    }

    private fun setGlProps(props: Properties) {
        // GL - best effort
        props.setProperty("GL.Version", EglExtensionProvider.GL_VERSION)
        props.setProperty("GL.Extensions", "")
        props.setProperty("GL.EGL.Extensions", "")
    }

    private fun setGsfProps(gsf: GsfVersion, props: Properties) {
        // GSF / Vending
        props.setProperty("GSF.version", gsf.gsfVersionCode.toString())
        props.setProperty("Vending.version", gsf.vendingVersionCode.toString())
        props.setProperty("Vending.versionString", gsf.vendingVersionString)
    }

    /**
     * Convert our Properties to gplayapi's DeviceInfoProvider
     */
    fun toGplayDeviceInfoProvider(
        props: Properties,
        locale: String = Locale.getDefault().toString()
    ): com.aurora.gplayapi.data.providers.DeviceInfoProvider {
        return com.aurora.gplayapi.data.providers.DeviceInfoProvider(props, locale)
    }
}
