package com.turtlewow.pipboy

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class SquareFrameLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val widthSize = MeasureSpec.getSize(widthMeasureSpec)
    val squareSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
    super.onMeasure(squareSpec, squareSpec)
  }
}
