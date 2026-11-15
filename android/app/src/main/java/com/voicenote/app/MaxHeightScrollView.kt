package com.voicenote.app

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that wraps its content but never grows past [maxHeightPx] —
 * beyond that it scrolls. Plain `android:maxHeight` isn't honored by ScrollView,
 * so we cap the measure spec here. Used for the approval card's code box so a
 * long command scrolls inside a bounded panel instead of being ellipsized or
 * blowing up the card height.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ScrollView(context, attrs, defStyle) {

    var maxHeightPx: Int = (200 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthSpec, capped)
    }
}
