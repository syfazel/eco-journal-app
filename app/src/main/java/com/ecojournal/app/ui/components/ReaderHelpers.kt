package com.ecojournal.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.ArcNode
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlin.math.roundToInt

/**
 * v4-H helper modules (presentation only — no new data, no new
 * sync, no tracking of what the reader reads).
 *
 * H1 Reading Ruler: a translucent guide band the reader drags
 * vertically over the body. It marks a line, nothing more: it
 * never hides text, never reflows it, and never records a
 * position. Drag is clamped to the visible reader area so the
 * band cannot be lost off-screen.
 *
 * H1 Calm Mode is a rendering flag in ArticleScreen, not a
 * component here: title + standfirst + points + body +
 * why-it-matters only; the arc strip, the ruler, and the
 * source-link button row are hidden. Attribution is never lost
 * in calm mode — the byline (verbatim source name) stays.
 *
 * H2 Story Arc Strip: nodes of one story, oldest first (see
 * StoryArc). Typed nodes open their channel reader; the article
 * node is the current page and is not a button. Retracted
 * versions never appear (StoryArc drops them), and a
 * single-version story renders no strip at all — the caller
 * passes only non-empty node lists, and this composable also
 * returns early on fewer than 2 nodes as a second guard.
 */
@Composable
fun ReadingRuler(
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    var offsetY by remember { mutableStateOf(0.dp) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .offset { IntOffset(0, offsetY.roundToPx()) }
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    val next = offsetY + dragAmount.toDp()
                    offsetY = next.coerceIn(0.dp, 600.dp)
                }
            }
            .semantics {
                contentDescription = "خط‌کش مطالعه؛ با کشیدن عمودی جابه‌جا می‌شود"
            },
    ) {
        Text(
            text = stringResource(R.string.reading_ruler_toggle),
            fontSize = 11.sp * fontScale,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun StoryArcStrip(
    nodes: List<ArcNode>,
    onOpenChannel: (String) -> Unit,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    if (nodes.size < 2) return
    val desc = stringResource(
        R.string.story_arc_desc,
        nodes.joinToString("، ") { it.title },
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = desc },
    ) {
        Text(
            text = stringResource(R.string.story_arc_title),
            fontSize = 14.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for ((index, node) in nodes.withIndex()) {
                Column(modifier = Modifier.width(180.dp)) {
                    Text(
                        text = if (node.kind == "typed") "●" else "◆",
                        fontSize = 14.sp * fontScale,
                        color = if (node.kind == "typed") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    if (node.kind == "typed") {
                        TextButton(
                            onClick = { onOpenChannel(node.appKey) },
                            modifier = Modifier.semantics {
                                contentDescription = "باز کردن نسخه: ${node.title}"
                            },
                        ) {
                            Text(
                                text = node.title,
                                fontSize = 13.sp * fontScale,
                                fontFamily = EcoFontFamily,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = node.title,
                            fontSize = 13.sp * fontScale,
                            fontFamily = EcoFontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                if (index < nodes.lastIndex) {
                    Text(
                        text = " ← ",
                        fontSize = 14.sp * fontScale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
