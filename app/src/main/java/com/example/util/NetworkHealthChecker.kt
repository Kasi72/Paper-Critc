package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Lightweight, zero-dangerous-permission connectivity pre-flight check using the
 * already-declared install-time ACCESS_NETWORK_STATE permission.
 * Prevents burning 30–60s waiting on remote LLM timeouts when the device is offline.
 */
object NetworkHealthChecker {

  fun isInternetReachable(context: Context): Boolean {
    return runCatching {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
      val activeNetwork = cm.activeNetwork ?: return false
      val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        (
          capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
          )
    }.getOrDefault(true)
  }
}
