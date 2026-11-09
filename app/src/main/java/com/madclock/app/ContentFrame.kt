package com.madclock.app

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** One readable vertical column in both orientations, including wide tablets. */
class ContentFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (720 * resources.displayMetrics.density).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), max), MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
