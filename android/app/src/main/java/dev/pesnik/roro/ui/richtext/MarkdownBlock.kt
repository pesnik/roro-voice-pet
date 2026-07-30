@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.pesnik.roro.ui.richtext

/*
 * Adapted from RikkaHub's app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt
 * (https://github.com/rikkahub/rikkahub, AGPL-3.0) — see NOTICE.md.
 *
 * Trimmed for this app's needs: no HTML-fallback rendering (Jsoup), no citations,
 * no image loading (Hermes never sends image parts), no HugeIcons/JetbrainsMono/
 * LocalSettings couplings, no LaTeX math rendering (formulas render as plain
 * monospace text). Code fences use CodeBlock.kt; tables use DataTable.kt.
 */

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

private val flavour by lazy { GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true) }
private val parser by lazy { MarkdownParser(flavour, false, CancellationToken.NonCancellable) }

private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")
private const val LINK_TAG = "URL"

private data class MarkdownParseResult(
    val content: String,
    val astTree: ASTNode,
    val hasHtml: Boolean,
)

private fun ASTNode.containsHtml(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtml() }
}

private fun parseMarkdown(content: String): MarkdownParseResult {
    val astTree = parser.buildMarkdownTreeFromString(content as CharSequence)
    return MarkdownParseResult(content, astTree, astTree.containsHtml())
}

/** Renders [content] as markdown. Falls back to plain text if it contains raw HTML
 * (rare for a chat-completions text stream, and we don't carry RikkaHub's Jsoup-based
 * HTML renderer) or if parsing throws. */
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    var data by remember { mutableStateOf(runCatching { parseMarkdown(content) }.getOrNull()) }

    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .mapLatest { runCatching { parseMarkdown(it) }.getOrNull() }
            .catch { it.printStackTrace() }
            .flowOn(Dispatchers.Default)
            .collect { data = it }
    }

    val parsed = data
    if (parsed == null || parsed.hasHtml) {
        ProvideTextStyle(style) {
            Text(text = content, modifier = modifier)
        }
        return
    }

    ProvideTextStyle(style) {
        Column(modifier = modifier.padding(horizontal = 4.dp)) {
            parsed.astTree.children.fastForEach { child ->
                MarkdownNode(node = child, content = parsed.content)
            }
        }
    }
}

private object HeaderStyle {
    private const val LINE_HEIGHT_RATIO = 1.25f

    private fun fromLevel(level: Int): TextStyle {
        val fontSize = when (level) {
            1 -> 24.sp; 2 -> 22.sp; 3 -> 20.sp; 4 -> 18.sp; 5 -> 16.sp; else -> 14.sp
        }
        return TextStyle(
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT_RATIO,
        )
    }

    private fun levelOf(type: IElementType) = when (type) {
        MarkdownElementTypes.ATX_1 -> 1
        MarkdownElementTypes.ATX_2 -> 2
        MarkdownElementTypes.ATX_3 -> 3
        MarkdownElementTypes.ATX_4 -> 4
        MarkdownElementTypes.ATX_5 -> 5
        else -> 6
    }

    fun verticalPadding(type: IElementType) = when (levelOf(type)) {
        1 -> 16.dp; 2 -> 14.dp; 3 -> 12.dp; 4 -> 10.dp; 5 -> 8.dp; else -> 6.dp
    }

    fun fromMarkdownType(type: IElementType) = fromLevel(levelOf(type))
}

@Composable
private fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    listLevel: Int = 0,
) {
    when (node.type) {
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child -> MarkdownNode(child, content, modifier) }
        }

        MarkdownElementTypes.PARAGRAPH -> {
            Paragraph(node = node, content = content, modifier = modifier)
        }

        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val style = HeaderStyle.fromMarkdownType(node.type)
            val headingPadding = HeaderStyle.verticalPadding(node.type)
            ProvideTextStyle(LocalTextStyle.current.merge(style)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children.fastForEach { child ->
                        if (child.type == MarkdownTokenTypes.ATX_CONTENT) {
                            Paragraph(
                                node = child,
                                content = content,
                                trim = true,
                                modifier = modifier.padding(vertical = headingPadding),
                            )
                        }
                    }
                }
            }
        }

        MarkdownElementTypes.UNORDERED_LIST -> {
            UnorderedListNode(node, content, modifier, listLevel)
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(node, content, modifier, listLevel)
        }

        GFMTokenTypes.CHECK_BOX -> {
            val isChecked = node.getTextInNode(content).trim() == "[x]"
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = modifier,
            ) {
                Row(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                ) {
                    if (isChecked) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        MarkdownElementTypes.BLOCK_QUOTE -> {
            ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            drawRect(color = bgColor, size = size)
                            drawRect(color = borderColor, size = Size(10f, size.height))
                        }
                        .padding(8.dp)
                ) {
                    node.children.fastForEach { child -> MarkdownNode(child, content) }
                }
            }
        }

        MarkdownElementTypes.EMPH -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child -> MarkdownNode(child, content, modifier) }
            }
        }

        MarkdownElementTypes.STRONG -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.Bold)) {
                node.children.fastForEach { child -> MarkdownNode(child, content, modifier) }
            }
        }

        GFMElementTypes.STRIKETHROUGH -> {
            Text(text = node.getTextInNode(content), textDecoration = TextDecoration.LineThrough, modifier = modifier)
        }

        GFMElementTypes.TABLE -> {
            TableNode(node = node, content = content, modifier = modifier)
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp,
            )
        }

        MarkdownElementTypes.IMAGE -> {
            // Hermes never sends image parts; just show the alt text/URL as a link.
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val imageUrl = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            Text(
                text = altText.ifBlank { imageUrl },
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier,
            )
        }

        GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH -> {
            // Real LaTeX rendering is an optional later phase — plain monospace for now.
            Text(text = node.getTextInNode(content), fontFamily = FontFamily.Monospace, modifier = modifier)
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            Text(text = code, fontFamily = FontFamily.Monospace, modifier = modifier)
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            Text(text = node.getTextInNode(content), fontFamily = FontFamily.Monospace, modifier = modifier)
        }

        MarkdownElementTypes.CODE_FENCE -> {
            val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            if (contentStartIndex == -1) return
            val eolElement =
                node.children.subList(0, contentStartIndex).findLast { it.type == MarkdownTokenTypes.EOL } ?: return
            val codeContentStartOffset = eolElement.endOffset
            val codeContentEndOffset =
                node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: return
            val code = content.substring(codeContentStartOffset, codeContentEndOffset).trimIndent()
            val language = node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: ""
            CodeBlock(
                code = code,
                language = language,
                modifier = modifier.padding(vertical = 4.dp),
            )
        }

        MarkdownTokenTypes.TEXT -> {
            Text(text = node.getTextInNode(content), modifier = modifier)
        }

        else -> {
            node.children.fastForEach { child -> MarkdownNode(child, content, modifier) }
        }
    }
}

@Composable
private fun UnorderedListNode(node: ASTNode, content: String, modifier: Modifier = Modifier, level: Int = 0) {
    val bulletStyle = when (level % 3) {
        0 -> "• "; 1 -> "◦ "; else -> "▪ "
    }
    Column(modifier = modifier.padding(start = (level * 8).dp)) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(child, content, bulletStyle, level)
            }
        }
    }
}

@Composable
private fun OrderedListNode(node: ASTNode, content: String, modifier: Modifier = Modifier, level: Int = 0) {
    Column(modifier.padding(start = (level * 8).dp)) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText =
                    child.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(content) ?: "$index. "
                ListItemNode(child, content, numberText, level)
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(node: ASTNode, content: String, bulletText: String, level: Int) {
    Column {
        val (directContent, nestedLists) = separateContentAndLists(node)
        if (directContent.isNotEmpty()) {
            Row {
                Text(
                    text = bulletText,
                    modifier = Modifier.alignByBaseline(),
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    directContent.fastForEach { child -> MarkdownNode(child, content, listLevel = level) }
                }
            }
        }
        nestedLists.fastForEach { nested -> MarkdownNode(nested, content, listLevel = level + 1) }
    }
}

private fun separateContentAndLists(listItemNode: ASTNode): Pair<List<ASTNode>, List<ASTNode>> {
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> nestedLists.add(child)
            else -> directContent.add(child)
        }
    }
    return directContent to nestedLists
}

@Composable
private fun TableNode(node: ASTNode, content: String, modifier: Modifier = Modifier) {
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0
    if (columnCount == 0) return

    val headerCells = headerNode?.children
        ?.filter { it.type == GFMTokenTypes.CELL }
        ?.map { it.getTextInNode(content).trim() } ?: emptyList()
    val rows = rowNodes.map { rowNode ->
        rowNode.children.filter { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(content).trim() }
    }

    val headers: List<@Composable () -> Unit> = List(columnCount) { columnIndex ->
        @Composable { MarkdownBlock(content = headerCells.getOrElse(columnIndex) { "" }) }
    }
    val rowComposables: List<List<@Composable () -> Unit>> = rows.map { rowData ->
        List(columnCount) { columnIndex ->
            @Composable { MarkdownBlock(content = rowData.getOrElse(columnIndex) { "" }) }
        }
    }

    val clipboard = LocalClipboardManager.current
    val tableMarkdown = remember(node, content) { node.getTextInNode(content).trim() }
    var justCopied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Table",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (justCopied) "Copied" else "Copy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(tableMarkdown))
                    justCopied = true
                },
            )
        }
        DataTable(
            headers = headers,
            rows = rowComposables,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

@Composable
private fun Paragraph(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    FlowRow(
        modifier = modifier.then(
            if (node.nextSibling() != null) Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp()) else Modifier
        )
    ) {
        val annotatedString = remember(content) {
            buildAnnotatedString {
                node.children.fastForEach { child ->
                    appendMarkdownNodeContent(child, content, colorScheme = colorScheme, trim = trim)
                }
            }
        }
        ClickableText(
            text = annotatedString,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current,
            onClick = { offset ->
                annotatedString.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.let { link ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.item.toUri())) }
                }
            },
        )
    }
}

private fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: ASTNode,
    content: String,
    colorScheme: androidx.compose.material3.ColorScheme,
    trim: Boolean = false,
) {
    when {
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content)
            pushStringAnnotation(LINK_TAG, link)
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(link) }
            pop()
        }

        node is LeafASTNode -> {
            val text = node.getTextInNode(content).let {
                (if (trim) it.trim() else it).replace(BREAK_LINE_REGEX, "\n")
            }
            append(text)
        }

        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendMarkdownNodeContent(it, content, colorScheme)
                }
            }
        }

        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendMarkdownNodeContent(it, content, colorScheme)
                }
            }
        }

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendMarkdownNodeContent(it, content, colorScheme)
                }
            }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                ?.trim { it == '[' || it == ']' } ?: linkDest
            pushStringAnnotation(LINK_TAG, linkDest)
            withStyle(SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                append(linkText)
            }
            pop()
        }

        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
            links.fastForEach { link ->
                val linkUrl = link.getTextInNode(content)
                pushStringAnnotation(LINK_TAG, linkUrl)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(linkUrl) }
                pop()
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, color = colorScheme.primary)) {
                append(' '); append(code); append(' ')
            }
        }

        node.type == GFMElementTypes.INLINE_MATH -> {
            // Rendered as plain monospace text until an optional later math phase.
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.95.em)) {
                append(node.getTextInNode(content))
            }
        }

        else -> {
            node.children.fastForEach { appendMarkdownNodeContent(it, content, colorScheme) }
        }
    }
}

private fun ASTNode.getTextInNode(text: String): String = text.substring(startOffset, endOffset)

private fun ASTNode.nextSibling(): ASTNode? {
    val siblings = parent?.children ?: return null
    val i = siblings.indexOf(this)
    return if (i in siblings.indices && i + 1 < siblings.size) siblings[i + 1] else null
}

private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        child.findChildOfTypeRecursive(*types)?.let { return it }
    }
    return null
}

private fun List<ASTNode>.trim(type: IElementType, size: Int): List<ASTNode> {
    if (isEmpty() || size <= 0) return this
    var start = 0
    var end = this.size
    var trimmed = 0
    while (start < end && trimmed < size && this[start].type == type) { start++; trimmed++ }
    trimmed = 0
    while (end > start && trimmed < size && this[end - 1].type == type) { end--; trimmed++ }
    return subList(start, end)
}
