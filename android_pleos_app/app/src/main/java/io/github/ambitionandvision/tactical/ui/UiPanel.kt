package io.github.ambitionandvision.tactical.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import io.github.ambitionandvision.tactical.R

internal fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

internal fun Context.label(textRes: Int, sizeRes: Int = R.dimen.text_label, colorRes: Int = R.color.text_secondary): TextView =
    TextView(this).apply {
        setText(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeRes))
        setTextColor(getColor(colorRes))
    }

internal fun Context.valueText(sizeRes: Int = R.dimen.text_body, colorRes: Int = R.color.text_primary): TextView =
    TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeRes))
        setTextColor(getColor(colorRes))
        // Pleos 기본 글꼴(Asta Sans)의 Bold(600). 700 은 ExtraBold 로 잡혀 시스템 화면보다 두껍다.
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        gravity = Gravity.START
        setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }
