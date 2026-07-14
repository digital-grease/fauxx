package com.fauxx.data.device

import android.content.Context
import com.fauxx.data.model.SyntheticPersona
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A coherent device template parsed from `device_templates.json`, with `{MAJOR}` unresolved. */
internal data class DeviceTemplate(
    val model: String,
    val platform: String,
    val platformVersion: String,
    val uaTemplate: String,
    val isMobile: Boolean,
    val brands: List<Brand>,
    val screenWidth: Int,
    val screenHeight: Int,
    val devicePixelRatio: Float,
    val hardwareConcurrency: Int,
    val deviceMemory: Int,
) {
    fun materialize(formFactor: FormFactor, chromeMajor: Int): DeviceProfile {
        val major = chromeMajor.toString()
        return DeviceProfile(
            formFactor = formFactor,
            userAgent = uaTemplate.replace(DeviceDeriver.MAJOR_TOKEN, major),
            platform = platform,
            platformVersion = platformVersion,
            model = model,
            isMobile = isMobile,
            brands = brands.map { Brand(it.name, it.version.replace(DeviceDeriver.MAJOR_TOKEN, major)) },
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            devicePixelRatio = devicePixelRatio,
            hardwareConcurrency = hardwareConcurrency,
            deviceMemory = deviceMemory,
        )
    }
}

/** The bundled device catalog: Android-Chromium [mobile] templates + desktop-Chrome [desktop] ones. */
internal data class DeviceCatalog(val mobile: List<DeviceTemplate>, val desktop: List<DeviceTemplate>) {
    companion object {
        fun parse(json: String): DeviceCatalog = Gson().fromJson(json, DeviceCatalog::class.java)
    }
}

/**
 * Derives a persona's stable device identity set (issue #242): one [FormFactor.MOBILE] device the
 * phone presents over its Android-Chromium WebView TLS, and one [FormFactor.DESKTOP] device the
 * desktop companion presents over its real desktop TLS.
 *
 * Derivation is deterministic from the persona alone — [SyntheticPersona.id] selects which template,
 * [SyntheticPersona.createdAt] fixes the Chrome major — so the desktop companion computes byte-identical
 * devices from the same synced persona WITHOUT the device crossing the LAN wire. The shared
 * `device_templates.json` (checksum-pinned) and a cross-language test vector guarantee both platforms
 * agree. See `.devloop/spikes/c2-device-identity-derivation.md`.
 */
@Singleton
class DeviceDeriver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val catalog: DeviceCatalog by lazy { loadCatalog() }

    private fun loadCatalog(): DeviceCatalog =
        try {
            DeviceCatalog.parse(context.assets.open(ASSET).bufferedReader().readText())
        } catch (e: Exception) {
            Timber.e(e, "Failed to load %s; using fallback device catalog", ASSET)
            FALLBACK
        }

    /** The persona's stable device set: exactly one MOBILE + one DESKTOP identity. */
    fun devicesFor(persona: SyntheticPersona): List<DeviceProfile> {
        val major = chromeMajor(persona.createdAt)
        return listOf(
            templateFor(persona, FormFactor.MOBILE).materialize(FormFactor.MOBILE, major),
            templateFor(persona, FormFactor.DESKTOP).materialize(FormFactor.DESKTOP, major),
        )
    }

    /** The persona's MOBILE device — what the phone's WebView presents (the only one emitted here). */
    fun mobileFor(persona: SyntheticPersona): DeviceProfile =
        templateFor(persona, FormFactor.MOBILE).materialize(FormFactor.MOBILE, chromeMajor(persona.createdAt))

    /** The persona's DESKTOP device — emitted by the companion; exposed for parity tests/UI. */
    fun desktopFor(persona: SyntheticPersona): DeviceProfile =
        templateFor(persona, FormFactor.DESKTOP).materialize(FormFactor.DESKTOP, chromeMajor(persona.createdAt))

    private fun templateFor(persona: SyntheticPersona, formFactor: FormFactor): DeviceTemplate {
        val (options, domain) = when (formFactor) {
            FormFactor.MOBILE -> catalog.mobile to DOMAIN_MOBILE
            FormFactor.DESKTOP -> catalog.desktop to DOMAIN_DESKTOP
        }
        return options[pick(persona.id, domain, 0, options.size)]
    }

    companion object {
        private const val ASSET = "device_templates.json"
        internal const val MAJOR_TOKEN = "{MAJOR}"
        internal const val DOMAIN_MOBILE = "device:mobile"
        internal const val DOMAIN_DESKTOP = "device:desktop"
        private const val SEPARATOR: Byte = 0x7C // '|'

        /**
         * Chrome-version baseline. UPDATE AT EACH APP RELEASE to the then-current Chrome stable major
         * and its release date, so synthetic traffic never claims a frozen old browser. [chromeMajor]
         * advances one major per [RELEASE_INTERVAL_MS] after [BASELINE_EPOCH_MS].
         */
        internal const val BASELINE_MAJOR = 142
        internal const val BASELINE_EPOCH_MS = 1_768_262_400_000L // 2026-01-13T00:00:00Z
        internal const val RELEASE_INTERVAL_MS = 28L * 24 * 60 * 60 * 1000

        /**
         * The Chrome major a persona claims, pinned to its creation time: stable for the persona's
         * ~7-day life, monotonic across personas (createdAt only increases), and tracking real
         * calendar time. Floors at [BASELINE_MAJOR] so a persona minted before the baseline (e.g. an
         * old synced record) never claims a version below it.
         */
        internal fun chromeMajor(createdAtMs: Long): Int {
            val elapsed = createdAtMs - BASELINE_EPOCH_MS
            if (elapsed <= 0) return BASELINE_MAJOR
            return BASELINE_MAJOR + (elapsed / RELEASE_INTERVAL_MS).toInt()
        }

        /**
         * Deterministic index into [size] options, from SHA-256 over `id | domain | index`. Portable
         * across languages (only a shared hash + canonical bytes, no PRNG, no floats): the desktop
         * companion computes the same value from the same persona.
         */
        internal fun pick(id: String, domain: String, index: Int, size: Int): Int {
            require(size > 0) { "options must be non-empty" }
            val md = MessageDigest.getInstance("SHA-256")
            md.update(id.toByteArray(Charsets.UTF_8))
            md.update(SEPARATOR)
            md.update(domain.toByteArray(Charsets.UTF_8))
            md.update(SEPARATOR)
            md.update(index.toString().toByteArray(Charsets.UTF_8))
            val h = md.digest()
            val n = ((h[0].toLong() and 0xFF) shl 24) or
                ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or
                (h[3].toLong() and 0xFF)
            return (n % size).toInt()
        }

        /** Minimal catalog used only if the bundled asset fails to load (keeps derivation total). */
        private val FALLBACK = DeviceCatalog(
            mobile = listOf(
                DeviceTemplate(
                    model = "Pixel 8", platform = "Android", platformVersion = "14.0.0",
                    uaTemplate = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/$MAJOR_TOKEN.0.0.0 Mobile Safari/537.36",
                    isMobile = true,
                    brands = listOf(Brand("Chromium", MAJOR_TOKEN), Brand("Google Chrome", MAJOR_TOKEN), Brand("Not?A_Brand", "24")),
                    screenWidth = 412, screenHeight = 915, devicePixelRatio = 2.625f,
                    hardwareConcurrency = 8, deviceMemory = 8,
                ),
            ),
            desktop = listOf(
                DeviceTemplate(
                    model = "", platform = "Windows", platformVersion = "15.0.0",
                    uaTemplate = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/$MAJOR_TOKEN.0.0.0 Safari/537.36",
                    isMobile = false,
                    brands = listOf(Brand("Chromium", MAJOR_TOKEN), Brand("Google Chrome", MAJOR_TOKEN), Brand("Not?A_Brand", "24")),
                    screenWidth = 1920, screenHeight = 1080, devicePixelRatio = 1.0f,
                    hardwareConcurrency = 8, deviceMemory = 16,
                ),
            ),
        )
    }
}
