package com.agent.mobile.ui.chat.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.ui.theme.*

/** Inline spans of [text] as styled text; links open in the browser. */
fun markdownInline(text: String): AnnotatedString = buildAnnotatedString {
    for (span in Markdown.inline(text)) {
        when (span) {
            is MdSpan.Text -> withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null
                )
            ) { append(span.text) }
            is MdSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = DarkCardElevated, fontSize = 14.sp)) {
                append(span.text)
            }
            is MdSpan.Link -> withLink(
                LinkAnnotation.Url(
                    span.url,
                    TextLinkStyles(SpanStyle(color = AccentSecondary, textDecoration = TextDecoration.Underline))
                )
            ) { append(span.label) }
        }
    }
}

/**
 * A chat reply rendered from Markdown: headings, lists, quotes, rules and code blocks with their
 * own copy button. Not a selection container, so the long-press menu of the message (copy, read
 * aloud, …) keeps working.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    markdownInline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = TextWhite
                )
                is MdBlock.Paragraph -> Text(markdownInline(block.text), style = style, color = TextWhite)
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row {
                            Text(
                                if (block.ordered) "${block.start + i}." else "•",
                                style = style,
                                color = TextSecondary,
                                modifier = Modifier.widthIn(min = 22.dp)
                            )
                            Text(markdownInline(item), style = style, color = TextWhite)
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(BorderLight, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(markdownInline(block.text), style = style, color = TextSecondary)
                }
                is MdBlock.Code -> CodeBlock(block)
                MdBlock.Rule -> HorizontalDivider(color = BorderSubtle)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AmcShapes.medium)
            .background(TerminalBg)
    ) {
        Row(
            Modifier.fillMaxWidth().background(TerminalBar).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                block.language.ifBlank { "code" },
                style = MaterialTheme.typography.labelMedium,
                color = TerminalDim,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code", block.code))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy code", tint = TerminalDim, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            block.code,
            style = CodeStyle,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
        )
    }
}
