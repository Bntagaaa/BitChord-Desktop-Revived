package com.music.bitchord.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.bintang_music_black_about
import org.jetbrains.compose.resources.Font

/** The supplied About wordmark face applies to every release and test version. */
@Composable
internal fun DesktopAboutVersionTitle(version: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = "BitChord $version",
        style = MaterialTheme.typography.headlineLarge.copy(
            fontFamily = FontFamily(Font(Res.font.bintang_music_black_about, FontWeight.Black)),
            fontSynthesis = FontSynthesis.None,
            fontSize = 32.sp,
            letterSpacing = 0.sp,
        ),
        fontWeight = FontWeight.Black,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier.fillMaxWidth(),
    )
}
