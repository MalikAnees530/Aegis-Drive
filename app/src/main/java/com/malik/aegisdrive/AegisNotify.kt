package com.malik.aegisdrive

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

object AegisNotify {

    enum class Type {
        SUCCESS, INFO, WARNING, ERROR, SPEECH
    }

    private var activeToast: Toast? = null

    fun show(context: Context, message: String, type: Type = Type.INFO) {
        activeToast?.cancel()
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.layout_custom_toast, null)

        val icon: ImageView = layout.findViewById(R.id.toastIcon)
        val text: TextView = layout.findViewById(R.id.toastText)

        text.text = message

        val color = when (type) {
            Type.SUCCESS -> {
                icon.setImageResource(R.drawable.ic_check_circle)
                "#22C55E" // status_safe
            }
            Type.INFO -> {
                icon.setImageResource(R.drawable.ic_info)
                "#38BDF8" // accent_primary
            }
            Type.WARNING -> {
                icon.setImageResource(R.drawable.ic_warning_modern)
                "#F59E0B" // status_warning
            }
            Type.ERROR -> {
                icon.setImageResource(R.drawable.ic_warning_modern)
                "#EF5350" // status_danger
            }
            Type.SPEECH -> {
                icon.setImageResource(R.drawable.ic_mic)
                "#38BDF8" // accent_primary
            }
        }
        
        try { icon.setColorFilter(Color.parseColor(color)) } catch (e: Exception) {}

        with(Toast(context)) {
            duration = Toast.LENGTH_SHORT
            view = layout
            activeToast = this
            show()
        }
    }
}
