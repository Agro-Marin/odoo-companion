package com.odoocompanion.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import com.odoocompanion.R
import com.odoocompanion.databinding.GuideCardBinding

// Draws the step EnrollmentGuide chose: its card slides in over the bottom of
// the form and breathes, and the field it is about is outlined in the accent.
// Nothing is animated when the system has animations off; the form keeps its
// place, so it still fits the screen while the guide is up.
class EnrollmentGuideView(
    private val steps: Map<GuideField, Pair<TextInputLayout, GuideCardBinding>>,
    onDismiss: (GuideField) -> Unit,
) {
    private var shown: GuideField? = null
    private var pulse: ValueAnimator? = null

    init {
        for ((field, step) in steps) {
            val card = step.second
            val text = TEXTS.getValue(field)
            card.guideTitle.setText(text.title)
            card.guideBody.setText(text.body)
            card.guideOdoo.setText(text.odoo)
            card.guideDismiss.setOnClickListener { onDismiss(field) }
        }
    }

    fun show(step: GuideStep?) {
        if (step?.field != shown) {
            shown?.let(::hide)
            shown = step?.field
            step?.let { reveal(it.field) }
        }
        step?.let { label(it) }
    }

    fun stop() {
        pulse?.cancel()
        pulse = null
    }

    private fun label(step: GuideStep) {
        val card = steps.getValue(step.field).second
        card.guideStep.text =
            card.root.context.getString(R.string.guide_step, step.number, step.total)
    }

    private fun reveal(field: GuideField) {
        val (layout, card) = steps.getValue(field)
        val root = card.root
        root.visibility = View.VISIBLE
        if (animated()) {
            root.alpha = 0f
            root.translationY = SLIDE_DP * root.resources.displayMetrics.density
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(REVEAL_MILLIS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            breathe(card)
        }
        light(layout)
    }

    private fun hide(field: GuideField) {
        stop()
        val (layout, card) = steps.getValue(field)
        ContextCompat.getColorStateList(layout.context, R.color.field_stroke)
            ?.let(layout::setBoxStrokeColorStateList)
        val root = card.root
        root.animate().cancel()
        root.alpha = 1f
        root.translationY = 0f
        root.visibility = View.GONE
    }

    // The field the card is about, outlined in the accent until it is filled.
    private fun light(layout: TextInputLayout) {
        val accent = MaterialColors.getColor(layout, androidx.appcompat.R.attr.colorPrimary)
        // stateful on purpose: a single color only replaces the focused one
        layout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(accent, accent),
            ),
        )
    }

    private fun breathe(card: GuideCardBinding) {
        val strong = MaterialColors.getColor(card.root, androidx.appcompat.R.attr.colorPrimary)
        val soft = ColorUtils.setAlphaComponent(strong, SOFT_ALPHA)
        stop()
        pulse = ValueAnimator.ofObject(ArgbEvaluator(), strong, soft).apply {
            duration = PULSE_MILLIS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { card.root.strokeColor = it.animatedValue as Int }
            start()
        }
    }

    private fun animated(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private data class Texts(
        @param:StringRes val title: Int,
        @param:StringRes val body: Int,
        @param:StringRes val odoo: Int,
    )

    private companion object {
        const val REVEAL_MILLIS = 280L
        const val PULSE_MILLIS = 900L
        const val SLIDE_DP = 24f
        const val SOFT_ALPHA = 0x40

        val TEXTS = mapOf(
            GuideField.BASE_URL to Texts(
                R.string.guide_base_url_title,
                R.string.guide_base_url_body,
                R.string.guide_base_url_odoo,
            ),
            GuideField.IDENTIFIER to Texts(
                R.string.guide_identifier_title,
                R.string.guide_identifier_body,
                R.string.guide_identifier_odoo,
            ),
            GuideField.TOKEN to Texts(
                R.string.guide_token_title,
                R.string.guide_token_body,
                R.string.guide_token_odoo,
            ),
        )
    }
}
