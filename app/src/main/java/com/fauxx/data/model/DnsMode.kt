package com.fauxx.data.model

/**
 * Which resolver Fauxx's own synthetic traffic uses (#227). [SYSTEM] is whatever the device
 * provides; [DOH] routes the WebView through Fauxx's loopback proxy, which resolves names over
 * DNS-over-HTTPS. Plain-DNS servers are planned as a third mode.
 */
enum class DnsMode { SYSTEM, DOH }
