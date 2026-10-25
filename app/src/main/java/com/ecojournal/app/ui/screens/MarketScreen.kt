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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.data.MarketFormula
import com.ecojournal.app.data.MarketSnapshot
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.formatPersianDouble
import com.ecojournal.app.util.formatPersianInt

/**
 * Market board (read-only display). Numbers come from edge market.json,
 * which copies data_core.prices verbatim; formulas are shown with
 * their Persian descriptions. No number is computed in the app
 * beyond formatting — derived values arrive from the edge, whose
 * generator documents the formula definitions.
 *
 * M3: the screen scrolls (before, a plain Column clipped on small
 * phones), numbers use Persian digits with thousands separators
 * (P2-07), the font scale applies here too, and the old heading
 * that over-promised formula provenance is gone — it now honestly
 * says "فرمول‌های فعال" (P2-01). Full field coverage and formula
 * evaluation stay M4 work.
 */
@Composable
fun MarketScreen(
    latest: MarketSnapshot?,
    formulas: List<MarketFormula>,
    snapshotCount: Int,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "بورد بازار",
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (latest == null) {
            Text(
                text = "دادهٔ بازار هنوز دریافت نشده است. بذر همراه برنامه " +
                    "در اجرای اول وارد می‌شود؛ اگر خالی ماند، پس از تنظیم " +
                    "لبهٔ انتشار market.json همگام می‌شود.",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
        } else {
            Text(
                text = "آخرین اسنپ‌شات: ${latest.tsTehran}",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            // Staleness label (M2): a snapshot older than 24 hours
            // is stale, not current. The bundled seed's newest
            // snapshot is dated 2026-10-24, so on first run offline
            // this label will honestly say so until a sync lands.
            val stale = latest.tsEpoch?.let {
                System.currentTimeMillis() - it > 24L * 60L * 60L * 1000L
            } ?: true
            if (stale) {
                Text(
                    text = "⚠ دادهٔ بازار قدیمی است (بیش از ۲۴ ساعت).",
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            latest.brent?.let {
                Text(
                    text = "برنت: ${formatPersianDouble(it)}",
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            latest.xauUsd?.let {
                Text(
                    text = "انس طلا (دلار): ${formatPersianDouble(it)}",
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            latest.usdIrtFree?.let {
                Text(
                    text = "دلار آزاد (تومان): ${formatPersianInt(it.toLong())}",
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            latest.usdtIrt?.let {
                Text(
                    text = "تتر (تومان): ${formatPersianInt(it.toLong())}",
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            latest.sekkeEmami?.let {
                Text(
                    text = "سکهٔ امامی (تومان): ${formatPersianInt(it.toLong())}",
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "تعداد اسنپ‌شات‌ها: ${formatPersianInt(snapshotCount)}",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "فرمول‌های فعال",
            fontFamily = EcoFontFamily,
            fontSize = 18.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        for (f in formulas) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${f.name} (v${f.version})",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            f.descriptionFa?.let {
                Text(
                    text = it,
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
        }
    }
}
