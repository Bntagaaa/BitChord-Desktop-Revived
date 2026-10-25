package com.music.bitchord.desktop

/** CPU and RAM are conservative resource hints, never a claim about GPU performance. */
internal enum class DesktopGraphicsProfile { FULL, BALANCED, PERFORMANCE }

internal fun initialGraphicsProfile(existingPreferences: Boolean, cores: Int, memoryBytes: Long?): DesktopGraphicsProfile {
    if (existingPreferences) return DesktopGraphicsProfile.FULL
    if (cores in 1..2 || (memoryBytes != null && memoryBytes in 1..(4L * 1024 * 1024 * 1024))) {
        return DesktopGraphicsProfile.PERFORMANCE
    }
    return DesktopGraphicsProfile.BALANCED // Unknown GPU: avoid claiming FULL is appropriate.
}

internal fun profileAllowsBlur(profile: DesktopGraphicsProfile, area: DesktopBlurArea): Boolean = when (profile) {
    DesktopGraphicsProfile.FULL -> true
    DesktopGraphicsProfile.BALANCED -> area != DesktopBlurArea.APP_CHROME
    DesktopGraphicsProfile.PERFORMANCE -> false
}

internal fun effectiveDesktopBlur(reduced: Boolean, selected: Boolean, profile: DesktopGraphicsProfile, area: DesktopBlurArea) =
    effectiveDesktopBlur(reduced, selected) && profileAllowsBlur(profile, area)

internal fun profileBlurScale(profile: DesktopGraphicsProfile): Float = when (profile) {
    DesktopGraphicsProfile.FULL -> 1f
    DesktopGraphicsProfile.BALANCED -> 0.65f
    DesktopGraphicsProfile.PERFORMANCE -> 0f
}
