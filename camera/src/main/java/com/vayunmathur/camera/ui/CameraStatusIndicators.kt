package com.vayunmathur.camera.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.domain.isFidoDeepLink
import com.vayunmathur.library.ui.Text

@Composable
internal fun BoxScope.CameraStatusIndicators(
    qrResult: String?,
    onDismissQr: () -> Unit,
    context: Context,
    qrBottomPadding: Dp,
    isRecording: Boolean,
    recordingDuration: Long,
    indicatorTopPadding: Dp,
    burstActive: Boolean,
    burstCount: Int,
    focusLocked: Boolean,
    focusTopPadding: Dp,
) {
    // FIDO deep links and eSIM codes render in CameraSettingsColumn directly
    // above the zoom bar — never in the generic QR popup here.
    qrResult?.takeUnless { isFidoDeepLink(it) || isEsimActivationCode(it) }?.let { qr ->
        QrResultOverlay(
            text = qr,
            onDismiss = onDismissQr,
            context = context,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = qrBottomPadding)
        )
    }

    if (isRecording) {
        RecordingIndicator(
            durationSec = recordingDuration,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = indicatorTopPadding)
        )
    }

    if (burstActive) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = indicatorTopPadding)
                .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.burst_count, burstCount),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (focusLocked && !isRecording) {
        Text(
            text = stringResource(R.string.ae_af_lock),
            color = Color(0xFFFFD54F),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = focusTopPadding)
                .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
