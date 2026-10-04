package com.fauxx.network.dns

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.X509TrustManager

/**
 * Trust on first use, for a custom DoH server whose certificate the system cannot verify (#227):
 * typically a self-hosted resolver with a self-signed certificate.
 *
 * The first certificate the server presents is trusted and its public key remembered ([onPinned],
 * which the router persists). From then on only that key is accepted. So a home resolver keeps
 * working, while an impostor on another network (a café's 192.168.x.x, or whatever a hostile
 * network's DNS says the server's name is) is refused: the lookup fails, the fail-open breaker
 * falls back to the device's DNS, and [onChecked] tells the dashboard why.
 *
 * Replacing the server's key therefore looks exactly like an impostor. The user re-trusts it by
 * switching "Skip certificate check" off and on, which clears the pin; the dashboard notice and
 * the setting's description both say so.
 *
 * Hostnames are not checked: a self-signed certificate rarely names what the URL says, and the
 * pinned key is the server's identity here.
 */
@SuppressLint("CustomX509TrustManager")
class CertificatePin(
    pinned: String?,
    private val onPinned: (String) -> Unit,
    /** True when the pinned key was presented, false when another one was (and refused). */
    private val onChecked: (matched: Boolean) -> Unit,
) : X509TrustManager {

    private val pin = AtomicReference(pinned?.takeIf { it.isNotEmpty() })

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The DNS server sent no certificate")
        val key = fingerprint(leaf)
        // compareAndSet: parallel first handshakes (A and AAAA) must agree on one key.
        if (pin.compareAndSet(null, key)) {
            onPinned(key)
            return
        }
        val matched = pin.get() == key
        onChecked(matched)
        if (!matched) throw CertificateException("The DNS server's certificate is not the one first trusted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** SHA-256 of the certificate's public key (SPKI), base64: what HPKP-style pins name. */
        fun fingerprint(certificate: X509Certificate): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))
    }
}
