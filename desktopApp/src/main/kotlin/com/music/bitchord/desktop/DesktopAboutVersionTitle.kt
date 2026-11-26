package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.bintang_music_black_about
import bitchord.desktopapp.generated.resources.bitchord_logo_original
import bitchord.desktopapp.generated.resources.logo
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun DesktopAboutCard(onLicenses: () -> Unit) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = DesktopForeground, textDecoration = TextDecoration.Underline),
    )
    Row(
        modifier = Modifier.fillMaxWidth().settingsCard().heightIn(min = 100.dp)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(if (DesktopTheme.isLight) Res.drawable.logo else Res.drawable.bitchord_logo_original),
            contentDescription = null,
            modifier = Modifier.size(width = 58.dp, height = 40.dp),
        )
        Column(Modifier.weight(1f)) {
            DesktopAboutVersionTitle(
                version = DesktopUpdateChecker.currentVersion,
                color = DesktopForeground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/BitChord", linkStyles)) {
                        append("GitHub")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) {
                        append("Developer")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Url("https://discord.gg/pDdKfrdHY6", linkStyles)) {
                        append("Discord")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Clickable("licenses", linkStyles) { onLicenses() }) {
                        append("Licenses")
                    }
                    append("  ~YouTube Music Backend")
                },
                style = MaterialTheme.typography.labelSmall,
                color = DesktopSecondary,
            )
        }
    }
}

private val releaseVersion = Regex("""^v?(\d+(?:\.\d+)+)(?=$|[-+])""")

@Composable
internal fun DesktopAboutVersionTitle(version: String, color: Color, modifier: Modifier = Modifier) {
    val displayVersion = releaseVersion.find(version)?.groupValues?.get(1) ?: version
    Text(
        text = "BitChord Desktop $displayVersion",
        style = MaterialTheme.typography.headlineLarge.copy(
            fontFamily = FontFamily(Font(Res.font.bintang_music_black_about, FontWeight.Black)),
            fontSynthesis = FontSynthesis.None,
            fontSize = 32.sp,
            letterSpacing = 0.sp,
        ),
        fontWeight = FontWeight.Black,
        color = color,
        modifier = modifier.fillMaxWidth(),
    )
}
