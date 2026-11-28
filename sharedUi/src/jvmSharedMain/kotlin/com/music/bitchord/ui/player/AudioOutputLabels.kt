package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Keep the names of external audio routes; use a platform-specific label for
 * the local/default route. On desktop PHONE represents the OS-managed default,
 * not a physical phone, and its picker name ("System default") is not the
 * personal output caption.
 */
@Composable
internal fun outputLabel(
    device: AudioOutputDevice?,
    accountName: String?,
    desktop: Boolean = PlayerPlatform.host.desktopOutputLabels,
): String {
    val firstName = accountName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }
    val name = device?.name.orEmpty()
    val localDesktop = desktop && device?.kind == AudioOutputKind.PHONE
    return when {
        !localDesktop && name.isNotBlank() -> name
        device?.kind == AudioOutputKind.WIRED -> stringResource(Res.string.wired_headphones)
        device?.kind == AudioOutputKind.USB -> stringResource(Res.string.usb_audio)
        device?.kind == AudioOutputKind.HDMI -> stringResource(Res.string.hdmi_output)
        firstName != null -> stringResource(
            if (desktop) Res.string.personal_desktop else Res.string.personal_phone,
            firstName,
        )
        else -> stringResource(if (desktop) Res.string.this_desktop else Res.string.this_phone)
    }
}

/** The active route's name for the caption under the transport. */
@Composable
internal fun rememberAudioOutputName(accountName: String?): String {
    val active = rememberAudioOutputs().firstOrNull { it.isActive }
    return outputLabel(active, accountName)
}
