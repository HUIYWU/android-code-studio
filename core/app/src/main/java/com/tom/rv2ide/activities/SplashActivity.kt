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

package com.tom.rv2ide.activities

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import com.tom.rv2ide.R
import com.tom.rv2ide.preferences.internal.GeneralPreferences

/** @author Akash Yadav */
class SplashActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    // Resolve the night mode BEFORE the theme/window is applied so the startup
    // window (windowSplashScreenBackground) matches the user's selected UI mode
    // instead of falling back to the light values/ resources. This removes the
    // blank white flash during app restart / theme switching.
    applyUserNightMode()

    super.onCreate(savedInstanceState)

    setContentView(R.layout.activity_splash)

    val icon = findViewById<ImageView>(R.id.splash_icon)
    val animation = icon.drawable as? AnimatedVectorDrawable
    if (animation == null) {
      openNextScreen()
      return
    }

    animation.registerAnimationCallback(
        object : Animatable2.AnimationCallback() {
          override fun onAnimationEnd(drawable: Drawable) {
            animation.unregisterAnimationCallback(this)
            openNextScreen()
          }
        }
    )

    icon.viewTreeObserver.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
          override fun onPreDraw(): Boolean {
            icon.viewTreeObserver.removeOnPreDrawListener(this)
            animation.start()
            return true
          }
        }
    )
  }

  private fun openNextScreen() {
    if (isFinishing) {
      return
    }

    startActivity(Intent(this, OnboardingActivity::class.java))
    finish()
  }

  private fun applyUserNightMode() {
    val target = when (GeneralPreferences.uiMode) {
      AppCompatDelegate.MODE_NIGHT_YES -> true
      AppCompatDelegate.MODE_NIGHT_NO -> false
      else ->
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    val currentNight =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    if (target != currentNight) {
      val targetMask =
          if (target) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
      val newConfig = Configuration(resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or targetMask
      }
      @Suppress("DEPRECATION")
      resources.updateConfiguration(newConfig, resources.displayMetrics)
    }
  }
}
