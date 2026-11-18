package com.ecojournal.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ecojournal.app.ui.theme.EcoDesignTokens

/**
 * Double rule used under the masthead and under a reader header.
 * v5 P2: thicknesses/gap now come from EcoDesignTokens.Rules
 * (2dp + 2dp gap + 1dp, plan §2) instead of literals.
 *
 * Phase 4 (v5.3): moved here verbatim from the deleted
 * ArticleCard.kt — the rule is a shared newspaper design
 * token, used by the invite screen, not a feed-section
 * component. NewspaperRule (the single light-grey rule) was
 * only used by feed cards and was not restored.
 */
@Composable
fun NewspaperDoubleRule(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        HorizontalDivider(
            thickness = EcoDesignTokens.Rules.DoubleThick,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(EcoDesignTokens.Rules.DoubleGap))
        HorizontalDivider(
            thickness = EcoDesignTokens.Rules.DoubleThin,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
