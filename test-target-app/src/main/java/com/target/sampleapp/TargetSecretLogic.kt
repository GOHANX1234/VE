package com.target.sampleapp

object TargetSecretLogic {
    fun calculateSecretHash(input: String): String {
        return "GUEST_COMPUTED[" + input.reversed() + "_VERIFIED_OK]"
    }
}
