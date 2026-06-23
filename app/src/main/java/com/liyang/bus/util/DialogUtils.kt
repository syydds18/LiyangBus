package com.liyang.bus.util

import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.liyang.bus.R

object DialogUtils {
    fun applyRoundedDialogStyle(dialog: Dialog) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                (window.context.resources.displayMetrics.widthPixels * 0.88).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window.setDimAmount(0.5f)
        }
        dialog.setOnShowListener { applyContentViewStyle(dialog) }
    }

    private fun applyContentViewStyle(dialog: Dialog) {
        val window = dialog.window ?: return
        val ctx = window.context
        val density = ctx.resources.displayMetrics.density
        val bgColor = ContextCompat.getColor(ctx, R.color.mi_card)

        val decorView = window.decorView as? android.view.ViewGroup ?: return
        val contentView = decorView.getChildAt(0) ?: return

        val bg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 16f * density
        }
        contentView.background = bg
        contentView.clipToOutline = true
        contentView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 16f * density)
            }
        }
    }

    /** Apply rounded corner style to AlertDialog */
    fun applyAlertDialogStyle(dialog: android.app.AlertDialog) {
        dialog.window?.let { window ->
            val density = window.context.resources.displayMetrics.density
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.5f)
            // Apply rounded corners to decor view's background panel
            val decorView = window.decorView as? android.view.ViewGroup ?: return
            for (i in 0 until decorView.childCount) {
                val child = decorView.getChildAt(i)
                val bg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(window.context, R.color.mi_card))
                    cornerRadius = 16f * density
                }
                child.background = bg
                child.clipToOutline = true
                child.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 16f * density)
                    }
                }
            }
        }
    }

    /** Strip Material button borders/outlines from AlertDialog buttons */
    fun stripAlertDialogButtonBorders(dialog: android.app.AlertDialog) {
        val color = ContextCompat.getColor(dialog.context, R.color.mi_orange)
        for (buttonType in intArrayOf(
            android.app.AlertDialog.BUTTON_POSITIVE,
            android.app.AlertDialog.BUTTON_NEGATIVE,
            android.app.AlertDialog.BUTTON_NEUTRAL
        )) {
            dialog.getButton(buttonType)?.let { btn ->
                btn.background = null
                btn.setTextColor(color)
            }
        }
    }
}
