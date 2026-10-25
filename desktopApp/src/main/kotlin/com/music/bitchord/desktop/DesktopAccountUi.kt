package com.music.bitchord.desktop

import com.music.bitchord.ui.screens.desktopTopScrollFade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The account button in the toolbar: the listener's photo, or a person glyph when there is nobody
 * signed in.
 */
@Composable
internal fun DesktopAccountButton(
    avatar: String?,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            DesktopArtwork(
                avatar,
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(1.dp, DesktopForeground.copy(alpha = 0.18f), CircleShape),
                contentScale = ContentScale.Crop,
                px = 96,
            )
        } else {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(DesktopForeground.copy(alpha = 0.14f))
                    .border(1.dp, DesktopForeground.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    DesktopStrings["accounts", "Accounts"],
                    tint = DesktopForeground.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * A compact, web-style account menu anchored above the sidebar account button.
 * It uses the shared menu glass, so the Menus & popups preference and Reduce Blur stay authoritative.
 */
@Composable
internal fun DesktopAccountSelector(
    accounts: List<DesktopGoogleAccount>,
    activeAccountId: String?,
    activeProfileId: String?,
    busy: Boolean,
    onSelect: (DesktopGoogleAccount, DesktopYouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (DesktopGoogleAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    DesktopRegisterFlyout(DesktopFlyoutLayer.ACCOUNT, onDismiss = onDismiss)
    var managing by remember { mutableStateOf(false) }
    val accountsListState = rememberLazyListState()
    val activeAccount = accounts.firstOrNull { it.accountId == activeAccountId } ?: accounts.firstOrNull()
    val heading = activeAccount?.name?.takeIf(String::isNotBlank)
        ?: activeAccount?.email?.takeIf(String::isNotBlank)
        ?: "Accounts"

    Box(Modifier.fillMaxSize()) {
        // Keep outside-click dismissal without dimming the page behind the menu.
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 64.dp)
                .widthIn(max = 292.dp)
                .fillMaxWidth()
                .clip(shape)
                .desktopMenuGlass(shape, tintAlpha = 0.82f),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DesktopForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        color = DesktopAccent,
                        modifier = Modifier.padding(start = 8.dp).size(15.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            LazyColumn(
                state = accountsListState,
                modifier = Modifier.heightIn(max = 360.dp).desktopTopScrollFade(accountsListState),
            ) {
                if (accounts.isEmpty()) {
                    item {
                        Text(
                            DesktopStrings[
                                "d_nobody_is_signed_in",
                                "Nobody is signed in. Your library, playlists and history need a " +
                                    "YouTube Music account.",
                            ],
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                accounts.forEach { account ->
                    if (accounts.size > 1 || account.profiles.size > 1) {
                        item(key = "header-${account.accountId}") {
                            Text(
                                account.email.ifBlank { account.name.ifBlank { "Account" } },
                                style = MaterialTheme.typography.labelSmall,
                                color = DesktopSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 3.dp),
                            )
                        }
                    }
                    items(account.profiles.size, key = { "p-${account.accountId}-${it}" }) { index ->
                        val profile = account.profiles[index]
                        DesktopProfileRow(
                            profile = profile,
                            selected = account.accountId == activeAccountId &&
                                profile.profileId == activeProfileId,
                            managing = managing,
                            onClick = { onSelect(account, profile); onDismiss() },
                            onRemove = { onRemoveAccount(account) },
                        )
                    }
                }
                item {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = DesktopCardEdge,
                    )
                }
                item { DesktopSelectorAction(BitChordIcons.Plus, "Add account", onAddAccount) }
                if (accounts.isNotEmpty()) {
                    item {
                        DesktopSelectorAction(Icons.Rounded.ManageAccounts, "Manage accounts") {
                            managing = !managing
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }
    }
}

@Composable
private fun DesktopProfileRow(
    profile: DesktopYouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected && !managing) DesktopForeground.copy(alpha = 0.075f) else Color.Transparent)
            .clickable(onClick = if (managing) onRemove else onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile.avatar != null) {
            DesktopArtwork(
                profile.avatar,
                Modifier.size(32.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
                px = 128,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                null,
                tint = DesktopSecondary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                color = DesktopForeground,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                profile.handle.ifBlank { if (profile.isBrandAccount) "Brand account" else "Personal" },
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            managing -> Text(
                DesktopStrings["sign_out", "Sign out"],
                color = DesktopDestructive,
                style = MaterialTheme.typography.labelSmall,
            )
            selected -> Icon(
                BitChordIcons.Check,
                DesktopStrings["selected", "Selected"],
                tint = DesktopForeground.copy(alpha = 0.92f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DesktopSelectorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = DesktopForeground.copy(alpha = 0.88f), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = DesktopForeground,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Signing in: in-app JavaFX WebView first; secure browser and imported sessions as fallbacks. */
@Composable
internal fun DesktopSignInDialog(
    interactiveBrowser: DesktopBrowserSignIn.Browser?,
    webSignInAvailable: Boolean,
    onWebSignIn: () -> Unit,
    onBrowserSignIn: (DesktopBrowserSignIn.Browser) -> Unit,
    onImport: (DesktopBrowserCookies.Profile) -> Unit,
    onPaste: (String) -> Unit,
    busy: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    // A sign-in prompt is above the account picker. Keep its current dismiss/cancel behavior,
    // but never let Escape reach a player or account underneath it.
    DesktopRegisterFlyout(DesktopFlyoutLayer.DIALOG)
    var profiles by remember { mutableStateOf<List<DesktopBrowserCookies.Profile>?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val browserListState = rememberLazyListState()
    LaunchedEffect(Unit) {
        profiles = withContext(Dispatchers.IO) {
            DesktopBrowserCookies.profiles().filterNot {
                DesktopPlatform.isWindows && it.family == DesktopBrowserCookies.Family.CHROMIUM
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(shape)
                .desktopMenuGlass(shape)
                .padding(bottom = 8.dp),
        ) {
            Text(
                DesktopStrings["sign_in_youtube_music", "Sign in to YouTube Music"],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DesktopForeground,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
            )
            Text(
                if (webSignInAvailable) {
                    "Sign in right from BitChord. If Google does not allow this embedded browser, " +
                        "use the secure Chrome/Edge option or an existing browser profile below."
                } else if (interactiveBrowser != null) {
                    "Sign in using ${interactiveBrowser.label}, then close its browser window " +
                        "after YouTube Music opens to return to BitChord."
                } else {
                    "Choose a signed-in browser profile to connect to YouTube Music."
                },
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp),
            )
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopAccent,
                    modifier = Modifier.padding(horizontal = 22.dp).padding(bottom = 8.dp),
                )
            }
            when {
                pasting -> {
                    Text(
                        DesktopStrings["d_paste_the_cookie_header_for_music_youtube_com", "Paste the Cookie header for music.youtube.com."],
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                    BasicTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = DesktopForeground),
                        cursorBrush = SolidColor(DesktopAccent),
                        modifier = Modifier
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .heightIn(min = 84.dp, max = 160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DesktopForeground.copy(alpha = 0.08f))
                            .padding(12.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { pasting = false }) { Text(DesktopStrings["back", "Back"], color = DesktopSecondary) }
                        TextButton(
                            onClick = { onPaste(pasted.trim()) },
                            enabled = pasted.isNotBlank() && busy == null,
                        ) {
                            Text(DesktopStrings["sign_in", "Sign in"], color = DesktopAccent)
                        }
                    }
                }
                profiles == null -> Row(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = DesktopAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(DesktopStrings["d_looking_for_browsers", "Looking for browsers…"], color = DesktopSecondary)
                }
                else -> LazyColumn(
                    state = browserListState,
                    modifier = Modifier.heightIn(max = 320.dp).desktopTopScrollFade(browserListState),
                ) {
                    val found = profiles.orEmpty()
                    if (webSignInAvailable) {
                        item(key = "in-app-webview") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 68.dp)
                                    .clickable(enabled = busy == null, onClick = onWebSignIn)
                                    .padding(horizontal = 22.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Sign in inside BitChord", color = DesktopForeground)
                                    Text(
                                        if (busy == "In-app YouTube Music") {
                                            "Complete sign-in in the BitChord window, then choose Use this profile"
                                        } else {
                                            "Embedded YouTube Music sign-in · choose your Google profile"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DesktopSecondary,
                                    )
                                }
                                if (busy == "In-app YouTube Music") {
                                    CircularProgressIndicator(
                                        color = DesktopAccent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                    interactiveBrowser?.let { browser ->
                        item(key = "interactive-browser") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 62.dp)
                                    .clickable(enabled = busy == null) { onBrowserSignIn(browser) }
                                    .padding(horizontal = 22.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(browser.label, color = DesktopForeground)
                                    Text(
                                        if (busy == browser.label) {
                                            "Finish signing in, then close Chrome"
                                        } else {
                                            "Opens normal Chrome; close it when signed in"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DesktopSecondary,
                                    )
                                }
                                if (busy == browser.label) {
                                    CircularProgressIndicator(color = DesktopAccent, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    if (found.isEmpty() && interactiveBrowser == null) {
                        item {
                            Text(
                                DesktopStrings["d_no_browser_profile_was_found_on_this_machine", "No browser profile was found on this machine."],
                                style = MaterialTheme.typography.bodySmall,
                                color = DesktopSecondary,
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(found.size, key = { found[it].database.toString() }) { index ->
                        val profile = found[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(enabled = busy == null) { onImport(profile) }
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(profile.label, color = DesktopForeground, modifier = Modifier.weight(1f))
                            if (busy == profile.label) {
                                CircularProgressIndicator(color = DesktopAccent, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable { pasting = true }
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.ContentPaste,
                                null,
                                tint = DesktopForeground.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(DesktopStrings["d_paste_a_cookie_instead", "Paste a cookie instead"], color = DesktopForeground)
                        }
                    }
                }
            }
        }
    }
}
