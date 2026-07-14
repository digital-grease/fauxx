package com.fauxx.sync.pairing

import com.journeyapps.barcodescanner.ScanOptions

/**
 * Builds the ZXing [ScanOptions] for pairing scans, locked to QR codes with the beep disabled.
 * Used with `rememberLauncherForActivityResult(ScanContract())` in the Sync screen. The camera
 * permission is requested at the moment the user taps "Scan to pair"; a manual-paste fallback
 * exists so a denial is never a dead end.
 */
object QrScanOptionsFactory {

    fun pairingScan(prompt: String): ScanOptions = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setBeepEnabled(false)
        .setOrientationLocked(false)
        .setPrompt(prompt)
}
