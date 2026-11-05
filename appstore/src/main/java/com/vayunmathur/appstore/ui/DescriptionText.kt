package com.vayunmathur.appstore.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import com.vayunmathur.appstore.domain.DescriptionMarkup

@Composable
internal fun rememberDescriptionText(raw: String, linkColor: Color): AnnotatedString =
    remember(raw, linkColor) {
        AnnotatedString.fromHtml(
            DescriptionMarkup.toHtml(raw),
            linkStyles = TextLinkStyles(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ),
        )
    }
