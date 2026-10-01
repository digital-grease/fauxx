package com.fauxx.data.model

/**
 * Which resolver Fauxx's own synthetic traffic uses (#227). [SYSTEM] is whatever the device
 * provides. [DOH] and [PLAIN] route the WebView through Fauxx's loopback proxy, which resolves
 * names over DNS-over-HTTPS or over plain DNS to a server the user names by IP.
 */
enum class DnsMode { SYSTEM, DOH, PLAIN }
