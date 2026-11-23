package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisSourcePolicyTest {

    private fun input(
        qr: Boolean = false,
        bitmap: Boolean = false,
        cheapOnly: Boolean = true,
    ) = AnalysisSourceInput(
        needsQr = qr, needsBitmapFrame = bitmap,
        cheapPlaneConsumersOnly = cheapOnly,
    )

    @Test fun cheapPlaneSessionUsesImageReader() {
        assertEquals(AnalysisSource.CAMERA2_IMAGE_READER, AnalysisSourcePolicy.select(input()))
    }

    @Test fun qrUsesImageReader() {
        // QR decodes from the Y plane in place on the only analysis source.
        assertEquals(AnalysisSource.CAMERA2_IMAGE_READER, AnalysisSourcePolicy.select(input(qr = true)))
    }

    @Test fun bitmapConsumersUseImageReader() {
        assertEquals(
            AnalysisSource.CAMERA2_IMAGE_READER,
            AnalysisSourcePolicy.select(input(bitmap = true)),
        )
    }

    @Test fun mixedConsumersUseImageReader() {
        assertEquals(
            AnalysisSource.CAMERA2_IMAGE_READER,
            AnalysisSourcePolicy.select(input(cheapOnly = false)),
        )
    }

    @Test fun everyCombinationUsesTheOnlySource() {
        for (qr in listOf(false, true)) {
            for (bitmap in listOf(false, true)) {
                for (cheap in listOf(false, true)) {
                    assertEquals(
                        AnalysisSource.CAMERA2_IMAGE_READER,
                        AnalysisSourcePolicy.select(input(qr, bitmap, cheap)),
                        "qr=$qr bitmap=$bitmap cheap=$cheap",
                    )
                }
            }
        }
        // The enum has exactly one source left.
        assertEquals(listOf(AnalysisSource.CAMERA2_IMAGE_READER), AnalysisSource.entries)
    }
}
