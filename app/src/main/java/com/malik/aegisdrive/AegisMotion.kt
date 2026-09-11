package com.malik.aegisdrive

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ProgressBar
import android.widget.TextView

/**
 * AegisMotion — centralized "rich but purposeful" motion helpers.
 *
 * Pure UI sugar built on the platform animation framework (no third-party deps).
 * Screens call these to add entrance, count-up, progress-fill and press feedback
 * without duplicating animator boilerplate. None of these touch business logic.
 */
object AegisMotion {

    /** Fade + rise a single view into place, optionally after [startDelay] ms. */
    fun entrance(view: View, startDelay: Long = 0L, distanceDp: Float = 18f) {
        val d = view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = distanceDp * d
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelay)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** Stagger direct children of a container into view (hero sections, grids). */
    fun staggerChildren(container: ViewGroup, step: Long = 55L, startDelay: Long = 60L) {
        for (i in 0 until container.childCount) {
            entrance(container.getChildAt(i), startDelay + i * step)
        }
    }

    /**
     * Apply the shared staggered layoutAnimation to a container and (re)play it.
     * Works for both plain ViewGroups and RecyclerViews.
     */
    fun playLayoutStagger(container: ViewGroup) {
        val ctrl = AnimationUtils.loadLayoutAnimation(
            container.context, R.anim.layout_animation_stagger
        )
        container.layoutAnimation = ctrl
        container.scheduleLayoutAnimation()
    }

    /** Count an integer up to [target], writing "[prefix]value[suffix]" into the TextView. */
    fun countUp(
        tv: TextView,
        target: Int,
        durationMs: Long = 900L,
        prefix: String = "",
        suffix: String = ""
    ) {
        val start = 0
        ValueAnimator.ofInt(start, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = "$prefix${it.animatedValue as Int}$suffix" }
            start()
        }
    }

    /** Animate a horizontal ProgressBar's progress to [target] with an easing sweep. */
    fun progressTo(bar: ProgressBar, target: Int, durationMs: Long = 900L) {
        ValueAnimator.ofInt(bar.progress, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { bar.progress = it.animatedValue as Int }
            start()
        }
    }

    /** Add a subtle scale-down-on-press to any tappable view for tactile feedback. */
    fun pressScale(view: View, downScale: Float = 0.96f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(downScale).scaleY(downScale)
                        .setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(2f))
                        .setDuration(180).start()
            }
            // Return false so normal click handling still fires.
            false
        }
    }

    /**
     * Walk the view tree under [root] and give every MaterialButton the subtle
     * press-scale feedback. One call wires tactile feedback for a whole screen
     * without per-button boilerplate. Safe to call once per screen after inflation.
     */
    fun applyPressToButtons(root: View?) {
        root ?: return
        when (root) {
            is com.google.android.material.button.MaterialButton -> pressScale(root)
            is ViewGroup -> for (i in 0 until root.childCount) applyPressToButtons(root.getChildAt(i))
        }
    }
}
