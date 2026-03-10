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
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
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
  private var questMarkers: List<QuestMarker> = emptyList()
  private var focusQuestId: Int? = null
  private var lastMapDst: RectF? = null
  private var mapLabel: String = ""
  var onQuestMarkerTap: ((QuestMarker) -> Unit)? = null

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
  private val questMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val questMarkerFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(220, 255, 209, 92)
    style = Paint.Style.FILL
  }
  private val questOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(220, 45, 25, 10)
    style = Paint.Style.STROKE
    strokeWidth = 2f
  }
  private val questMarkerBitmaps: Map<String, Bitmap?> = mapOf(
    "available" to decodeQuestMarker(R.drawable.pb_pfq_available),
    "available_c" to decodeQuestMarker(R.drawable.pb_pfq_available_c),
    "complete" to decodeQuestMarker(R.drawable.pb_pfq_complete),
    "complete_c" to decodeQuestMarker(R.drawable.pb_pfq_complete_c),
    "cluster_mob" to decodeQuestMarker(R.drawable.pb_pfq_cluster_mob),
    "cluster_item" to decodeQuestMarker(R.drawable.pb_pfq_cluster_item),
    "cluster_misc" to decodeQuestMarker(R.drawable.pb_pfq_cluster_misc)
  )

  private fun decodeQuestMarker(resId: Int): Bitmap? {
    return runCatching { BitmapFactory.decodeResource(resources, resId) }.getOrNull()
  }

  fun setMap(bitmap: Bitmap?, label: String) {
    mapBitmap = bitmap
    mapLabel = label
    invalidate()
  }

  fun setPosition(p: PosPacket?) {
    pos = p
    invalidate()
  }

  fun setQuestMarkers(markers: List<QuestMarker>, focusedQuestId: Int?) {
    questMarkers = markers
    focusQuestId = focusedQuestId
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
    lastMapDst = RectF(dst)

    drawQuestMarkers(canvas, dst)
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

  private fun drawQuestMarkers(canvas: Canvas, dst: RectF) {
    if (questMarkers.isEmpty()) return

    val iconSize = max(16f, min(dst.width(), dst.height()) * 0.034f)
    val half = iconSize * 0.5f
    val focusedQuest = focusQuestId ?: 0
    val markerDst = RectF()

    for (m in questMarkers) {
      val x = dst.left + m.x * dst.width()
      val y = dst.top + m.y * dst.height()
      markerDst.set(x - half, y - half, x + half, y + half)
      val faded = focusedQuest > 0 && m.questId > 0 && m.questId != focusedQuest
      val alpha = if (faded) 85 else 255

      val iconKey = (m.icon ?: "").lowercase()
      val icon = questMarkerBitmaps[iconKey]
      if (icon != null && !icon.isRecycled) {
        questMarkerPaint.alpha = alpha
        canvas.drawBitmap(icon, null, markerDst, questMarkerPaint)
      } else {
        questMarkerFallbackPaint.alpha = alpha
        questOutlinePaint.alpha = alpha
        canvas.drawCircle(x, y, half * 0.62f, questMarkerFallbackPaint)
        canvas.drawCircle(x, y, half * 0.62f, questOutlinePaint)
      }
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        // Claim touch stream so ACTION_UP is reliably delivered for tap hit-testing.
        return true
      }

      MotionEvent.ACTION_UP -> {
        val marker = findQuestMarkerAt(event.x, event.y)
        if (marker != null) {
          onQuestMarkerTap?.invoke(marker)
          performClick()
          return true
        }
      }
    }
    return super.onTouchEvent(event)
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  private fun findQuestMarkerAt(x: Float, y: Float): QuestMarker? {
    val dst = lastMapDst ?: return null
    if (x < dst.left || x > dst.right || y < dst.top || y > dst.bottom) return null
    if (questMarkers.isEmpty()) return null
    val iconSize = max(16f, min(dst.width(), dst.height()) * 0.034f)
    val minTouchRadius = 24f * resources.displayMetrics.density
    val radius = max(iconSize * 1.1f, minTouchRadius)
    val radiusSq = radius * radius

    var nearest: QuestMarker? = null
    var nearestDist = Float.MAX_VALUE
    for (m in questMarkers) {
      val mx = dst.left + m.x * dst.width()
      val my = dst.top + m.y * dst.height()
      val dx = x - mx
      val dy = y - my
      val dist = dx * dx + dy * dy
      if (dist <= radiusSq && dist < nearestDist) {
        nearestDist = dist
        nearest = m
      }
    }
    return nearest
  }
}
