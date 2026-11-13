package com.omni.aiassistant.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.omni.aiassistant.R

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
}

@Composable
fun MarkdownMessage(text: String, modifier: Modifier = Modifier) {
    val blocks = parseBlocks(text)
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text))
                    is MarkdownBlock.Code -> CodeBlock(block.language, block.code)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    val context = LocalContext.current
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(language?.ifBlank { "code" } ?: "code", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_code)) }
            }
            Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun parseBlocks(text: String): List<MarkdownBlock> {
    if (!text.contains("```")) return listOf(MarkdownBlock.Paragraph(text))
    val out = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < text.length) {
        val start = text.indexOf("```", index)
        if (start < 0) {
            if (index < text.length) out += MarkdownBlock.Paragraph(text.substring(index))
            break
        }
        if (start > index) out += MarkdownBlock.Paragraph(text.substring(index, start))
        val lineEnd = text.indexOf('\n', start + 3)
        if (lineEnd < 0) {
            out += MarkdownBlock.Paragraph(text.substring(start))
            break
        }
        val language = text.substring(start + 3, lineEnd).trim().takeIf { it.isNotEmpty() }
        val end = text.indexOf("```", lineEnd + 1)
        if (end < 0) {
            out += MarkdownBlock.Code(language, text.substring(lineEnd + 1))
            break
        }
        out += MarkdownBlock.Code(language, text.substring(lineEnd + 1, end).trimEnd())
        index = end + 3
    }
    return out.ifEmpty { listOf(MarkdownBlock.Paragraph(text)) }
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val regex = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    regex.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removePrefix("**").removeSuffix("**")) }
            token.startsWith('`') -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(token.removePrefix("`").removeSuffix("`")) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
