package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecojournal.app.data.MarketFormula
import com.ecojournal.app.data.MarketSnapshot
import com.ecojournal.app.ui.theme.EcoFontFamily

/**
 * Market board (read-only display). Numbers come from edge market.json,
 * which copies data_core.prices verbatim; formulas are shown with
 * their Persian lineage descriptions. No number is computed in the app
 * beyond formatting — derived values arrive from the edge, whose
 * generator documents the formula definitions.
 */
@Composable
fun MarketScreen(
    latest: MarketSnapshot?,
    formulas: List<MarketFormula>,
    snapshotCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("بورد بازار", fontFamily = EcoFontFamily)
        Spacer(modifier = Modifier.height(12.dp))
        if (latest == null) {
            Text(
                text = "دادهٔ بازار هنوز دریافت نشده است. پس از تنظیم لبهٔ " +
                    "انتشار، market.json همگام می‌شود.",
                fontFamily = EcoFontFamily,
            )
        } else {
            Text("آخرین اسنپ‌شات: ${latest.tsTehran}", fontFamily = EcoFontFamily)
            latest.brent?.let { Text("برنت: $it", fontFamily = EcoFontFamily) }
            latest.xauUsd?.let { Text("انس طلا (دلار): $it", fontFamily = EcoFontFamily) }
            latest.usdIrtFree?.let { Text("دلار آزاد (تومان): $it", fontFamily = EcoFontFamily) }
            latest.usdtIrt?.let { Text("تتر (تومان): $it", fontFamily = EcoFontFamily) }
            latest.sekkeEmami?.let { Text("سکهٔ امامی (تومان): $it", fontFamily = EcoFontFamily) }
            Spacer(modifier = Modifier.height(8.dp))
            Text("تعداد اسنپ‌شات‌ها: $snapshotCount", fontFamily = EcoFontFamily)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("فرمول‌های فعال (lineage)", fontFamily = EcoFontFamily)
        for (f in formulas) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("${f.name} (v${f.version})", fontFamily = EcoFontFamily)
            f.descriptionFa?.let { Text(it, fontFamily = EcoFontFamily) }
        }
    }
}
