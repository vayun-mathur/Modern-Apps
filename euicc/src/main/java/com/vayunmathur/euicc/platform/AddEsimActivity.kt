package com.vayunmathur.euicc.platform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.euicc.Navigation
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.ui.looksLikeActivationCode
import com.vayunmathur.library.intents.euicc.EsimLink
import com.vayunmathur.library.ui.DynamicTheme

/**
 * Permission-free handler for the standard eSIM universal link:
 * `https://esimsetup.android.com/esim_qrcode_provisioning?carddata=<activation-code>`.
 *
 * This is the platform-standard handoff (the same URL Google's own LPA flow uses):
 * a scanner builds the link from the QR payload and fires `ACTION_VIEW`, and the
 * LPA whose manifest declares the host+path receives it instead of a browser.
 *
 * This exists alongside [LuiActivity] because that entry point cannot serve here:
 * its `BIND_EUICC_SERVICE` gate constrains the *caller*, and a third-party app
 * holds no signature permission — the platform itself is the only caller that can
 * reach it. This activity carries no gate, so anyone holding the scanned code can
 * open it.
 *
 * A valid `carddata` lands straight on the download flow ([Route.Download]); a
 * missing or malformed one lands on [Route.AddSim] so the user can type the code
 * in by hand instead of staring at Home with no path forward.
 */
class AddEsimActivity : ComponentActivity() {
    private val viewModel: EuiccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Universal link (`...?carddata=<code>`) or raw `lpa:<code>` deep link.
        // An `lpa:` URI is opaque, and getQueryParameter throws on opaque URIs,
        // so the scheme decides which accessor is safe before either is called.
        val data = intent?.data
        val code = if (data?.scheme.equals("lpa", ignoreCase = true)) {
            data?.schemeSpecificPart?.trim().orEmpty()
        } else {
            data?.getQueryParameter(EsimLink.CARDDATA_PARAM)?.trim().orEmpty()
        }
        val start: Route =
            if (looksLikeActivationCode(code)) Route.Download(code) else Route.AddSim
        setContent { DynamicTheme { Navigation(viewModel, start) } }
    }
}
