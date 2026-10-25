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
import com.ecojournal.app.ui.theme.EcoFontFamily

// Phase 6 — Method page (About → Method, spec §1).
// Bundled editorial copy, NOT a manifest-synced section: it works
// offline (including airplane-mode launch) and can never show a
// "failed to load method" state. All 7 sections (M1–M7) render in
// fixed order with the version + Shamsi date line. No source
// buttons are rendered on this page, so no button can ever point
// at the blog (P6-M4, BLOG_LIVE=False). No personal name, no AI
// trace, no private-report content, no model probability, and no
// internal threshold presented as a reader promise (P6-M3).
@Composable
fun MethodScreen(
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val sections = listOf(
        R.string.method_m1_title to R.string.method_m1_body,
        R.string.method_m2_title to R.string.method_m2_body,
        R.string.method_m3_title to R.string.method_m3_body,
        R.string.method_m4_title to R.string.method_m4_body,
        R.string.method_m5_title to R.string.method_m5_body,
        R.string.method_m6_title to R.string.method_m6_body,
        R.string.method_m7_title to R.string.method_m7_body,
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.method_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.method_version_line),
            fontFamily = EcoFontFamily,
            fontSize = 13.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(16.dp))
        for ((titleRes, bodyRes) in sections) {
            Text(
                text = stringResource(titleRes),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(bodyRes),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
