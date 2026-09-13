package com.nuvio.tv.ui.screens.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * An alignment plan: a frame-rate correction of the target timeline, followed by the piecewise
 * offsets of a [SubtitleSyncModel].
 *
 * [model] was produced by aligning the reference against the target *after* it had been rescaled by
 * [rateRatio], so its segment boundaries and offsets are expressed in the rescaled timeline. That
 * is why [rewrite] must rescale before delegating: scale first, offset second.
 */
internal data class SubtitleSyncPlan(
    val rateRatio: Double,
    val model: SubtitleSyncModel
) {
    val confidence: Double get() = model.confidence

    /** True when the target had to be resampled to a different frame rate to match the reference. */
    val isRescaled: Boolean get() = rateRatio != 1.0

    fun rewrite(document: SrtDocument): SrtDocument = model.rewrite(document.scaledBy(rateRatio))
}

/**
 * Frame-rate aware wrapper around [SubtitleTimingAligner].
 *
 * A subtitle authored against a different frame rate drifts linearly, and [SubtitleTimingAligner]
 * cannot express that: its model is offset-only, and its matching window spans minutes, so drift
 * accumulating past the match tolerance destroys matching outright. Rather than teach the aligner a
 * scale term, this rescales the target by each of a handful of *real* frame-rate ratios, runs the
 * unmodified aligner on each, and keeps the best scoring result.
 *
 * Frame rates are not a continuous space, which is what makes this safe: a continuous scale search
 * finds a plausible-looking fit for any input, whereas only a genuinely misframed track scores well
 * under one of these fixed ratios.
 *
 * ### Safety
 *
 * Drift that is *not* a frame-rate conversion (a stretched or badly retimed release, say) also
 * produces a best-scoring ratio, but a wrong one, and applying it is far worse than declining --
 * measured at 4-27 seconds of median error with 80%+ of the track displaced. Two independent guards
 * keep that out, and a candidate must clear both:
 *
 *  - [MIN_RESCALED_CONFIDENCE]. On a full length reference the correct ratio scores ~0.852 while
 *    every wrong or non-standard fit tops out at ~0.740, so the threshold sits in open space rather
 *    than on a boundary. Confidence is used in preference to segment count because a film with
 *    genuine frame-rate drift *and* an ad break would legitimately produce more than the two
 *    segments a clean recovery yields.
 *
 *  - [MIN_RESCALED_MATCHED_CUES]. Confidence alone is *not* sufficient: a short reference can be
 *    fitted by chance. A 16 cue reference taken from an unrelated title scores 0.881 against a
 *    989 cue target under one of these ratios -- higher than a genuine full length recovery --
 *    because seven hypotheses and a free offset can place a handful of points anywhere. What
 *    separates it is how much evidence the fit rests on: 15 matched cues there, versus 244 for the
 *    shortest legitimate reference measured (a 15 minute partial capture) and 1716 for a full one.
 *
 * Anything failing either guard falls back to the unscaled result -- usually null, so the user sees
 * the existing low-confidence message.
 *
 * A very short passive reference is handled before the rate scan. If the offset-only aligner can
 * validate a provisional constant fit, it is returned at [rateRatio] `1.0`; otherwise the
 * alignment is declined. This deliberately gives low evidence no path to a frame-rate or
 * piecewise correction.
 *
 * A passive reference can cover only the beginning (or another portion) of the target timeline.
 * In that case a direct offset-only fit can still clear the confidence threshold while a fixed-rate
 * candidate is the better explanation of the complete track. Partial references therefore pay the
 * rate-scan cost; the direct fit remains a candidate, but only rescaled fits that clear both guards
 * may compete with it. References that span the target keep the direct fast path.
 */
internal object SubtitleRateAwareAligner {

    /**
     * A rescaled alignment is only trusted above this. See the class note: correct fits measure
     * ~0.852 and incorrect ones ~0.740 on a full length feature, so the threshold has room either
     * side rather than being tuned to a boundary.
     */
    private const val MIN_RESCALED_CONFIDENCE = 0.80

    /**
     * Minimum matched cue evidence behind a rescaled alignment.
     *
     * Rescaling tests seven hypotheses where the plain aligner tests one, so it needs proportionally
     * more support to rule out a coincidence. Measured either side of this: spurious fits on short
     * references rest on 15-24 matched cues, while the shortest legitimate reference that recovers
     * real drift (a 15 minute partial playback capture) rests on 244 and a full film on 1716.
     */
    private const val MIN_RESCALED_MATCHED_CUES = 200

    /**
     * Partial references need extra confidence headroom before a rate correction is extrapolated
     * over the unseen target. The valid fifteen-minute pulldown recovery measures about 0.8299,
     * while the nearest unsupported 1.0025 drift measured 0.8006; this leaves a narrow margin
     * between the observed cases without changing the full-reference gate.
     */
    private const val MIN_PARTIAL_RESCALED_CONFIDENCE = 0.82

    /**
     * A reference covering less than this fraction of the target is treated as substantially
     * partial for rate-candidate safety. The span margin tolerates normal frame-rate differences
     * and trailing metadata while still identifying playback-sized captures.
     */
    private const val MIN_PARTIAL_REFERENCE_SPAN_RATIO = 0.90

    /**
     * Allow ordinary leading/trailing cue omissions when deciding whether a reference spans the
     * target. A substantially shorter passive capture must be scanned, even when its direct fit is
     * confident, because its confidence describes only the observed portion.
     */
    private const val REFERENCE_EDGE_TOLERANCE_MS = 2L * 60L * 1000L

    /**
     * Ratios to resample the target by, covering the film/PAL/NTSC conversions that actually occur.
     *
     * Deduplicated by value because several are the same number: 24/23.976 and 30/29.97 are both
     * the 1000/999 NTSC pulldown ratio, as are their inverses. Comparing with a tolerance rather
     * than `distinct()` since these are computed in floating point.
     */
    private val RATE_RATIOS: List<Double> = listOf(
        24.0 / 23.976,   // NTSC pulldown
        23.976 / 24.0,
        25.0 / 24.0,     // PAL speedup from film
        24.0 / 25.0,
        25.0 / 23.976,   // PAL speedup from NTSC film
        23.976 / 25.0,
        30.0 / 29.97,    // identical to 24/23.976, kept for intent
        29.97 / 30.0
    ).fold(mutableListOf()) { distinct, ratio ->
        if (distinct.none { abs(it - ratio) < 1e-9 }) distinct += ratio
        distinct
    }

    fun align(reference: List<SrtCue>, target: List<SrtCue>): SubtitleSyncPlan? {
        // The overwhelmingly common case is a correctly framed subtitle, so it must not pay for the
        // rate scan: a single alignment is ~80ms and every extra ratio costs the same again. A
        // partial passive reference is intentionally excluded from this shortcut; see the class
        // documentation above.
        val direct = SubtitleTimingAligner.align(reference, target)
            ?.let { SubtitleSyncPlan(rateRatio = 1.0, model = it) }
        val directSpansTarget = direct?.let {
            referenceSpansTarget(reference, target, it)
        } == true
        val partialReference = referenceIsSubstantiallyShorter(reference, target)
        if (SubtitleTimingAligner.isProvisionalEvidence(reference, target)) {
            // A short passive capture can establish a useful provisional offset, but it cannot
            // distinguish a genuine frame-rate conversion from a coincidental local fit.
            return direct
        }
        if (direct != null &&
            direct.confidence >= MIN_RESCALED_CONFIDENCE &&
            directSpansTarget &&
            !partialReference
        ) {
            return direct
        }

        val minimumRescaledConfidence = if (partialReference) {
            MIN_PARTIAL_RESCALED_CONFIDENCE
        } else {
            MIN_RESCALED_CONFIDENCE
        }
        val document = SrtDocument(target)
        val rescaled = RATE_RATIOS.mapNotNull { ratio ->
            SubtitleTimingAligner.align(reference, document.scaledBy(ratio).cues)
                ?.takeIf {
                    it.confidence >= minimumRescaledConfidence &&
                        it.matchedCueCount >= MIN_RESCALED_MATCHED_CUES
                }
                ?.let { SubtitleSyncPlan(rateRatio = ratio, model = it) }
        }

        // Preserve the established direct-fit policy; extrapolating a rate from a partial capture
        // deliberately requires more confidence. The direct fallback is not subject to the
        // rescaling evidence gates, so short but otherwise valid offset-only fits still work.
        val candidates = if (partialReference) {
            // A direct fit only describes the observed prefix. Never let it beat a validated
            // fixed-rate fit that can be safely extrapolated over the complete target.
            rescaled
        } else {
            rescaled.toMutableList().apply {
                if (direct != null && direct.confidence >= MIN_RESCALED_CONFIDENCE) {
                    add(direct)
                }
            }
        }

        // Ties are broken towards the least aggressive rescale, then the better supported model, so
        // the choice never depends on candidate ordering.
        return candidates.minWithOrNull(
            compareByDescending<SubtitleSyncPlan> { it.confidence }
                .thenBy { abs(ln(it.rateRatio)) }
                .thenByDescending { it.model.matchedCueCount }
                .thenBy { it.rateRatio }
        ) ?: direct
    }

    /**
     * Checks coverage after applying the direct model's offset range to the target edges. Using the
     * model offsets handles ordinary constant desync while still treating a short beginning-only
     * or middle-only capture as partial. The tolerance is intentionally generous enough for
     * missing credits and intro metadata, but far below a playback-sized capture.
     */
    private fun referenceSpansTarget(
        reference: List<SrtCue>,
        target: List<SrtCue>,
        direct: SubtitleSyncPlan
    ): Boolean {
        if (reference.isEmpty() || target.isEmpty() || direct.model.segments.isEmpty()) return false

        val referenceStartMs = reference.minOf(SrtCue::startMs)
        val referenceEndMs = reference.maxOf(SrtCue::endMs)
        val targetStartMs = target.minOf(SrtCue::startMs)
        val targetEndMs = target.maxOf(SrtCue::endMs)
        val minimumOffsetMs = direct.model.segments.minOf(SubtitleSyncSegment::offsetMs)
        val maximumOffsetMs = direct.model.segments.maxOf(SubtitleSyncSegment::offsetMs)
        val correctedTargetStartMs = targetStartMs + minimumOffsetMs
        val correctedTargetEndMs = targetEndMs + maximumOffsetMs

        return correctedTargetStartMs >= referenceStartMs - REFERENCE_EDGE_TOLERANCE_MS &&
            correctedTargetEndMs <= referenceEndMs + REFERENCE_EDGE_TOLERANCE_MS
    }

    private fun referenceIsSubstantiallyShorter(
        reference: List<SrtCue>,
        target: List<SrtCue>
    ): Boolean {
        if (reference.isEmpty() || target.isEmpty()) return false

        val targetSpanMs = target.maxOf(SrtCue::endMs) - target.minOf(SrtCue::startMs)
        if (targetSpanMs <= 0L) return false
        val referenceSpanMs = reference.maxOf(SrtCue::endMs) - reference.minOf(SrtCue::startMs)
        return referenceSpanMs.toDouble() / targetSpanMs < MIN_PARTIAL_REFERENCE_SPAN_RATIO
    }
}

/**
 * Resamples every timestamp by [ratio]. Identity for 1.0, so the unscaled path stays bit-for-bit
 * what it was before frame-rate support existed.
 *
 * The end is held at least one millisecond past the start: shrinking ratios can otherwise collapse
 * a very short cue to zero duration, and [SubtitleSyncModel.rewrite] silently drops those.
 */
private fun SrtDocument.scaledBy(ratio: Double): SrtDocument {
    if (ratio == 1.0) return this
    return SrtDocument(
        cues.map { cue ->
            val startMs = (cue.startMs * ratio).roundToLong().coerceAtLeast(0L)
            val endMs = (cue.endMs * ratio).roundToLong().coerceAtLeast(startMs + 1L)
            cue.copy(startMs = startMs, endMs = endMs)
        }
    )
}
