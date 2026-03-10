package com.turtlewow.pipboy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PipBoyBuddyView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {
  enum class State { IDLE, WALK, HURT }

  private var state: State = State.IDLE

  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }

  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }

  fun setState(newState: State) {
    if (state == newState) return
    state = newState
    invalidate()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    postInvalidateOnAnimation()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (width <= 0 || height <= 0) return

    val t = SystemClock.uptimeMillis() / 1000f
    val size = min(width, height).toFloat()
    val cx = width * 0.5f
    val cy = height * 0.55f

    val bob = when (state) {
      State.WALK -> sin(t * 8f) * size * 0.012f
      State.HURT -> sin(t * 14f) * size * 0.01f
      State.IDLE -> sin(t * 2.2f) * size * 0.005f
    }
    val swing = when (state) {
      State.WALK -> sin(t * 8f) * 0.85f
      State.HURT -> sin(t * 18f) * 0.5f
      State.IDLE -> sin(t * 2.2f) * 0.12f
    }

    val bodyColor = when (state) {
      State.HURT -> if ((SystemClock.uptimeMillis() / 120L) % 2L == 0L) Color.parseColor("#E75D4A") else Color.parseColor("#C24437")
      else -> Color.parseColor("#D7BD8C")
    }
    val outlineColor = Color.parseColor("#2A2017")

    val strokeW = size * 0.032f
    strokePaint.strokeWidth = strokeW

    val torsoTopY = cy - size * 0.20f + bob
    val torsoBottomY = cy + size * 0.08f + bob
    val headR = size * 0.075f
    val headCy = torsoTopY - headR * 1.65f

    // Body fill
    fillPaint.color = bodyColor
    canvas.drawCircle(cx, headCy, headR, fillPaint)
    canvas.drawCircle(cx, torsoTopY + size * 0.02f, size * 0.055f, fillPaint)

    // Limbs (fill tone)
    strokePaint.color = bodyColor
    canvas.drawLine(cx, torsoTopY, cx, torsoBottomY, strokePaint)

    val armLen = size * 0.15f
    val leftArmAngle = (-2.4f + swing * 0.35f)
    val rightArmAngle = (-0.75f - swing * 0.35f)
    val shoulderY = torsoTopY + size * 0.02f
    val lx = cx + cos(leftArmAngle) * armLen
    val ly = shoulderY + sin(leftArmAngle) * armLen
    val rx = cx + cos(rightArmAngle) * armLen
    val ry = shoulderY + sin(rightArmAngle) * armLen
    canvas.drawLine(cx, shoulderY, lx, ly, strokePaint)
    canvas.drawLine(cx, shoulderY, rx, ry, strokePaint)

    val hipY = torsoBottomY
    val legLen = size * 0.18f
    val leftLegAngle = (2.1f - swing * 0.45f)
    val rightLegAngle = (1.05f + swing * 0.45f)
    val llx = cx + cos(leftLegAngle) * legLen
    val lly = hipY + sin(leftLegAngle) * legLen
    val rlx = cx + cos(rightLegAngle) * legLen
    val rly = hipY + sin(rightLegAngle) * legLen
    canvas.drawLine(cx, hipY, llx, lly, strokePaint)
    canvas.drawLine(cx, hipY, rlx, rly, strokePaint)

    // Outline pass
    strokePaint.color = outlineColor
    strokePaint.strokeWidth = strokeW * 0.42f
    canvas.drawCircle(cx, headCy, headR, strokePaint)
    canvas.drawLine(cx, torsoTopY, cx, torsoBottomY, strokePaint)
    canvas.drawLine(cx, shoulderY, lx, ly, strokePaint)
    canvas.drawLine(cx, shoulderY, rx, ry, strokePaint)
    canvas.drawLine(cx, hipY, llx, lly, strokePaint)
    canvas.drawLine(cx, hipY, rlx, rly, strokePaint)

    // Eye
    fillPaint.color = outlineColor
    val eyeY = headCy - headR * 0.15f
    canvas.drawCircle(cx - headR * 0.24f, eyeY, headR * 0.11f, fillPaint)
    canvas.drawCircle(cx + headR * 0.24f, eyeY, headR * 0.11f, fillPaint)

    postInvalidateOnAnimation()
  }
}

