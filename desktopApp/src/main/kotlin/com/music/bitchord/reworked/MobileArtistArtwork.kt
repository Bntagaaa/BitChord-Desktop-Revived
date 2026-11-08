package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.*
import com.music.bitchord.data.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The mobile DetailScreen's identity-checked Apple header and optional signature provider. */
internal object MobileArtistArtworkSettings {
    val enabled = MutableStateFlow(DesktopStore.preferences.getBoolean("artist_signatures", false))
    val url = MutableStateFlow(DesktopStore.preferences.get("artist_signature_url", ""))
    fun configure(on: Boolean, address: String) {
        enabled.value = on; url.value = ArtistSignatureRepository.normalizeBaseUrl(address)
        DesktopStore.preferences.putBoolean("artist_signatures", on)
        DesktopStore.preferences.put("artist_signature_url", url.value)
        ArtistSignatureRepository.clear()
    }
}

@Composable
internal fun rememberMobileArtistArt(page: DetailPage): AppleArtistArt? {
    var art by remember(page.browseId) { mutableStateOf(AppleArtistArtRepository.cached(page.browseId)) }
    val loading = page.songs is UiState.Loading
    LaunchedEffect(page.browseId, page.title, loading) {
        if (OfflineSmoke.active) return@LaunchedEffect
        if (page.type == BrowseType.ARTIST && !loading) art = AppleArtistArtRepository.artFor(page.browseId, page.title) {
            (page.songs as? UiState.Success)?.data.orEmpty().map(Song::title) + page.sections.flatMap { shelf -> shelf.items.map(ShelfItem::title) }
        } ?: art
    }
    return art.takeIf { page.type == BrowseType.ARTIST }
}

@Composable
internal fun MobileArtistName(page: DetailPage, art: AppleArtistArt?) {
    val enabled by MobileArtistArtworkSettings.enabled.collectAsState()
    val base by MobileArtistArtworkSettings.url.collectAsState()
    var signature by remember(page.browseId) { mutableStateOf<ArtistSignature?>(null) }
    LaunchedEffect(page.browseId, page.title, enabled, base, art?.logoUrl) {
        if (OfflineSmoke.active) return@LaunchedEffect
        signature = null
        if (page.type != BrowseType.ARTIST || art?.logoUrl != null || page.songs is UiState.Loading) return@LaunchedEffect
        val effective = ArtistSignatureRepository.effectiveBaseUrl(enabled, base) ?: return@LaunchedEffect
        for (wait in listOf(0L, 5_000L, 15_000L, 30_000L)) {
            if (wait > 0) delay(wait)
            signature = ArtistSignatureRepository.cached(page.title, effective) ?: ArtistSignatureRepository.signatureFor(page.title, effective)
            if (signature != null) break
        }
    }
    if (art?.logoUrl != null) {
        AsyncImage(art.logoUrl, page.title, Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 110.dp)
            .aspectRatio(art.logoAspect.coerceAtLeast(1f)), contentScale = ContentScale.Fit)
    } else {
        Text(page.title, style = MaterialTheme.typography.headlineLarge)
        signature?.let { ink -> AsyncImage(ink.imageUrl, "${page.title} signature", Modifier.widthIn(max = 240.dp)
            .fillMaxWidth().heightIn(max = 70.dp).aspectRatio(ink.aspect.coerceAtLeast(1f)), contentScale = ContentScale.Fit) }
    }
}

@Composable
internal fun MobileArtistSettings(notice: (String) -> Unit) {
    val enabled by MobileArtistArtworkSettings.enabled.collectAsState()
    val address by MobileArtistArtworkSettings.url.collectAsState()
    var draft by remember(address) { mutableStateOf(address) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ReworkedToggle("Artist signatures", enabled) { MobileArtistArtworkSettings.configure(it, address) }
    Text("Apple Music artist photos and title logos are matched against the artist's catalogue. A signature can be shown when Apple has no logo.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(draft, { draft = it }, label = { Text("Artist signature API URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            if (draft.isBlank() || ArtistSignatureRepository.isValidBaseUrl(draft)) MobileArtistArtworkSettings.configure(enabled, draft)
            else notice("Enter an HTTP or HTTPS address")
        }) { Text("Save provider") }
        TextButton(enabled = !checking && ArtistSignatureRepository.isValidBaseUrl(draft), onClick = {
            scope.launch { checking = true; try { notice(if (ArtistSignatureRepository.checkHealth(draft)) "Signature provider is reachable" else "Could not reach the signature provider") } finally { checking = false } }
        }) { Text(if (checking) "Checking…" else "Test connection") }
    }
}
