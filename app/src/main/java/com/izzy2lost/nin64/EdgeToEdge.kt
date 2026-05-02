package com.izzy2lost.nin64

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

@Suppress("DEPRECATION")
fun AppCompatActivity.enableNin64EdgeToEdge(immersive: Boolean = false) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    val lightSystemBars = !immersive &&
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightSystemBars
        isAppearanceLightNavigationBars = lightSystemBars
        if (immersive) {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

fun View.applyTopBarInsets() {
    val initialPaddingLeft = paddingLeft
    val initialPaddingTop = paddingTop
    val initialPaddingRight = paddingRight
    val initialPaddingBottom = paddingBottom
    val initialHeight = layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safeInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = initialPaddingLeft + safeInsets.left,
            top = initialPaddingTop + safeInsets.top,
            right = initialPaddingRight + safeInsets.right,
            bottom = initialPaddingBottom,
        )
        if (initialHeight > 0) {
            view.updateLayoutParams<ViewGroup.LayoutParams> {
                height = initialHeight + safeInsets.top
            }
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

fun View.applyBottomContentInsets(includeIme: Boolean = false) {
    val initialPaddingLeft = paddingLeft
    val initialPaddingTop = paddingTop
    val initialPaddingRight = paddingRight
    val initialPaddingBottom = paddingBottom
    val insetTypes = WindowInsetsCompat.Type.navigationBars() or
        WindowInsetsCompat.Type.displayCutout() or
        (if (includeIme) WindowInsetsCompat.Type.ime() else 0)

    (this as? ViewGroup)?.clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safeInsets = insets.getInsets(insetTypes)
        view.updatePadding(
            left = initialPaddingLeft + safeInsets.left,
            top = initialPaddingTop,
            right = initialPaddingRight + safeInsets.right,
            bottom = initialPaddingBottom + safeInsets.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

fun View.applySafeAreaMargins(
    applyStart: Boolean = false,
    applyTop: Boolean = false,
    applyEnd: Boolean = false,
    applyBottom: Boolean = false,
) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val initialStart = params.marginStart
    val initialTop = params.topMargin
    val initialEnd = params.marginEnd
    val initialBottom = params.bottomMargin

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safeInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = initialStart + if (applyStart) safeInsets.left else 0
            topMargin = initialTop + if (applyTop) safeInsets.top else 0
            marginEnd = initialEnd + if (applyEnd) safeInsets.right else 0
            bottomMargin = initialBottom + if (applyBottom) safeInsets.bottom else 0
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
