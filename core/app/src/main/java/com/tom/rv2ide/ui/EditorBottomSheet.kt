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

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.annotation.GravityInt
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.Insets
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.transition.TransitionManager
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.ThreadUtils.runOnUiThread
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayout.Tab
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.DiagnosticsAdapter
import com.tom.rv2ide.adapters.EditorBottomSheetTabAdapter
import com.tom.rv2ide.adapters.SearchListAdapter
import com.tom.rv2ide.databinding.LayoutEditorBottomSheetBinding
import com.tom.rv2ide.fragments.output.ShareableOutputFragment
import com.tom.rv2ide.models.LogLine
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.tasks.TaskExecutor.CallbackWithError
import com.tom.rv2ide.tasks.TaskExecutor.executeAsync
import com.tom.rv2ide.tasks.TaskExecutor.executeAsyncProvideError
import com.tom.rv2ide.utils.IntentUtils.shareFile
import com.tom.rv2ide.utils.Symbols.forFile
import com.tom.rv2ide.utils.flashError
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Callable
import kotlin.math.max
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import eightbitlab.com.blurview.RenderScriptBlur
import android.view.ViewOutlineProvider
import eightbitlab.com.blurview.BlurTarget

/**
 * Bottom sheet shown in editor activity.
 *
 * @author Akash Yadav
 */
class EditorBottomSheet
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr, defStyleRes) {

  private val collapsedHeight: Float by lazy {
    val localContext = getContext() ?: return@lazy 0f
    localContext.resources.getDimension(R.dimen.editor_sheet_collapsed_height)
  }
  private val quickInputExpandedHeight: Int by lazy {
    SizeUtils.dp2px(136f)
  }
  private val behavior: BottomSheetBehavior<EditorBottomSheet> by lazy {
    BottomSheetBehavior.from(this).apply {
      isFitToContents = false
      skipCollapsed = true
    }
  }

  @JvmField var binding: LayoutEditorBottomSheetBinding
  val pagerAdapter: EditorBottomSheetTabAdapter
  private var anchorOffset = 0
  private var currentSheetOffset = 0f
  private var isImeVisible = false
  private var quickInputContainerAnimator: ValueAnimator? = null
  private var basicContainerChild = CHILD_HEADER
  private var windowInsets: Insets? = null
  private var currentSymbolInputEditor: CodeEditorView? = null
  private var imeLogLayoutPass = 0
  private var lastImeBottom = 0
  private var imeAnimEpoch = 0
  private var imeAnimPhase = IME_PHASE_IDLE
  private var imeTraceArmedAt = 0L
  // TODO(IME-FIX-EXPERIMENT): Collapsed offset follows the IME by changing Behavior geometry.
  private var imeOwnerInstalled = false
  private var imeOwnerActive = false
  private var imeOwnerHalfActive = false
  private var imeOwnerImeRoutedToSidebar = false
  private var imeOwnerSidebarReleasePending = false
  private var imeOwnerStartedWhileSidebar = false
  private var imeOwnerMdcStartY = 0
  private var imeOwnerMdcStartTranslation = 0
  private var imeOwnerBasePeekHeight = 0
  private var imeOwnerLastPeekHeight = 0
  private var imeOwnerLastHalfRatio = 0.5f

  private val insetBottom: Int
    get() = if (isImeVisible) 0 else windowInsets?.bottom ?: 0

  var requestShowQuickInputOverlay: (() -> Unit)? = null
  var requestHideQuickInputOverlay: (() -> Unit)? = null
  var onQuickInputActionClick: ((String) -> Unit)? = null
  private var quickInputOverlayActive = false
  private var headerExternallyHidden = false


  private enum class TopContainerMode {
    BASIC,
    SYMBOL_INPUT,
    HIDDEN,
  }

  companion object {

    private val log = LoggerFactory.getLogger(EditorBottomSheet::class.java)
    private const val START_HIDE_CONTAINER_AT_OFFSET = 0.82f
    private const val HIDE_CONTAINER_AT_OFFSET = 0.92f

    private const val IME_PHASE_IDLE = 0
    private const val IME_PHASE_START = 1
    private const val IME_PHASE_PROGRESS = 2
    private const val IME_PHASE_END = 3

    const val CHILD_HEADER = 0
    const val CHILD_SYMBOL_INPUT = 1
    const val CHILD_ACTION = 2
  }

  private fun canShareOutput(fragment: Fragment?): Boolean {
    return fragment is ShareableOutputFragment
  }

  private fun installImeGeoTrace() {
    post {
      val host = (parent as? View) ?: return@post
      ViewCompat.setWindowInsetsAnimationCallback(
          host,
          object : WindowInsetsAnimationCompat.Callback(
              WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
          ) {
            private fun isIme(animation: WindowInsetsAnimationCompat): Boolean {
              return (animation.typeMask and WindowInsetsCompat.Type.ime()) != 0
            }

            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
              if (!isIme(animation)) return
              imeAnimEpoch++
            }

            override fun onStart(
                animation: WindowInsetsAnimationCompat,
                bounds: WindowInsetsAnimationCompat.BoundsCompat,
            ): WindowInsetsAnimationCompat.BoundsCompat {
              if (!isIme(animation)) return bounds
              imeAnimPhase = IME_PHASE_START
              imeTraceArmedAt = SystemClock.uptimeMillis()
              logImeGeoTrace("start", animation.interpolatedFraction, null)
              return bounds
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: List<WindowInsetsAnimationCompat>,
            ): WindowInsetsCompat {
              val running =
                  runningAnimations.lastOrNull { animation ->
                    (animation.typeMask and WindowInsetsCompat.Type.ime()) != 0
                  }
                      ?: return insets
              imeAnimPhase = IME_PHASE_PROGRESS
              imeTraceArmedAt = SystemClock.uptimeMillis()
              logImeGeoTrace(
                  "progress",
                  running.interpolatedFraction,
                  insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
              )
              return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
              if (!isIme(animation)) return
              imeAnimPhase = IME_PHASE_END
              imeTraceArmedAt = SystemClock.uptimeMillis()
              logImeGeoTrace("end", animation.interpolatedFraction, null)
              imeAnimPhase = IME_PHASE_IDLE
            }
          }
      )
    }
  }

  private fun logImeGeoTrace(event: String, fraction: Float, imeBottomOverride: Int?) {
    if (imeAnimEpoch == 0) return
    val rootInsets = rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
    val imeBottom =
        imeBottomOverride ?: (rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
    val sheetLocation = IntArray(2)
    val headerLocation = IntArray(2)
    val pagerLocation = IntArray(2)
    val shellLocation = IntArray(2)
    getLocationOnScreen(sheetLocation)
    binding.headerContainer.getLocationOnScreen(headerLocation)
    binding.pager.getLocationOnScreen(pagerLocation)
    binding.quickInputShell.getLocationOnScreen(shellLocation)
    val imeDelta = imeBottom - lastImeBottom
    lastImeBottom = imeBottom
    log.warn(
        "[EditorImeTrace] anim epoch=$imeAnimEpoch event=$event phase=$imeAnimPhase " +
            "fraction=$fraction imeBottom=$imeBottom imeDelta=$imeDelta state=${behavior.state} " +
            "sheetTop=${sheetLocation[1]} sheetBottom=${sheetLocation[1] + height} sheetH=$height " +
            "translationY=$translationY " +
            "headerTop=${headerLocation[1]} headerBottom=${headerLocation[1] + binding.headerContainer.height} " +
            "headerH=${binding.headerContainer.height} " +
            "pagerTop=${pagerLocation[1]} pagerBottom=${pagerLocation[1] + binding.pager.height} " +
            "pagerH=${binding.pager.height} " +
            "shellTop=${shellLocation[1]} shellH=${binding.quickInputShell.height} " +
            "slide=$currentSheetOffset"
    )
  }

  // TODO(IME-FIX-EXPERIMENT): Use BottomSheetBehavior geometry for collapsed IME motion.
  private fun installImeSheetOwner() {
    ViewCompat.setWindowInsetsAnimationCallback(
        this,
        object : WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        ) {
          private fun isIme(animation: WindowInsetsAnimationCompat): Boolean {
            return (animation.typeMask and WindowInsetsCompat.Type.ime()) != 0
          }

          override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (!isIme(animation) || imeOwnerImeRoutedToSidebar) return
            val location = IntArray(2)
            getLocationOnScreen(location)
            imeOwnerMdcStartY = location[1]
          }

          override fun onStart(
              animation: WindowInsetsAnimationCompat,
              bounds: WindowInsetsAnimationCompat.BoundsCompat,
          ): WindowInsetsAnimationCompat.BoundsCompat {
            if (!isIme(animation)) return bounds
            if (imeOwnerImeRoutedToSidebar) {
              imeOwnerActive = false
              imeOwnerHalfActive = false
              imeOwnerStartedWhileSidebar = true
              return bounds
            }
            imeOwnerStartedWhileSidebar = false
            imeOwnerActive = behavior.state == BottomSheetBehavior.STATE_COLLAPSED
            imeOwnerHalfActive = behavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED
            imeOwnerBasePeekHeight = collapsedHeight.roundToInt()
            imeOwnerLastPeekHeight = imeOwnerBasePeekHeight
            imeOwnerLastHalfRatio = behavior.halfExpandedRatio
            if (imeOwnerActive) {
              translationY = 0f
            } else if (imeOwnerHalfActive) {
              translationY = 0f
            } else {
              val location = IntArray(2)
              getLocationOnScreen(location)
              imeOwnerMdcStartTranslation = imeOwnerMdcStartY - location[1]
              translationY = imeOwnerMdcStartTranslation.toFloat()
            }
            return bounds
          }

          override fun onProgress(
              insets: WindowInsetsCompat,
              runningAnimations: List<WindowInsetsAnimationCompat>,
          ): WindowInsetsCompat {
            val running = runningAnimations.lastOrNull { isIme(it) } ?: return insets
            if (imeOwnerImeRoutedToSidebar || imeOwnerStartedWhileSidebar) return insets
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeOffset = max(imeBottom - systemBottom, 0)
            val targetPeekHeight = imeOwnerBasePeekHeight + imeOffset
            if (targetPeekHeight != imeOwnerLastPeekHeight) {
              imeOwnerLastPeekHeight = targetPeekHeight
              behavior.peekHeight = targetPeekHeight
            }
            val dragParent = (parent as? View)
            val targetRatio =
                if (dragParent != null && dragParent.height > 0) {
                  0.5f + imeOffset / (2f * dragParent.height)
                } else {
                  imeOwnerLastHalfRatio
                }
            if (targetRatio != imeOwnerLastHalfRatio) {
              imeOwnerLastHalfRatio = targetRatio
              behavior.halfExpandedRatio = targetRatio
              if (imeOwnerHalfActive) {
                dragParent?.requestLayout()
              }
            }
            if (imeOwnerActive) {
              log.warn(
                  "[EditorImeTrace] owner fraction=${running.interpolatedFraction} " +
                      "imeBottom=$imeBottom systemBottom=$systemBottom " +
                      "peekHeight=$targetPeekHeight ratio=$targetRatio"
              )
            } else if (imeOwnerHalfActive) {
              log.warn(
                  "[EditorImeTrace] half fraction=${running.interpolatedFraction} " +
                      "imeBottom=$imeBottom systemBottom=$systemBottom " +
                      "peekHeight=$targetPeekHeight ratio=$targetRatio"
              )
            } else {
              val fraction = running.interpolatedFraction
              translationY =
                  imeOwnerMdcStartTranslation.toFloat() * (1f - fraction)
            }
            return insets
          }

          override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (!isIme(animation)) return
            imeOwnerStartedWhileSidebar = false
            if (imeOwnerSidebarReleasePending) {
              imeOwnerSidebarReleasePending = false
              imeOwnerImeRoutedToSidebar = false
              return
            }
            if (imeOwnerImeRoutedToSidebar) return
            if (imeOwnerHalfActive) {
              imeOwnerHalfActive = false
              imeOwnerLastHalfRatio = 0.5f
            }
            if (!imeOwnerActive) {
              translationY = 0f
            }
            imeOwnerActive = false
          }
        }
    )
  }

  private fun initialize(context: FragmentActivity) {
    val mediator =
        TabLayoutMediator(binding.tabs, binding.pager, true, true) { tab, position ->
          tab.text = pagerAdapter.getTitle(position)
        }

    mediator.attach()
    binding.pager.isUserInputEnabled = false
    binding.pager.offscreenPageLimit = pagerAdapter.itemCount - 1 // Do not remove any views

    binding.root.viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                setupBlurEffect()
            }
        }
    )
    
    binding.tabs.addOnTabSelectedListener(
        object : OnTabSelectedListener {
          override fun onTabSelected(tab: Tab) {
            val fragment: Fragment = pagerAdapter.getFragmentAtIndex(tab.position)
            if (canShareOutput(fragment)) {
              binding.clearFab.show()
              binding.shareOutputFab.show()
            } else {
              binding.clearFab.hide()
              binding.shareOutputFab.hide()
            }

            applyTopContainerState()
          }
    
          override fun onTabUnselected(tab: Tab) {}
    
          override fun onTabReselected(tab: Tab) {}
        }
    )

    binding.shareOutputFab.setOnClickListener {
      val fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)

      if (fragment !is ShareableOutputFragment) {
        log.error("Unknown fragment: {}", fragment)
        return@setOnClickListener
      }

      val filename = fragment.getFilename()

      @Suppress("DEPRECATION")
      val progress =
          android.app.ProgressDialog.show(context, null, context.getString(string.please_wait))
      executeAsync(fragment::getContent) {
        progress.dismiss()
        shareText(it, filename)
      }
    }

    TooltipCompat.setTooltipText(binding.clearFab, context.getString(string.title_clear_output))
    binding.clearFab.setOnClickListener {
      val fragment: Fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)
      if (fragment !is ShareableOutputFragment) {
        log.error("Unknown fragment: {}", fragment)
        return@setOnClickListener
      }
      (fragment as ShareableOutputFragment).clearOutput()
    }

    binding.headerContainer.setOnClickListener {
      if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
      }
    }

    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      this.windowInsets = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
      val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      val imeDelta = ime.bottom - lastImeBottom
      lastImeBottom = ime.bottom
      log.warn(
          "[EditorImeTrace] sheetInsets imeBottom=${ime.bottom} imeDelta=$imeDelta " +
              "phase=$imeAnimPhase epoch=$imeAnimEpoch " +
              "systemBottom=${bars.bottom} gestureBottom=${windowInsets?.bottom ?: 0} " +
              "imeVisible=$isImeVisible translationY=$translationY state=${behavior.state} " +
              "top=$top height=$height paddingBottom=$paddingBottom"
      )
      insets
    }
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    // TODO(IME-FIX-EXPERIMENT): Preserve MDC motion outside collapsed state; use Behavior geometry when collapsed.
    if (!imeOwnerInstalled) {
      imeOwnerInstalled = true
      installImeSheetOwner()
    }
    if (imeAnimPhase != IME_PHASE_IDLE ||
        SystemClock.uptimeMillis() - imeTraceArmedAt < 500L
    ) {
      imeLogLayoutPass++
      logImeGeoTrace("layout#$imeLogLayoutPass", -1f, null)
    }
  }

  init {
    if (context !is FragmentActivity) {
      throw IllegalArgumentException("EditorBottomSheet must be set up with a FragmentActivity")
    }

    val inflater = LayoutInflater.from(context)
    binding = LayoutEditorBottomSheetBinding.inflate(inflater)
    pagerAdapter = EditorBottomSheetTabAdapter(context)
    binding.pager.adapter = pagerAdapter
    clipChildren = false
    clipToPadding = false
    binding.root.clipChildren = false
    binding.root.clipToPadding = false
    binding.quickInputShell.clipChildren = false
    binding.quickInputShell.clipToPadding = false
    binding.cardView.scaleX = 0.9f
    binding.cardView.scaleY = 0.9f
    binding.cardView.clipToOutline = true
    binding.blurView.clipToOutline = false
    // This view owns the in-container quick input state.
    // DOWN expansion stays in this container so the bottom sheet can resize together with the panel.
    // UP expansion is delegated to a separate overlay, so this view acts only as the trigger, state source,
    // and geometry anchor for that path.
    binding.symbolInput.bindToggleButton(binding.quickInputToggle)
    binding.symbolInput.setActionClickListener { actionId -> onQuickInputActionClick?.invoke(actionId) }
    binding.symbolInput.setExpansionChangeListener { expanded, direction ->
      applyQuickInputExpansion(expanded, direction)
      if (direction == SymbolInputView.ExpandDirection.DOWN) {
        quickInputOverlayActive = false
      }
      binding.quickInputToggle.rotation =
          if ((expanded && direction == SymbolInputView.ExpandDirection.DOWN) ||
              quickInputOverlayActive) {
            90f
          } else {
            -90f
          }
    }
    binding.quickInputToggle.setOnClickListener {
      if (!EditorPreferences.quickInputExpansionEnabled ||
          resolveTopContainerMode() != TopContainerMode.SYMBOL_INPUT) {
        return@setOnClickListener
      }
      if (shouldUseDownExpansion()) {
        // Keep DOWN expansion inside the current container.
        // The overlay must be hidden first so only one expanded surface is active at a time.
        requestHideQuickInputOverlay?.invoke()
        binding.symbolInput.setExpandDirection(SymbolInputView.ExpandDirection.DOWN)
        binding.symbolInput.toggleExpanded()
        setQuickInputOverlayActive(false)
      } else {
        // Do not render UP overflow inside the header container.
        // Collapse the local view and let the overlay own the expanded surface.
        binding.symbolInput.collapse()
        if (quickInputOverlayActive) {
          requestHideQuickInputOverlay?.invoke()
        } else {
          requestShowQuickInputOverlay?.invoke()
        }
      }
    }

    removeAllViews()
    addView(binding.root)

    initialize(context)
    installImeGeoTrace()
  }

  // TODO(IME-FIX-EXPERIMENT): The activity routes sidebar IME separately from the editor slots.
  fun isImeRoutedToSidebar(): Boolean = imeOwnerImeRoutedToSidebar

  fun setImeRoutedToSidebar(routed: Boolean) {
    if (routed) {
      imeOwnerSidebarReleasePending = false
      imeOwnerImeRoutedToSidebar = true
    } else if (imeOwnerImeRoutedToSidebar) {
      imeOwnerSidebarReleasePending = true
      return
    } else {
      imeOwnerImeRoutedToSidebar = false
    }
    if (routed) {
      imeOwnerActive = false
      if (imeOwnerLastPeekHeight != imeOwnerBasePeekHeight) {
        imeOwnerLastPeekHeight = imeOwnerBasePeekHeight
        behavior.peekHeight = imeOwnerBasePeekHeight
      }
      if (imeOwnerHalfActive) {
        imeOwnerHalfActive = false
        imeOwnerLastHalfRatio = 0.5f
        if (behavior.halfExpandedRatio != 0.5f) {
          behavior.halfExpandedRatio = 0.5f
          (parent as? View)?.requestLayout()
        }
      }
    }
  }

  fun setExternallyHeaderHidden(hidden: Boolean) {
    if (headerExternallyHidden == hidden) return
    headerExternallyHidden = hidden
    if (hidden) {
      // Hide only the visible surface. The top container geometry (quickInputShell/cardView/
      // headerContainer heights) must stay intact so the tabs/pager never shift under the
      // search replacement host; only the expand toggle is suppressed.
      binding.symbolInput.collapse()
      binding.quickInputToggle.visibility = View.GONE
      binding.quickInputToggle.translationY = 0f
    } else {
      applyTopContainerState()
    }
  }

  /** Set whether the input method is visible. */
  fun setImeVisible(isVisible: Boolean) {
    isImeVisible = isVisible
    behavior.isGestureInsetBottomIgnored = isVisible
  }

  fun setOffsetAnchor(view: View, excludedChild: View? = null) {
    val listener =
        object : ViewTreeObserver.OnGlobalLayoutListener {
          override fun onGlobalLayout() {
            view.viewTreeObserver.removeOnGlobalLayoutListener(this)

            val excludedHeight =
                if (excludedChild != null && excludedChild.visibility != View.GONE) {
                  excludedChild.height
                } else {
                  0
                }
            anchorOffset = (view.height - excludedHeight) + SizeUtils.dp2px(1f)

            behavior.peekHeight = collapsedHeight.roundToInt()
            behavior.expandedOffset = anchorOffset
            behavior.isGestureInsetBottomIgnored = isImeVisible

            binding.root.updatePadding(bottom = anchorOffset + insetBottom)
            binding.headerContainer.apply {
              updatePaddingRelative(bottom = paddingBottom + insetBottom)
              updateLayoutParams<ViewGroup.LayoutParams> {
                height = (collapsedHeight + insetBottom).roundToInt()
              }
            }
          }
        }

    view.viewTreeObserver.addOnGlobalLayoutListener(listener)
  }

  fun onStateChanged(newState: Int) {
    currentSheetOffset =
        when (newState) {
          BottomSheetBehavior.STATE_EXPANDED -> 1f
          BottomSheetBehavior.STATE_COLLAPSED -> 0f
          else -> currentSheetOffset
        }
    log.warn(
        "[EditorImeTrace] behaviorState state=$newState offset=$currentSheetOffset " +
            "imeVisible=$isImeVisible phase=$imeAnimPhase epoch=$imeAnimEpoch " +
            "top=$top height=$height translationY=$translationY"
    )
    applyTopContainerState(animated = true)
  }

  fun onSlide(sheetOffset: Float) {
    currentSheetOffset = sheetOffset
    if (imeAnimPhase != IME_PHASE_IDLE) {
      log.warn(
          "[EditorImeTrace] slideDuringIme offset=$sheetOffset phase=$imeAnimPhase " +
              "imeVisible=$isImeVisible top=$top height=$height translationY=$translationY"
      )
    }
    updateQuickInputExpandDirection()
    binding.symbolInput.collapse()
    binding.headerContainer.updatePaddingRelative(bottom = 0)
    applyTopContainerState()
  }

  fun showChild(index: Int) {
    if (index != CHILD_SYMBOL_INPUT) {
      basicContainerChild = index
    }
    applyTopContainerState()
  }

  private fun updateQuickInputExpandDirection() {
    val direction =
        if (shouldUseDownExpansion()) {
          SymbolInputView.ExpandDirection.DOWN
        } else {
          SymbolInputView.ExpandDirection.UP
        }
    binding.symbolInput.setExpandDirection(direction)
  }

  private fun shouldExpandQuickInputDown(): Boolean {
    return currentSheetOffset > 0.08f && currentSheetOffset < HIDE_CONTAINER_AT_OFFSET
  }

  private fun shouldUseDownExpansion(): Boolean {
    return shouldExpandQuickInputDown()
  }

  private fun shouldSuppressSymbolInputForTerminal(): Boolean {
    return isTerminalTabSelected() && shouldUseDownExpansion()
  }

  private fun resolveTopContainerMode(): TopContainerMode {
    return if (shouldHideTopContainer()) {
      TopContainerMode.HIDDEN
    } else if (isImeVisible && !shouldSuppressSymbolInputForTerminal()) {
      TopContainerMode.SYMBOL_INPUT
    } else {
      TopContainerMode.BASIC
    }
  }

  private fun shouldHideTopContainer(): Boolean {
    return currentSheetOffset >= HIDE_CONTAINER_AT_OFFSET ||
        behavior.state == BottomSheetBehavior.STATE_EXPANDED
  }

  private fun topContainerVisibilityProgress(): Float {
    if (currentSheetOffset <= START_HIDE_CONTAINER_AT_OFFSET) {
      return 1f
    }
    if (currentSheetOffset >= HIDE_CONTAINER_AT_OFFSET) {
      return 0f
    }
    val range = HIDE_CONTAINER_AT_OFFSET - START_HIDE_CONTAINER_AT_OFFSET
    return (1f - ((currentSheetOffset - START_HIDE_CONTAINER_AT_OFFSET) / range)).coerceIn(0f, 1f)
  }

  private fun currentTopContainerHeight(): Int {
    return (collapsedHeight * topContainerVisibilityProgress()).roundToInt().coerceAtLeast(0)
  }

  private fun applyTopContainerState(animated: Boolean = false) {
    if (animated) {
      TransitionManager.beginDelayedTransition(
          binding.root,
          MaterialSharedAxis(MaterialSharedAxis.Y, false),
      )
    }
    when (resolveTopContainerMode()) {
      TopContainerMode.HIDDEN -> hideTopContainer()
      TopContainerMode.SYMBOL_INPUT -> showSymbolInputContainer()
      TopContainerMode.BASIC -> showBasicContainer()
    }
  }

  private fun setTopContainerHeight(height: Int) {
    binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
      this.height = height
    }
    binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
      this.height = height
      gravity = android.view.Gravity.TOP
    }
    binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
      this.height = height
    }
  }

  private fun hideTopContainer() {
    binding.symbolInput.collapse()
    binding.quickInputShell.visibility = View.VISIBLE
    binding.quickInputShell.alpha = 0f
    binding.quickInputShell.isEnabled = false
    binding.headerContainer.visibility = View.INVISIBLE
    binding.quickInputLeadingSpace.visibility = View.GONE
    binding.quickInputToggle.visibility = View.GONE
    binding.cardView.translationY = 0f
    binding.quickInputToggle.translationY = 0f
    setTopContainerHeight(0)
  }

  private fun showBasicContainer() {
    val height = currentTopContainerHeight()
    val progress = topContainerVisibilityProgress()
    binding.symbolInput.collapse()
    binding.quickInputShell.visibility = View.VISIBLE
    binding.quickInputShell.alpha = progress
    binding.quickInputShell.isEnabled = true
    binding.headerContainer.visibility = View.VISIBLE
    binding.headerContainer.displayedChild = basicContainerChild
    binding.quickInputLeadingSpace.visibility = View.GONE
    binding.quickInputToggle.visibility = View.GONE
    binding.cardView.scaleX = 0.9f
    binding.cardView.scaleY = 0.9f
    binding.cardView.translationY = 0f
    binding.quickInputToggle.translationY = 0f
    if (headerExternallyHidden) {
      binding.headerContainer.visibility = View.INVISIBLE
    }
    setTopContainerHeight(height)
  }

  private fun showSymbolInputContainer() {
    val height = currentTopContainerHeight()
    val progress = topContainerVisibilityProgress()
    val expansionEnabled = EditorPreferences.quickInputExpansionEnabled
    if (!expansionEnabled) {
      binding.symbolInput.collapse()
      requestHideQuickInputOverlay?.invoke()
      setQuickInputOverlayActive(false)
    }
    binding.quickInputShell.visibility = View.VISIBLE
    binding.quickInputShell.alpha = progress
    binding.quickInputShell.isEnabled = true
    binding.headerContainer.visibility = View.VISIBLE
    binding.headerContainer.displayedChild = CHILD_SYMBOL_INPUT
    // Keep the left spacer when the toggle is hidden so the collapsed symbol card retains
    // the same centered width as the expandable layout.
    binding.quickInputLeadingSpace.visibility = View.VISIBLE
    // Preserve the toggle's width when expansion is disabled so the symbol card stays centered.
    binding.quickInputToggle.visibility = if (expansionEnabled) View.VISIBLE else View.INVISIBLE
    binding.cardView.scaleX = 1f
    binding.cardView.scaleY = 1f
    updateQuickInputExpandDirection()
    if (quickInputOverlayActive) {
      binding.quickInputToggle.rotation = 90f
    }
    if (headerExternallyHidden) {
      // The search replacement host owns the top area; keep the header and the expand toggle
      // hidden even when IME state would normally select the symbol input container.
      binding.headerContainer.visibility = View.INVISIBLE
      binding.quickInputToggle.visibility = View.GONE
    }
    setTopContainerHeight(height)
  }

  // Applies the container-side expansion model.
  // The panel surface is defined by quickInputShell / cardView / headerContainer, not by SymbolInputView alone.
  // This keeps size, gravity, and sheet interaction consistent for the in-container path.
  // For DOWN, both expand and collapse must stay on the animated container path; gating by `expanded`
  // would make collapse skip the animator and snap back to the collapsed height.
  private fun applyQuickInputExpansion(
      expanded: Boolean,
      direction: SymbolInputView.ExpandDirection,
  ) {
    val collapsed = collapsedHeight.roundToInt()
    val targetHeight = if (expanded) quickInputExpandedHeight else collapsed
    val expandUp = expanded && direction == SymbolInputView.ExpandDirection.UP
    // DOWN path owns both expand and collapse; `expandDown` is only used to pick the expanded target height.
    val useDownPath = direction == SymbolInputView.ExpandDirection.DOWN
    val expandDown = expanded && useDownPath
    val shellTargetHeight = if (expandDown) targetHeight else collapsed

    quickInputContainerAnimator?.cancel()
    binding.quickInputShell.bringToFront()
    binding.cardView.translationY = 0f
    binding.quickInputToggle.translationY = 0f
    binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
      gravity = if (expandUp) android.view.Gravity.BOTTOM else android.view.Gravity.TOP
    }

    if (!useDownPath) {
      binding.symbolInput.setContentExpanded(expanded)
      binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
        height = shellTargetHeight
      }
      binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
        height = targetHeight
      }
      binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
        height = targetHeight
      }
      return
    }
    val startCardHeight = binding.cardView.height.takeIf { it > 0 } ?: collapsed
    val startShellHeight = binding.quickInputShell.height.takeIf { it > 0 } ?: collapsed
    if (!expanded) {
      binding.symbolInput.setContentTransitionProgress(1f)
    }

    quickInputContainerAnimator = ValueAnimator.ofInt(startCardHeight, targetHeight).apply {
      duration = 250
      interpolator = DecelerateInterpolator(1.5f)
      addUpdateListener { animator ->
        val progress = animator.animatedFraction.coerceIn(0f, 1f)
        val animatedHeight = (startCardHeight + ((targetHeight - startCardHeight) * progress)).roundToInt()

        val shellAnimatedHeight = (startShellHeight + ((shellTargetHeight - startShellHeight) * progress)).roundToInt()
        binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
          height = shellAnimatedHeight
        }
        binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
          height = animatedHeight
        }
        binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
          height = animatedHeight
        }

        val contentProgress = if (expanded) {
          ((progress - 0.2f) / 0.55f).coerceIn(0f, 1f)
        } else {
          (1f - (progress / 0.8f)).coerceIn(0f, 1f)
        }
        binding.symbolInput.setContentTransitionProgress(contentProgress)
      }
      doOnEnd {
        binding.symbolInput.setContentExpanded(expanded)

        binding.quickInputShell.updateLayoutParams<ViewGroup.LayoutParams> {
          height = shellTargetHeight
        }
        binding.cardView.updateLayoutParams<LinearLayout.LayoutParams> {
          height = targetHeight
        }
        binding.headerContainer.updateLayoutParams<ViewGroup.LayoutParams> {
          height = targetHeight
        }
        quickInputContainerAnimator = null
      }
      start()
    }
  }

  fun setActionText(text: CharSequence) {
    binding.bottomAction.actionText.text = text
  }

  fun setActionProgress(progress: Int) {
    binding.bottomAction.progress.setProgressCompat(progress, true)
  }

  fun appendApkLog(line: io.github.mohammedbaqernull.logger.model.LogEntry) {
    pagerAdapter.logFragment?.appendLogToEditor(line)
  }

  fun appendBuildOut(str: String?) {
    pagerAdapter.buildOutputFragment?.appendOutput(str)
  }

  fun clearBuildOutput() {
    pagerAdapter.buildOutputFragment?.clearOutput()
  }

  fun handleDiagnosticsResultVisibility(errorVisible: Boolean) {
    runOnUiThread { pagerAdapter.diagnosticsFragment?.isEmpty = errorVisible }
  }

  fun handleSearchResultVisibility(errorVisible: Boolean) {
    runOnUiThread { pagerAdapter.searchResultFragment?.isEmpty = errorVisible }
  }

  fun setDiagnosticsAdapter(adapter: DiagnosticsAdapter) {
    runOnUiThread { pagerAdapter.diagnosticsFragment?.setAdapter(adapter) }
  }

  fun setSearchResultAdapter(adapter: SearchListAdapter) {
    runOnUiThread { pagerAdapter.searchResultFragment?.setAdapter(adapter) }
  }
  fun refreshSymbolInput(editor: CodeEditorView) {
    val ideEditor = editor.editor ?: return
    currentSymbolInputEditor = editor
    binding.symbolInput.refresh(ideEditor, editor.file, forFile(editor.file))
  }

  fun getCurrentQuickInputEditor(): CodeEditorView? = currentSymbolInputEditor

  fun getQuickInputAnchorView(): View = binding.cardView

  fun setQuickInputOverlayActive(active: Boolean) {
    quickInputOverlayActive = active
    binding.quickInputToggle.isEnabled = true
    binding.quickInputToggle.alpha = 1f
    if (active) {
      binding.quickInputToggle.rotation = 90f
    } else if (!binding.symbolInput.isExpanded) {
      binding.quickInputToggle.rotation = -90f
    }
    binding.cardView.alpha = if (active) 0f else 1f
  }

  fun setQuickInputOverlayHandoffProgress(progress: Float) {
    binding.cardView.alpha = progress.coerceIn(0f, 1f)
  }

  fun isTerminalTabSelected(): Boolean {
    val fragment = pagerAdapter.getFragmentAtIndex(binding.tabs.selectedTabPosition)
    return fragment.javaClass.simpleName.contains("Terminal", ignoreCase = true)
  }

  fun onSoftInputChanged(isVisible: Boolean) {
    binding.symbolInput.endItemAnimations()

    isImeVisible = isVisible
    if (!isImeVisible) {
      setQuickInputOverlayActive(false)
    }
    applyTopContainerState(animated = true)
  }

  
  fun setStatus(text: CharSequence, @GravityInt gravity: Int) {
    runOnUiThread {
      binding.buildStatus.let {
        it.statusText.gravity = gravity
        it.statusText.text = text
      }
    }
  }

  private fun shareFile(file: File) {
    shareFile(context, file, "text/plain")
  }

  @Suppress("DEPRECATION")
  private fun shareText(text: String?, type: String) {
    if (text == null || TextUtils.isEmpty(text)) {
      flashError(context.getString(string.msg_output_text_extraction_failed))
      return
    }
    val pd =
        android.app.ProgressDialog.show(
            context,
            null,
            context.getString(string.please_wait),
            true,
            false,
        )
    executeAsyncProvideError(
        Callable { writeTempFile(text, type) },
        CallbackWithError<File> { result: File?, error: Throwable? ->
          pd.dismiss()
          if (result == null || error != null) {
            log.warn("Unable to share output", error)
            return@CallbackWithError
          }
          shareFile(result)
        },
    )
  }


  private fun setupBlurEffect() {
      binding.blurView.viewTreeObserver.addOnGlobalLayoutListener(
          object : ViewTreeObserver.OnGlobalLayoutListener {
              override fun onGlobalLayout() {
                  binding.blurView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                  
                  val activity = context as? Activity ?: return
                  
                  val blurTarget = activity.findViewById<BlurTarget>(R.id.blurTarget)
                  
                  if (blurTarget == null) {
                      return
                  }
                  
                  try {
                      binding.blurView.setupWith(
                          blurTarget,
                          RenderScriptBlur(context),
                          40f,
                          true
                      )
                      binding.blurView.setOutlineProvider(ViewOutlineProvider.BACKGROUND)
                      binding.blurView.setClipToOutline(false)

                  } catch (e: Exception) {
                      log.error("Blur setup failed", e)
                  }
              }
          }
      )
  }

  private fun writeTempFile(text: String, type: String): File {
    // use a common name to avoid multiple files
    val file: Path = context.filesDir.toPath().resolve("$type.txt")
    try {
      if (Files.exists(file)) {
        Files.delete(file)
      }
      Files.write(file, text.toByteArray(StandardCharsets.UTF_8), CREATE_NEW, WRITE)
    } catch (e: IOException) {
      log.error("Unable to write output to file", e)
    }
    return file.toFile()
  }
}
