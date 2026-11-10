package com.odoocompanion.softphone

object SoftphoneEngine {
    const val CONFIG = """
[net]
android_ignore_network_background_restriction=1

[sound]
android_disable_audio_route_changes=1

[rtp]
rtcp_mux=1
"""
}
