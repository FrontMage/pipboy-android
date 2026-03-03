package com.turtlewow.pipboy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class MapSurfaceView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {
  companion object {
    // Mirror sign to match left/right turning direction.
    // Offset calibrated from device test: 0 deg.
    private const val MARKER_FACING_OFFSET_DEG = 0f
  }

  private var mapBitmap: Bitmap? = null
  private var pos: PosPacket? = null
  private var mapLabel: String = ""

  private val mapLogicalWidth = 1002f
  private val mapLogicalHeight = 668f

  private val mapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(230, 200, 255, 200)
    textSize = 30f
  }

  private val markerBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val markerBitmap: Bitmap? = runCatching {
    BitmapFactory.decodeResource(resources, R.drawable.player_arrow_green)
  }.getOrNull()

  fun setMap(bitmap: Bitmap?, label: String) {
    mapBitmap = bitmap
    mapLabel = label
    invalidate()
  }

  fun setPosition(p: PosPacket?) {
    pos = p
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.drawColor(Color.rgb(22, 20, 16))

    val bmp = mapBitmap
    if (bmp == null) {
      canvas.drawText("No map loaded", 24f, 48f, textPaint)
      return
    }

    val srcW = min(bmp.width.toFloat(), mapLogicalWidth).toInt().coerceAtLeast(1)
    val srcH = min(bmp.height.toFloat(), mapLogicalHeight).toInt().coerceAtLeast(1)

    val viewRatio = width / height.toFloat()
    val mapRatio = mapLogicalWidth / mapLogicalHeight

    val dst = if (viewRatio > mapRatio) {
      val h = height.toFloat()
      val w = h * mapRatio
      val left = (width - w) * 0.5f
      RectF(left, 0f, left + w, h)
    } else {
      val w = width.toFloat()
      val h = w / mapRatio
      val top = (height - h) * 0.5f
      RectF(0f, top, w, top + h)
    }

    val srcRect = Rect(0, 0, srcW, srcH)
    canvas.drawBitmap(bmp, srcRect, dst, mapPaint)

    drawMarker(canvas, dst)

    canvas.drawText(mapLabel, 16f, height - 20f, textPaint)
  }

  private fun drawMarker(canvas: Canvas, dst: RectF) {
    val p = pos ?: return
    val px = dst.left + p.x * dst.width()
    val py = dst.top + p.y * dst.height()

    val radius = 18f

    val rawFacingDeg = p.facingDeg ?: 0f
    val angleDeg = (-rawFacingDeg) + MARKER_FACING_OFFSET_DEG
    val icon = markerBitmap
    if (icon == null) return

    val iconHalfW = radius * 1.35f
    val iconHalfH = radius * 1.35f
    val iconDst = RectF(-iconHalfW, -iconHalfH, iconHalfW, iconHalfH)

    canvas.save()
    canvas.translate(px, py)
    canvas.rotate(angleDeg)
    canvas.drawBitmap(icon, null, iconDst, markerBitmapPaint)
    canvas.restore()
  }
}
