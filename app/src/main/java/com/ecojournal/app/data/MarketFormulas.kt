package com.ecojournal.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A formula with its value computed in the app (M4), not just its
 * definition shown as text. [value] is null exactly when the
 * computation is not possible from this snapshot: a missing input,
 * a division by zero, an unknown operator or an unparseable
 * definition. The UI renders null as "—" with the inputs listed,
 * so a missing value is visible, never silently replaced.
 */
data class ComputedFormula(
    val name: String,
    val version: Int,
    val descriptionFa: String?,
    val value: Double?,
    /** "تومان" for money formulas, "٪" for percentage formulas, "" otherwise. */
    val unit: String,
    /** Persian input label to resolved value (null = that input is missing). */
    val inputs: List<Pair<String, Double?>>,
)

/**
 * Formula evaluation (M4).
 *
 * The edge/bundled market.json ships each active formula as a
 * JSON-encoded S-expression over named inputs, e.g. intrinsic_emami
 * is ["div", ["mul", ["mul", "xau_usd", "usd_tmn"], 7.3197], 31.1035].
 * The evaluator runs that shipped definition — it does not duplicate
 * the arithmetic in Kotlin, so a definition change on the edge
 * changes the computed value without an app update. The only
 * app-side knowledge is the input-name mapping, which comes from
 * the formulas' own Persian descriptions:
 * - gold18_tmn = gold_18k_gram, xau_usd = xau_usd,
 *   usd_tmn = usd_irt_free (the free-market dollar).
 * - bubble_pct's "market" is the market sekke_emami price and its
 *   "intrinsic" is the OUTPUT of intrinsic_emami, not a column
 *   (its description says so explicitly), so it is resolved by
 *   evaluating that formula recursively, with a cycle guard.
 * - spread_pct's generic "a"/"b" are, per its description's
 *   standard application, usdt_irt and usd_irt_free.
 * Anything else — unknown variable, unknown operator, missing
 * input, division by zero, non-finite result — yields null, never
 * a guessed number.
 */
object MarketFormulas {

    fun formulaFromJson(obj: JSONObject): MarketFormula? {
        val name = obj.optString("name", "")
        if (name.isBlank()) return null
        val definition = obj.optString("definition", "")
        if (definition.isBlank()) return null
        return MarketFormula(
            name = name,
            version = obj.optInt("version", 1),
            definition = definition,
            inputs = if (obj.isNull("inputs")) null else obj.optString("inputs"),
            descriptionFa = if (obj.isNull("description_fa")) null
                else obj.optString("description_fa"),
        )
    }

    fun evaluate(
        snapshot: MarketSnapshot,
        formulas: List<MarketFormula>,
    ): List<ComputedFormula> {
        val byName = formulas.associateBy { it.name }
        return formulas.map { formula ->
            ComputedFormula(
                name = formula.name,
                version = formula.version,
                descriptionFa = formula.descriptionFa,
                value = evalDefinition(formula, snapshot, byName, emptySet()),
                unit = when (formula.name) {
                    "implied_usd", "intrinsic_emami" -> "تومان"
                    "bubble_pct", "spread_pct" -> "٪"
                    else -> ""
                },
                inputs = inputValues(formula, snapshot, byName),
            )
        }
    }

    private fun inputValues(
        formula: MarketFormula,
        snapshot: MarketSnapshot,
        byName: Map<String, MarketFormula>,
    ): List<Pair<String, Double?>> {
        val raw = formula.inputs ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until arr.length()).map { i ->
            val name = arr.optString(i)
            inputLabel(name) to resolveVariable(name, snapshot, byName, emptySet())
        }
    }

    private fun inputLabel(name: String): String = when (name) {
        "gold18_tmn" -> "طلای ۱۸ عیار (تومان بر گرم)"
        "xau_usd" -> "انس طلا (دلار)"
        "usd_tmn" -> "دلار آزاد (تومان)"
        "market" -> "سکهٔ امامی در بازار (تومان)"
        "intrinsic" -> "ارزش ذاتی سکهٔ امامی (تومان)"
        "a" -> "تتر (تومان)"
        "b" -> "دلار آزاد (تومان)"
        else -> name
    }

    private fun evalDefinition(
        formula: MarketFormula,
        snapshot: MarketSnapshot,
        byName: Map<String, MarketFormula>,
        visiting: Set<String>,
    ): Double? {
        if (formula.name in visiting) return null
        val tree = try {
            JSONArray(formula.definition)
        } catch (_: Exception) {
            return null
        }
        return evalNode(tree, snapshot, byName, visiting + formula.name)
    }

    private fun resolveVariable(
        name: String,
        snapshot: MarketSnapshot,
        byName: Map<String, MarketFormula>,
        visiting: Set<String>,
    ): Double? = when (name) {
        "gold18_tmn" -> snapshot.gold18kGram
        "xau_usd" -> snapshot.xauUsd
        "usd_tmn" -> snapshot.usdIrtFree
        "market" -> snapshot.sekkeEmami
        // bubble_pct's "intrinsic": the intrinsic_emami formula's
        // output, evaluated on this same snapshot (cycle-guarded).
        "intrinsic" -> byName["intrinsic_emami"]?.let {
            evalDefinition(it, snapshot, byName, visiting)
        }
        // spread_pct's standard application: USDT vs free dollar.
        "a" -> snapshot.usdtIrt
        "b" -> snapshot.usdIrtFree
        else -> null
    }

    private fun evalNode(
        node: Any?,
        snapshot: MarketSnapshot,
        byName: Map<String, MarketFormula>,
        visiting: Set<String>,
    ): Double? = when (node) {
        is Number -> node.toDouble().takeIf { it.isFinite() }
        is String -> resolveVariable(node, snapshot, byName, visiting)
        is JSONArray -> {
            if (node.length() == 0) {
                null
            } else {
                val op = node.optString(0)
                val operands = (1 until node.length()).map { i ->
                    evalNode(node.get(i), snapshot, byName, visiting)
                }
                if (operands.isEmpty() || operands.any { it == null }) {
                    null
                } else {
                    val values = operands.map { it!! }
                    when (op) {
                        "add" -> values.sum().takeIf { it.isFinite() }
                        "sub" -> values.drop(1).fold(values[0]) { acc, v -> acc - v }
                            .takeIf { it.isFinite() }
                        "mul" -> values.fold(1.0) { acc, v -> acc * v }
                            .takeIf { it.isFinite() }
                        "div" -> {
                            if (values.drop(1).any { it == 0.0 }) {
                                null
                            } else {
                                values.drop(1).fold(values[0]) { acc, v -> acc / v }
                                    .takeIf { it.isFinite() }
                            }
                        }
                        else -> null
                    }
                }
            }
        }
        else -> null
    }
}
