package com.fauxx.sync.wire

/**
 * Frozen cross-device sync protocol constants (E13 #178), verified byte-for-byte against the
 * desktop reference in `fauxx-desktop/crates/fauxx-core/src/sync/`. Get any of these wrong and
 * interop breaks silently, so they live in exactly one place and are never duplicated inline.
 *
 * See `E13_SYNC_PROTOCOL.md` for the normative contract.
 */
object SyncConstants {

    /** The single protocol version both sides carry (mDNS TXT `v`, QR `v`, and the sealed frame). */
    const val PROTOCOL_VERSION: Int = 1

    // --- mDNS / DNS-SD ---

    /**
     * The fully-qualified DNS-SD service type the wire, TXT, and QR layers use, and what the
     * desktop advertises. Android's [android.net.nsd.NsdManager] wants the type WITHOUT the
     * trailing `.local.` ([NSD_SERVICE_TYPE]); both forms are the same service. Centralized here
     * so the two string conventions cannot drift.
     */
    const val SERVICE_TYPE_FQ: String = "_fauxx-sync._tcp.local."

    /** The NsdManager-flavored service type (no trailing `.local.`). */
    const val NSD_SERVICE_TYPE: String = "_fauxx-sync._tcp"

    /** mDNS TXT key carrying the protocol version as a decimal string. */
    const val TXT_KEY_VERSION: String = "v"

    /** mDNS TXT key carrying the public-key fingerprint. */
    const val TXT_KEY_FINGERPRINT: String = "fp"

    /** mDNS TXT key carrying the full base64url (no padding) public key. */
    const val TXT_KEY_PUBKEY: String = "pk"

    /** The default TCP port the sync transport advertises and listens on. */
    const val DEFAULT_SYNC_PORT: Int = 45999

    // --- Sealed frame + crypto sizes (must match the desktop byte layout exactly) ---

    /** 4-byte ASCII magic marking a Fauxx sealed sync frame. */
    const val FRAME_MAGIC: String = "FXS1"

    /** Length, in bytes, of an X25519 public key. */
    const val PUBLIC_KEY_LEN: Int = 32

    /** Length, in bytes, of an X25519 secret key. */
    const val SECRET_KEY_LEN: Int = 32

    /** Length, in bytes, of a crypto_box nonce. */
    const val NONCE_LEN: Int = 24

    /** Length, in bytes, of the Poly1305 authentication tag. */
    const val MAC_LEN: Int = 16

    /** Sealed-frame header length: magic(4) + version(2) + nonce(24) = 30. */
    const val FRAME_HEADER_LEN: Int = 4 + 2 + NONCE_LEN

    /** Minimum valid sealed-frame length: header(30) + MAC(16) = 46. */
    const val MIN_FRAME_LEN: Int = FRAME_HEADER_LEN + MAC_LEN

    /** Defensive cap on an inbound sealed frame (1 MiB). Larger frames are rejected unallocated. */
    const val MAX_FRAME_LEN: Int = 1 shl 20
}
