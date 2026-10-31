package com.nuvio.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.LocalTextStyle

// Prism: side menu names stay full size; one too long to fit (like "Play Random Episode") scrolls
// from right to left so all of it can be read, instead of shrinking and being cut off.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrismTvSideLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_200, repeatDelayMillis = 1_500),
        style = if (textAlign != null) style.copy(textAlign = textAlign) else style,
        color = ColorProducer { color },
        maxLines = 1,
        softWrap = false,
    )
}
