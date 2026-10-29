package com.aminopy.roohvpn.vpn

import android.net.VpnService

/**
 * Lifecycle boundary for the eventual tunnel implementation.
 *
 * Important: this shell intentionally does not call Builder.establish() and
 * does not claim to protect traffic. Establishing a TUN without a packet
 * forwarder would blackhole traffic. The real engine-to-TUN path must be
 * integrated and tested before connection actions are exposed.
 */
class RoohVpnService : VpnService() {
    override fun onRevoke() {
        // Future backend implementations must stop native processes, close
        // descriptors, and clear their own routes/resources before returning.
        super.onRevoke()
        stopSelf()
    }
}
