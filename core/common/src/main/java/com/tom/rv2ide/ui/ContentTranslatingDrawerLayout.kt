/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A [DrawerLayout] that translates its content when navigation drawers are opened or closed.
 *
 * On API 34+ this layout also owns the predictive-back gesture for the start drawer.
 * DrawerLayout itself registers an [OnBackInvokedCallback] which calls `closeDrawers()`;
 * on some devices that path produces no intermediate `onDrawerSlide()` frames, so the drawer
 * vanishes instantly while the translated content is still offset. To keep the two in sync this
 * class:
 *
 * 1. Locks the start drawer to `LOCK_MODE_LOCKED_OPEN` while it is open. DrawerLayout only
 *    registers its own back callback when the drawer is unlocked, so the parent callback is
 *    disabled instead of racing the custom one.
 * 2. Registers its own [OnBackAnimationCallback]. The gesture progress translates both the
 *    drawer and the content, and a cancelled gesture restores the open position.
 * 3. On `onBackInvoked()` a short [ValueAnimator] closes the remaining visual distance and then
 *    commits the closed state with `closeDrawer(drawerView, false)`. The parent is never asked
 *    to animate again, so a second close animation cannot start.
 * 4. Temporarily unlocks the drawer while a real touch is being intercepted, preserving
 *    swipe-to-close. The lock is restored by the drawer listener's `onDrawerClosed`.
 *
 * @author Akash Yadav
 */
class ContentTranslatingDrawerLayout : InterceptableDrawerLayout {

  constructor(context: Context) : super(context)

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

  constructor(
      context: Context,
      attrs: AttributeSet?,
      defStyleAttr: Int,
  ) : super(context, attrs, defStyleAttr)

  /**
   * The ID of the child view which will be translated when the navigation views are
   * expanded/collapsed.
   *
   * Set this value to `-1` to disable transition.
   */
  var childId: Int = -1

  /** The [TranslationBehavior] for the start navigation view. */
  var translationBehaviorStart: TranslationBehavior = TranslationBehavior.DEFAULT

  /** The [TranslationBehavior] for the end navigation view. */
  var translationBehaviorEnd: TranslationBehavior = TranslationBehavior.DEFAULT

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private var backDispatcher: OnBackInvokedDispatcher? = null

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private var backProgress = 0f

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private var backCloseAnimator: ValueAnimator? = null

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private val backAnimationCallback =
      object : OnBackAnimationCallback {
        override fun onBackStarted(backEvent: BackEvent) {
          backCloseAnimator?.cancel()
          backProgress = 0f
          findDrawerWithGravityCompat()?.let { drawerView ->
            applyBackProgress(drawerView, 0f)
          }
        }

        override fun onBackProgressed(backEvent: BackEvent) {
          backProgress = backEvent.progress.coerceIn(0f, 1f)
          findDrawerWithGravityCompat()?.let { drawerView ->
            applyBackProgress(drawerView, backProgress)
          }
        }

        override fun onBackCancelled() {
          backCloseAnimator?.cancel()
          backProgress = 0f
          findDrawerWithGravityCompat()?.let { drawerView ->
            resetBackTranslation(drawerView)
            applyContentTranslation(drawerView, 1f)
          }
        }

        override fun onBackInvoked() {
          findDrawerWithGravityCompat()?.let { drawerView ->
            animateBackClose(drawerView)
          }
        }
      }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun animateBackClose(drawerView: View) {
    backCloseAnimator?.cancel()

    val startProgress = backProgress.coerceIn(0f, 1f)
    var cancelled = false
    val animator = ValueAnimator.ofFloat(startProgress, 1f).apply {
      duration = (220L * (1f - startProgress).coerceAtLeast(0.35f)).toLong()
      interpolator = DecelerateInterpolator()
      addUpdateListener { animation ->
        applyBackProgress(drawerView, animation.animatedValue as Float)
      }
      addListener(
          object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
              cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
              if (!cancelled) {
                closeDrawer(drawerView, false)
              }
              if (backCloseAnimator === animation) {
                backCloseAnimator = null
              }
              backProgress = 0f
            }
          },
      )
    }
    backCloseAnimator = animator
    animator.start()
  }

  private fun findDrawerWithGravityCompat(): View? {
    for (index in 0 until childCount) {
      val child = getChildAt(index)
      val gravity = (child.layoutParams as? LayoutParams)?.gravity ?: continue
      val absoluteGravity = GravityCompat.getAbsoluteGravity(gravity, layoutDirection)
      if ((absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT) {
        return child
      }
    }
    return null
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun applyBackProgress(drawerView: View, progress: Float) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val drawerWidth = drawerView.width.toFloat()
    drawerView.translationX = -drawerWidth * clampedProgress
    applyContentTranslation(drawerView, 1f - clampedProgress)
    invalidate()
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun resetBackTranslation(drawerView: View) {
    drawerView.translationX = 0f
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun registerBackAnimationCallback() {
    if (backDispatcher != null || !isAttachedToWindow) {
      return
    }
    findOnBackInvokedDispatcher()?.let { dispatcher ->
      dispatcher.registerOnBackInvokedCallback(
          OnBackInvokedDispatcher.PRIORITY_OVERLAY,
          backAnimationCallback,
      )
      backDispatcher = dispatcher
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun unregisterBackAnimationCallback() {
    backDispatcher?.unregisterOnBackInvokedCallback(backAnimationCallback)
    backDispatcher = null
  }

  private fun applyContentTranslation(drawerView: View, slideOffset: Float) {
    if (childId == -1 || drawerView.width == 0) {
      return
    }

    val gravity = (drawerView.layoutParams as LayoutParams).gravity
    val view = findViewById<View>(childId) ?: return
    val absoluteGravity = GravityCompat.getAbsoluteGravity(gravity, layoutDirection)
    val isLeftDrawer = (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT
    val maxOffset =
        if (isLeftDrawer) translationBehaviorStart.maxOffset
        else translationBehaviorEnd.maxOffset
    val direction = if (isLeftDrawer) 1 else -1
    view.translationX = direction * (drawerView.width * slideOffset) * maxOffset
  }

  override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            isDrawerOpen(GravityCompat.START)
    ) {
      // Unlock only for a real touch gesture so DrawerLayout can continue to handle swipe-close.
      setDrawerLockMode(LOCK_MODE_UNLOCKED, GravityCompat.START)
      post {
        if (isDrawerOpen(GravityCompat.START)) {
          registerBackAnimationCallback()
        }
      }
    }
    return super.onInterceptTouchEvent(event)
  }

  private val mListener =
      object : SimpleDrawerListener() {
        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
          applyContentTranslation(drawerView, slideOffset)
        }

        override fun onDrawerOpened(drawerView: View) {
          // TODO(DRAWER-GRAVITY-EXPERIMENT): restrict start-drawer lock handling to the start drawer
          if (
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                  drawerView === findDrawerWithGravityCompat()
          ) {
            backCloseAnimator?.cancel()
            backProgress = 0f
            // Prevent DrawerLayout's own API 33 callback from racing the custom callback.
            setDrawerLockMode(LOCK_MODE_LOCKED_OPEN, GravityCompat.START)
            post { registerBackAnimationCallback() }
          }
        }

        override fun onDrawerClosed(drawerView: View) {
          // TODO(DRAWER-GRAVITY-EXPERIMENT): restrict start-drawer lock handling to the start drawer
          if (
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                  drawerView === findDrawerWithGravityCompat()
          ) {
            unregisterBackAnimationCallback()
            // The predictive-back callback temporarily translates the drawer itself. Clear that
            // visual offset only after DrawerLayout has committed the closed state.
            drawerView.translationX = 0f
            setDrawerLockMode(LOCK_MODE_UNLOCKED, GravityCompat.START)
          }
          applyContentTranslation(drawerView, 0f)
        }
      }

  init {
    addDrawerListener(mListener)
  }

  /** Translation behavior for content view of [ContentTranslatingDrawerLayout]. */
  enum class TranslationBehavior(val maxOffset: Float) {

    /**
     * The default translation behavior. This makes the child view translate partially according to
     * the slide offset of the
     * [NavigationView][com.google.android.material.navigation.NavigationView]
     */
    DEFAULT(0.2f),

    /**
     * Makes the child child view translate according to the slide offset of the
     * [NavigationView][com.google.android.material.navigation.NavigationView]. The translation
     * offset is always equal to the slide offset in this behavior.
     */
    FULL(0.95f),
  }
}
