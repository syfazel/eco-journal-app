package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.data.ComputedFormula
import com.ecojournal.app.data.MarketSnapshot
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.formatPersianDouble
import com.ecojournal.app.util.formatPersianInt

/**
 * Market board (read-only display), M4.
 *
 * All 15 snapshot fields are shown (snapshot id, timestamp and the
 * 13 prices), with "—" for a null price — a missing field is never
 * silently omitted. "Latest" is the Room row with max tsEpoch
 * (the DAO orders by tsEpoch, not by array position), and change
 * is computed against the previous distinct timestamp, only where
 * both snapshots carry the field. Formulas are COMPUTED here from
 * their shipped definitions (MarketFormulas.evaluate); a value
 * that cannot be computed shows "—" with its inputs listed, so the
 * reason is visible. Staleness uses a 24h threshold: the bundled
 * seed's newest snapshot is dated 2026-10-24, so offline first
 * runs honestly say stale until a sync lands (M5 wires the edge).
 * States are distinct: still seeding (loading), seed done with no
 * snapshot (error + retry, which re-runs the resumable seed), and
 * ready. Nothing is invented: every number is a bundled/cached
 * snapshot value or a formula evaluated from one.
 */
private data class PriceField(
    val label: String,
    val value: Double?,
    val previous: Double?,
    /** Decimals for the price itself; change uses the same decimals. */
    val decimals: Int,
)

private fun ageText(epoch: Long?): String? {
    if (epoch == null) return null
    val ageMs = System.currentTimeMillis() - epoch
    if (ageMs < 0L) return "تازه"
    if (ageMs < 60L * 60L * 1000L) return "کمتر از یک ساعت پیش"
    val hours = ageMs / (60L * 60L * 1000L)
    if (hours < 24L) return "${formatPersianInt(hours)} ساعت پیش"
    val days = hours / 24L
    return "${formatPersianInt(days)} روز پیش"
}

private fun formatPrice(value: Double, decimals: Int): String {
    return if (decimals == 0) {
        formatPersianInt(value.toLong())
    } else {
        formatPersianDouble(value, decimals)
    }
}

/** Change vs the previous snapshot, or null where not computable. */
private fun changeText(now: Double, prev: Double?, decimals: Int): String? {
    if (prev == null || prev == 0.0) return null
    val delta = now - prev
    if (delta == 0.0) return "بدون تغییر نسبت به اسنپ‌شات قبل"
    val arrow = if (delta > 0) "▲" else "▼"
    val sign = if (delta > 0) "+" else "−"
    val pct = kotlin.math.abs(delta / prev * 100.0)
    return "$arrow $sign${formatPrice(kotlin.math.abs(delta), decimals)} " +
        "(${formatPersianDouble(pct, 2)}٪)"
}

private fun formulaValueText(formula: ComputedFormula): String {
    val value = formula.value ?: return "—"
    return when (formula.unit) {
        "٪" -> "${formatPersianDouble(value, 2)}٪"
        "تومان" -> "${formatPersianInt(value.toLong())} تومان"
        else -> formatPersianDouble(value, 2)
    }
}

/**
 * Input display: large whole values (toman amounts) as integers
 * with separators; smaller/fractional values with 2 decimals.
 * This is display formatting only; the evaluated value itself
 * always uses full double precision.
 */
private fun inputValueText(value: Double): String {
    return if (kotlin.math.abs(value) >= 10_000.0 || value == kotlin.math.floor(value)) {
        formatPersianInt(value.toLong())
    } else {
        formatPersianDouble(value, 2)
    }
}

@Composable
fun MarketScreen(
    latest: MarketSnapshot?,
    previous: MarketSnapshot?,
    formulas: List<ComputedFormula>,
    snapshotCount: Int,
    seedDone: Boolean,
    onRetry: () -> Unit,
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
        if (!seedDone) {
            Text(
                text = "در حال بارگذاری دادهٔ بازار…",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            return@Column
        }
        if (latest == null) {
            Text(
                text = "دادهٔ بازار در دسترس نیست.",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "بذر همراه برنامه اجرا شد ولی هیچ اسنپ‌شاتی " +
                    "در حافظه نیست؛ فایل بازار ممکن است خراب یا " +
                    "ناقص باشد. با تلاش دوباره، بذر از همان‌جایی " +
                    "که مانده ادامه می‌دهد و چیزی از اول ساخته نمی‌شود.",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(
                    text = "تلاش دوباره",
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            return@Column
        }
        val stale = latest.tsEpoch?.let {
            System.currentTimeMillis() - it > 24L * 60L * 60L * 1000L
        } ?: true
        Text(
            text = "آخرین اسنپ‌شات: ${latest.tsTehran}",
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        ageText(latest.tsEpoch)?.let {
            Text(
                text = "آخرین به‌روزرسانی: $it",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (latest.snapshotId.isNotBlank()) {
            Text(
                text = "شناسهٔ اسنپ‌شات: ${latest.snapshotId}",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stale) {
            Text(
                text = "⚠ دادهٔ بازار قدیمی است (بیش از ۲۴ ساعت). " +
                    "این اسنپ‌شات از حافظهٔ محلی است و با همگام‌سازی " +
                    "آینده به‌روز می‌شود.",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
        }
        previous?.let {
            Text(
                text = "مقایسه با اسنپ‌شات قبل: ${it.tsTehran}",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        val fields = listOf(
            PriceField("برنت (دلار)", latest.brent, previous?.brent, 2),
            PriceField("انس طلا (دلار)", latest.xauUsd, previous?.xauUsd, 2),
            PriceField("شاخص دلار", latest.dxy, previous?.dxy, 2),
            PriceField("تتر (دلار)", latest.usdtUsd, previous?.usdtUsd, 5),
            PriceField("دلار آزاد (تومان)", latest.usdIrtFree, previous?.usdIrtFree, 0),
            PriceField("تتر (تومان)", latest.usdtIrt, previous?.usdtIrt, 0),
            PriceField("طلای ۱۸ عیار هر گرم (تومان)", latest.gold18kGram, previous?.gold18kGram, 0),
            PriceField("سکهٔ امامی (تومان)", latest.sekkeEmami, previous?.sekkeEmami, 0),
            PriceField("سکهٔ بهار آزادی (تومان)", latest.sekkeBahar, previous?.sekkeBahar, 0),
            PriceField("نیم‌سکه (تومان)", latest.nimSekke, previous?.nimSekke, 0),
            PriceField("ربع‌سکه (تومان)", latest.robSekke, previous?.robSekke, 0),
            PriceField("شاخص ترس (VIX)", latest.vix, previous?.vix, 2),
            PriceField("شاخص S&P 500", latest.sp500, previous?.sp500, 2),
        )
        for (field in fields) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${field.label}: " +
                    if (field.value == null) "—" else formatPrice(field.value, field.decimals),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            if (field.value != null) {
                changeText(field.value, field.previous, field.decimals)?.let { change ->
                    Text(
                        text = "تغییر نسبت به قبل: $change",
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "تعداد اسنپ‌شات‌ها: ${formatPersianInt(snapshotCount)}",
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "فرمول‌های محاسبه‌شده",
            fontFamily = EcoFontFamily,
            fontSize = 18.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        if (formulas.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "فرمولی در دسترس نیست. فرمول‌ها همراه بذر " +
                    "بازار وارد می‌شوند؛ اگر خالی ماند، با همگام‌سازی " +
                    "آینده به‌روز می‌شوند.",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
        }
        for (f in formulas) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${f.name} (v${f.version})",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "مقدار محاسبه‌شده: ${formulaValueText(f)}",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            if (f.value == null) {
                Text(
                    text = "محاسبه ممکن نشد؛ یک یا چند ورودی در این " +
                        "اسنپ‌شات موجود نیست.",
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            f.descriptionFa?.let {
                Text(
                    text = it,
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            if (f.inputs.isNotEmpty()) {
                Text(
                    text = "ورودی‌ها:",
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((label, inputValue) in f.inputs) {
                    Text(
                        text = "$label: " +
                            if (inputValue == null) "—" else inputValueText(inputValue),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
