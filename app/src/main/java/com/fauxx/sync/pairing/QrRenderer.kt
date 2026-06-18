package com.fauxx.sync.pairing

import android.graphics.Bitmap
import com.fauxx.sync.wire.PairingPayload
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder

/**
 * Renders a [PairingPayload] to a QR [Bitmap], fully offline (ZXing). Uses medium error correction
 * to match the desktop (`EcLevel::M`). The fingerprint is shown separately under the QR so a user
 * can eyeball it against the discovered peer.
 */
object QrRenderer {

    fun render(payload: PairingPayload, sizePx: Int = 640): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        return BarcodeEncoder().encodeBitmap(payload.encode(), BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }
}
