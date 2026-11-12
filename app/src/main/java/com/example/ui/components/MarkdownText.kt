package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.JetBrainsMonoFontFamily

private val HEADING_REGEX = Regex("^(#{1,3})\\s+(.+)$")
private val BULLET_REGEX = Regex("^([-*•])\\s+(.+)$")
private val NUMBERED_REGEX = Regex("^(\\d+\\.)\\s+(.+)$")

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class BulletItem(val marker: String, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeText = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    val styledText = remember(block.text, codeBg, textColor) {
                        buildInlineAnnotatedString(block.text, codeBg, textColor)
                    }
                    Text(
                        text = styledText,
                        style = headingStyle.copy(color = textColor, fontWeight = FontWeight.Bold)
                    )
                }

                is MarkdownBlock.BulletItem -> {
                    val styledText = remember(block.text, codeBg, textColor) {
                        buildInlineAnnotatedString(block.text, codeBg, textColor)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = block.marker,
                            style = baseStyle.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = styledText,
                            style = baseStyle.copy(color = textColor),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    CodeBlockCard(
                        language = block.language,
                        code = block.code,
                        containerColor = codeBg,
                        contentColor = codeText
                    )
                }

                is MarkdownBlock.Paragraph -> {
                    val styledText = remember(block.text, codeBg, textColor) {
                        buildInlineAnnotatedString(block.text, codeBg, textColor)
                    }
                    Text(
                        text = styledText,
                        style = baseStyle.copy(color = textColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
    containerColor: Color,
    contentColor: Color
) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(bottom = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_message),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = contentColor
                ),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            )
        }
    }
}

private fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    val lines = input.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    val paragraphBuffer = StringBuilder()

    fun flushParagraph() {
        val text = paragraphBuffer.toString().trim()
        if (text.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(text))
        }
        paragraphBuffer.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        val headingMatch = HEADING_REGEX.find(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        val bulletMatch = BULLET_REGEX.find(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            blocks.add(MarkdownBlock.BulletItem("•", bulletMatch.groupValues[2]))
            i++
            continue
        }

        val numberedMatch = NUMBERED_REGEX.find(trimmed)
        if (numberedMatch != null) {
            flushParagraph()
            blocks.add(MarkdownBlock.BulletItem(numberedMatch.groupValues[1], numberedMatch.groupValues[2]))
            i++
            continue
        }

        if (paragraphBuffer.isNotEmpty()) {
            paragraphBuffer.append('\n')
        }
        paragraphBuffer.append(line)
        i++
    }
    flushParagraph()
    return blocks
}

private fun buildInlineAnnotatedString(
    text: String,
    inlineCodeBg: Color,
    defaultColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            when {
                text.startsWith("**", idx) -> {
                    val end = text.indexOf("**", idx + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor)) {
                            append(text.substring(idx + 2, end))
                        }
                        idx = end + 2
                    } else {
                        append("**")
                        idx += 2
                    }
                }

                text.startsWith("`", idx) -> {
                    val end = text.indexOf('`', idx + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                background = inlineCodeBg.copy(alpha = 0.7f),
                                color = defaultColor
                            )
                        ) {
                            append(" ${text.substring(idx + 1, end)} ")
                        }
                        idx = end + 1
                    } else {
                        append('`')
                        idx++
                    }
                }

                text.startsWith("*", idx) -> {
                    val end = text.indexOf('*', idx + 1)
                    if (end != -1 && end > idx + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor)) {
                            append(text.substring(idx + 1, end))
                        }
                        idx = end + 1
                    } else {
                        append('*')
                        idx++
                    }
                }

                else -> {
                    append(text[idx])
                    idx++
                }
            }
        }
    }
}
