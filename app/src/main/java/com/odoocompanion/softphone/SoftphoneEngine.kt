package com.odoocompanion.softphone

/** How the SIP stack is set up beyond what its API exposes. */
object SoftphoneEngine {
    /**
     * Read when the core is created, so it is the core's starting configuration:
     * - net: Data Saver makes a metered network unusable to an app in the
     *   background, and the stack then reports no network at all, so a handset
     *   on its eSIM stopped registering whenever the screen was off. The data
     *   it needs is the registration and the calls the user takes.
     * - sound: Telecom routes a self-managed call's audio (earpiece, speaker,
     *   Bluetooth, car); the stack moving the route itself fought it.
     * - rtp: RTP and RTCP on one port, as the phone system's WebRTC endpoint
     *   (webrtc=yes) offers and expects.
     */
    const val CONFIG = """
[net]
android_ignore_network_background_restriction=1

[sound]
android_disable_audio_route_changes=1

[rtp]
rtcp_mux=1
"""
}
