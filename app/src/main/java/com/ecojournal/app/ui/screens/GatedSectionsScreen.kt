package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.SpecialIssuesGate
import com.ecojournal.app.data.VoicesGate
import com.ecojournal.app.ui.theme.EcoFontFamily

// Phase 6 — gated placeholders (spec §3.6 voices, §4.4 special
// issues). Each screen renders EXACTLY its placeholder text and
// nothing more: no draft voice name, no count, no tier, no sample
// card, no queued-post title/author/PDF link, no search index
// entry, no notification. The placeholders are reachable from
// their normal navigation entries (Settings buttons) so the
// absence is honest rather than hidden. Both gates are closed by
// constants in Phase6Domain (VoicesGate.FINALISED = false,
// SpecialIssuesGate.FROZEN = true) and cannot be opened from here.
@Composable
fun VoicesScreen(
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    // Guard, not decoration: if finalisation ever lands, this
    // screen must be replaced by the real section (§3.8), never
    // by adding names to the placeholder.
    check(!VoicesGate.FINALISED) { "Voices placeholder rendered after finalisation" }
    Column(
        modifier = modifier
            .fillMaxSize()
            // Phase 7: non-scrolling column could clip the
            // placeholder copy at 1.3x; MethodScreen already
            // scrolls — copy that pattern.
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.voices_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.voices_placeholder),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
    }
}

@Composable
fun SpecialIssuesScreen(
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    check(SpecialIssuesGate.FROZEN) { "Special issues placeholder rendered while unfrozen" }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.special_issues_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.special_issues_placeholder),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
    }
}
