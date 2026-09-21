package dev.vibecheck

import kotlin.math.abs
import kotlin.math.max

/**
 * Single-step contextual bandit over the judgments.
 *
 * There is no gradient to push into a hosted model, so "learning" here means two things
 * that are actually observable on device, with no labelling work from the user:
 *
 *  1. Danger calibration. We predict danger at turn t. At turn t+1 we see what the
 *     conversation actually became. The running error between the two is a per-relationship
 *     bias: this model may systematically over-call danger for THIS person.
 *  2. Action value. Context = the judged intent, arm = the action we put on top of the card,
 *     reward = how much the danger fell by the next turn. Arms that precede de-escalation
 *     get promoted on later cards.
 *
 * ponytail: reward is correlational, not causal. We cannot see whether the user followed the
 * advice, only what happened after it was shown. Upgrade path if that matters: make the card
 * touchable with a 采纳 tap and reward only adopted arms.
 */
object Learner {

    private const val LR = 0.15          // calibration step
    private const val MIN_N = 3          // arms below this never move the ranking
    private const val W = 0.5            // how much learned value can bend the model's probabilities

    data class Arm(val n: Int, val mean: Double)

    data class Model(
        var dangerBias: Double = 0.0,
        var updates: Int = 0,
        val arms: MutableMap<String, Arm> = LinkedHashMap(),
    )

    /** An outstanding prediction, waiting for the next turn to score it. */
    data class Episode(val situation: String, val intent: String, val action: String, val danger: Double)

    /**
     * Three keys per episode, most specific first. Context that has enough history wins; a
     * situation you have barely been in falls back to what is known about the intent, and a
     * brand-new intent still benefits from how the action has gone in general.
     */
    fun keys(situation: String, intent: String, action: String): List<String> =
        // distinct(): when the situation is unknown it IS "*", and without this the same arm
        // would be credited twice for one episode, inflating both its count and its confidence.
        listOf("$situation|$intent|$action", "*|$intent|$action", "*|*|$action").distinct()

    /** danger arrives normalized to 0..1 so levels can change without invalidating history. */
    fun observe(m: Model, e: Episode, nextDanger: Double): Double {
        val reward = e.danger - nextDanger          // positive = it calmed down
        for (key in keys(e.situation, e.intent, e.action)) {
            val prev = m.arms[key] ?: Arm(0, 0.0)
            val n = prev.n + 1
            m.arms[key] = Arm(n, prev.mean + (reward - prev.mean) / n)
        }

        m.dangerBias += LR * (nextDanger - e.danger)
        m.dangerBias = m.dangerBias.coerceIn(-0.35, 0.35)
        m.updates++
        return reward
    }

    /** Calibrated danger, still normalized 0..1. */
    fun danger(m: Model, raw: Double): Double = (raw + m.dangerBias).coerceIn(0.0, 1.0)

    /** The most specific arm that has enough evidence to be worth using. */
    fun armFor(m: Model, situation: String, intent: String, action: String): Arm? =
        keys(situation, intent, action).firstNotNullOfOrNull { k ->
            m.arms[k]?.takeIf { it.n >= MIN_N }
        }

    /** Reweight the action distribution by learned value. Returns probabilities, renormalized. */
    fun rerank(m: Model, situation: String, intent: String, probs: Map<String, Double>): Map<String, Double> {
        val adjusted = probs.mapValues { (action, p) ->
            val arm = armFor(m, situation, intent, action)
            if (arm == null) p
            else max(0.0, p * (1.0 + W * arm.mean.coerceIn(-1.0, 1.0)))
        }
        val sum = adjusted.values.sum()
        return if (sum <= 0.0) probs else adjusted.mapValues { it.value / sum }
    }

    /** True when experience actually changed which action is on top. */
    fun changedTop(before: Map<String, Double>, after: Map<String, Double>): Boolean =
        before.maxByOrNull { it.value }?.key != after.maxByOrNull { it.value }?.key

    // Flat text rather than JSON so this file stays Android-free and unit testable.
    /** Fold another record's bandit into this one: pooled arms, bias weighted by how much each learned. */
    fun merge(into: Model, from: Model) {
        val n = into.updates + from.updates
        if (n > 0) into.dangerBias = (into.dangerBias * into.updates + from.dangerBias * from.updates) / n
        into.updates = n
        for ((k, a) in from.arms) {
            val b = into.arms[k]
            into.arms[k] = if (b == null || b.n + a.n == 0) a
                else Arm(b.n + a.n, (b.mean * b.n + a.mean * a.n) / (b.n + a.n))
        }
    }

    fun save(m: Model): String = buildString {
        append("v1\t${m.dangerBias}\t${m.updates}\n")
        for ((k, a) in m.arms) append("$k\t${a.n}\t${a.mean}\n")
    }

    fun load(text: String): Model {
        val m = Model()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || !lines[0].startsWith("v1")) return m
        lines[0].split('\t').let {
            m.dangerBias = it.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            m.updates = it.getOrNull(2)?.toIntOrNull() ?: 0
        }
        for (l in lines.drop(1)) {
            val p = l.split('\t')
            if (p.size < 3) continue
            m.arms[p[0]] = Arm(p[1].toIntOrNull() ?: 0, p[2].toDoubleOrNull() ?: 0.0)
        }
        return m
    }

    fun summary(m: Model): List<String> {
        val top = m.arms.entries.filter { it.value.n >= MIN_N }.sortedByDescending { it.value.mean }.take(5)
        return listOf(L.t("更新 ${m.updates} 次，危险偏置 ${"%+.2f".format(m.dangerBias)}", "${m.updates} updates, danger bias ${"%+.2f".format(m.dangerBias)}")) +
            top.map { "${it.key}  n=${it.value.n}  r=${"%+.2f".format(it.value.mean)}" }
    }

    fun isTrained(m: Model) = m.updates >= MIN_N || abs(m.dangerBias) > 0.02
}
