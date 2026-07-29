package com.systemsgo.hex.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.NavBackStackEntry

// ─────────────────────────────────────────────────────────────────────────────
//  SPACE MOTION — shared "hyperspace jump" transition used everywhere a new
//  screen or full-screen surface opens (Settings + all of its sections,
//  Connection History, Sessions, the Add/Edit Connection form, …).
//
//  UI-FIX (transitions): the previous NavHost transitions were a plain
//  horizontal slide — the generic default every Android app uses, and it
//  didn't read as intentional for an RDP/space-themed client. This replaces
//  it everywhere with one consistent motion language: incoming screens scale
//  up from slightly-small + fade in (like pulling a new console panel into
//  focus), while outgoing screens scale down and fade (receding into the
//  distance) instead of just sliding off. Applied uniformly via NavHost's
//  transition lambdas AND the full-screen Dialog used by the "Add/Edit
//  Connection" form, so every "open a new surface" moment in the app shares
//  the same feel.
//
//  PERF-FIX (July 2026): this used to combine THREE simultaneous animated
//  properties — scale + vertical slide + fade — over 420ms (enter) / 260ms
//  (exit), on *every* screen transition and every open/close of the
//  Add/Edit Connection form (the single most frequently opened surface in
//  the app). Each extra animated dimension is another value the compositor
//  has to interpolate and another transform matrix to recompute every frame
//  for the whole duration; stacking three of them, for nearly half a second,
//  made navigation and the connection form feel sluggish, especially with
//  other work (recomposition, the home screen's animated starfield) competing
//  for the same frame budget. The vertical-slide component is dropped — scale
//  + fade alone still reads clearly as the same "warp in / recede out" motion
//  — and both durations are shortened to match Material's guidance for
//  standard navigation transitions (~300ms/200ms), so surfaces open and close
//  noticeably snappier without losing the app's visual identity.
// ─────────────────────────────────────────────────────────────────────────────
object SpaceMotion {

    /** Fast start, long soft landing — used when something is arriving. */
    val WarpIn: CubicBezierEasing = CubicBezierEasing(0.16f, 0.78f, 0.14f, 1f)

    /** Soft start, fast departure — used when something is leaving. */
    val WarpOut: CubicBezierEasing = CubicBezierEasing(0.3f, 0f, 0.78f, 0.15f)

    const val DURATION_ENTER = 260
    const val DURATION_EXIT = 200

    // ── NavHost transitions (screen ↔ screen) ──────────────────────────────
    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        scaleIn(
            initialScale = 0.96f,
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) + fadeIn(
            animationSpec = tween(DURATION_ENTER, easing = LinearOutSlowInEasing)
        )
    }

    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        scaleOut(
            targetScale = 1.03f,
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) + fadeOut(animationSpec = tween(DURATION_EXIT, easing = WarpOut))
    }

    val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        scaleIn(
            initialScale = 1.03f,
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) + fadeIn(animationSpec = tween(DURATION_ENTER, easing = LinearOutSlowInEasing))
    }

    val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        scaleOut(
            targetScale = 0.96f,
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) + fadeOut(
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        )
    }

    // ── Full-screen Dialog transitions (Add/Edit Connection form) ─────────
    // This is the most frequently triggered transition in the app (opened on
    // every "Add"/"Edit" tap), so it gets the same lightened treatment as the
    // NavHost transitions above.
    val dialogEnter =
        scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(DURATION_ENTER, easing = WarpIn)
        ) + fadeIn(
            animationSpec = tween(DURATION_ENTER, easing = LinearOutSlowInEasing)
        )

    val dialogExit =
        scaleOut(
            targetScale = 0.96f,
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        ) + fadeOut(
            animationSpec = tween(DURATION_EXIT, easing = WarpOut)
        )

    /** Matches [dialogExit]'s duration so callers can delay real dismissal
     *  until the animation has actually finished playing. */
    val DIALOG_EXIT_MS: Long = DURATION_EXIT.toLong()
}
