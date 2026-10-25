package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ecojournal.app.R
import com.ecojournal.app.ui.theme.EcoDesignTokens
import com.ecojournal.app.ui.theme.EcoFontFamily

/**
 * v5 P2 — sections hub.
 *
 * The bottom bar holds exactly five destinations (feed, warroom,
 * market, bookmarks, settings). Bundles, Nightly, Analysis, and
 * Method — removed from the bar — live here, one tap away, with
 * labels that fit (the eight-item bar's wrapped labels were plan
 * §1 M3). This screen adds NO new content and NO new gate
 * behaviour: it only navigates to the four existing routes.
 * Gated sections (voices, special issues) are deliberately NOT
 * hub destinations; they stay reachable only via their gated
 * placeholders in Settings, exactly as before.
 *
 * The bottom bar stays visible on this route (it is not in
 * MainActivity's hideBar set), so the hub never strands the
 * reader; no tab is selected while the hub is open.
 */
@Composable
fun SectionsHubScreen(
    onOpenBundles: () -> Unit = {},
    onOpenNightly: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenMethod: () -> Unit = {},
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EcoDesignTokens.Spacing.PageMargin),
    ) {
        Text(
            text = stringResource(R.string.sections_hub_title),
            fontFamily = EcoFontFamily,
            fontSize = EcoDesignTokens.Type.SectionHeader * fontScale,
            fontWeight = EcoDesignTokens.Type.SectionHeaderWeight,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
        Text(
            text = stringResource(R.string.sections_hub_desc),
            fontFamily = EcoFontFamily,
            fontSize = EcoDesignTokens.Type.Meta * fontScale,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.BlockGap))
        for ((labelRes, action) in listOf(
            R.string.tab_bundles to onOpenBundles,
            R.string.tab_nightly to onOpenNightly,
            R.string.tab_analysis to onOpenAnalysis,
            R.string.settings_method_button to onOpenMethod,
        )) {
            Button(
                onClick = action,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(labelRes),
                    fontFamily = EcoFontFamily,
                )
            }
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
        }
    }
}
