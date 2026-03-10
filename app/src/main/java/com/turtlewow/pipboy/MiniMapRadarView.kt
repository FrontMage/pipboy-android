package com.turtlewow.pipboy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp

class MiniMapRadarView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {
  companion object {
    private const val RADAR_FACING_OFFSET_DEG = 0f
    private const val MINIMAP_ZOOM = 1.8f
    private const val MINIMAP_TILE_WORLD_UNITS = 533.3333f
    private const val MINIMAP_WORLD_OFFSET = 17066.666f
    private const val PLAYER_ARROW_SCALE = 0.024f
    private const val FRAME_INTERVAL_MS = 33L // ~30 FPS
    private const val CAMERA_SMOOTH_GAIN = 10f
  }

  private var pos: PosPacket? = null
  private var scan: ResourceScanPacket? = null
  private var minimapState: MinimapStatePacket? = null
  private var miniMapCompositeBitmap: Bitmap? = null
  private var miniMapCompositeBaseTileX: Int? = null
  private var miniMapCompositeBaseTileY: Int? = null
  private var miniMapBitmap: Bitmap? = null
  private var minimapTileX: Int? = null
  private var minimapTileY: Int? = null
  private var rangeMeters: Float = 70f
  private var targetWx: Float? = null
  private var targetWy: Float? = null
  private var cameraWx: Float? = null
  private var cameraWy: Float? = null
  private var lastAnimMs: Long = 0L
  private var renderActive = false
  private val renderTick = object : Runnable {
    override fun run() {
      if (!renderActive) return
      if (visibility == VISIBLE && windowVisibility == VISIBLE) {
        invalidate()
      }
      postDelayed(this, FRAME_INTERVAL_MS)
    }
  }

  private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(220, 48, 38, 30)
    style = Paint.Style.FILL
  }
  private val herbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(255, 120, 226, 128)
    style = Paint.Style.FILL
  }
  private val minePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(255, 168, 210, 255)
    style = Paint.Style.FILL
  }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(235, 235, 220, 192)
    textSize = dp(10f)
    isFakeBoldText = true
  }
  private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(220, 200, 178, 140)
    textSize = dp(9f)
  }
  private val nodeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(235, 245, 232, 200)
    textSize = dp(9f)
    isFakeBoldText = true
    setShadowLayer(dp(1.8f), 0f, 0f, Color.argb(210, 20, 12, 8))
  }
  private val nodeLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(112, 18, 12, 8)
    style = Paint.Style.FILL
  }
  private val miniMapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    isFilterBitmap = true
  }
  private val miniMapTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(22, 20, 20, 16)
    style = Paint.Style.FILL
  }
  private val markerBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val markerBitmap: Bitmap? = runCatching {
    BitmapFactory.decodeResource(resources, R.drawable.player_arrow_green)
  }.getOrNull()

  fun setPosition(packet: PosPacket?) {
    pos = packet
    invalidate()
  }

  fun setResourceScan(packet: ResourceScanPacket?) {
    scan = packet
    val maxDist = packet?.nodes?.mapNotNull { it.distMeters }?.maxOrNull()
    rangeMeters = (if (maxDist != null && maxDist > 0f) max(45f, maxDist * 1.25f) else 70f).coerceAtMost(160f)
    if (minimapState == null) {
      setCameraTarget(packet?.playerWx, packet?.playerWy, instant = false)
    }
    invalidate()
  }

  fun setMiniMapBitmap(bitmap: Bitmap?) {
    miniMapBitmap = bitmap
    invalidate()
  }

  fun setMiniMapComposite(bitmap: Bitmap?, baseTileX: Int?, baseTileY: Int?) {
    miniMapCompositeBitmap = bitmap
    miniMapCompositeBaseTileX = baseTileX
    miniMapCompositeBaseTileY = baseTileY
    invalidate()
  }

  fun setMinimapState(packet: MinimapStatePacket?) {
    minimapState = packet
    if (packet != null) {
      minimapTileX = packet.tileX
      minimapTileY = packet.tileY
      setCameraTarget(packet.playerWx, packet.playerWy, instant = false)
    }
    invalidate()
  }

  fun setRenderActive(active: Boolean) {
    if (renderActive == active) return
    renderActive = active
    if (active) {
      lastAnimMs = SystemClock.uptimeMillis()
      removeCallbacks(renderTick)
      post(renderTick)
    } else {
      removeCallbacks(renderTick)
    }
  }

  override fun onDetachedFromWindow() {
    removeCallbacks(renderTick)
    renderActive = false
    super.onDetachedFromWindow()
  }

  fun setMinimapKey(packet: MinimapKeyPacket?) {
    if (packet == null) {
      minimapTileX = null
      minimapTileY = null
      invalidate()
      return
    }
    val parsed = parseTile(packet.tile)
    minimapTileX = parsed?.first
    minimapTileY = parsed?.second
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 1f || h <= 1f) return

    val pad = dp(8f)
    val rect = RectF(pad, pad, w - pad, h - pad)
    val cx = rect.centerX()
    val cy = rect.centerY()
    val side = min(rect.width(), rect.height()) - dp(8f)
    if (side <= 1f) return
    val mapDst = RectF(
      cx - side * 0.5f,
      cy - side * 0.5f,
      cx + side * 0.5f,
      cy + side * 0.5f
    )

    canvas.drawRoundRect(rect, dp(10f), dp(10f), framePaint)
    val state = minimapState
    val camera = stepCamera()
    val stateWx = camera?.first ?: state?.playerWx
    val stateWy = camera?.second ?: state?.playerWy
    val stateTileX = state?.tileX
    val stateTileY = state?.tileY

    val compositeBmp = miniMapCompositeBitmap
    val compositeBaseX = miniMapCompositeBaseTileX
    val compositeBaseY = miniMapCompositeBaseTileY
    val hasComposite = compositeBmp != null && compositeBaseX != null && compositeBaseY != null
    val bmp = if (hasComposite) compositeBmp else miniMapBitmap
    var srcRect: Rect? = null
    bmp?.let {
      srcRect = if (hasComposite) {
        buildCompositeZoomSrcRect(
          bitmap = it,
          playerWx = stateWx ?: scan?.playerWx,
          playerWy = stateWy ?: scan?.playerWy,
          baseTileX = compositeBaseX,
          baseTileY = compositeBaseY
        )
      } else {
        buildZoomSrcRect(
          bitmap = it,
          playerWx = stateWx ?: scan?.playerWx,
          playerWy = stateWy ?: scan?.playerWy,
          tileX = stateTileX ?: minimapTileX,
          tileY = stateTileY ?: minimapTileY
        )
      }
      canvas.save()
      canvas.clipRect(mapDst)
      if (srcRect != null) {
        canvas.drawBitmap(it, srcRect, mapDst, miniMapPaint)
      } else {
        canvas.drawBitmap(it, null, mapDst, miniMapPaint)
      }
      canvas.drawRect(mapDst, miniMapTintPaint)
      canvas.restore()
    }

    val rawFacingDeg = pos?.facingDeg ?: 0f
    val angleDeg = (-rawFacingDeg) + RADAR_FACING_OFFSET_DEG
    markerBitmap?.let { icon ->
      val r = side * PLAYER_ARROW_SCALE
      val iconDst = RectF(-r, -r, r, r)
      canvas.save()
      canvas.translate(cx, cy)
      canvas.rotate(angleDeg)
      canvas.drawBitmap(icon, null, iconDst, markerBitmapPaint)
      canvas.restore()
    }

    val s = scan
    val px = s?.playerWx
    val py = s?.playerWy
    if (s != null && px != null && py != null && s.nodes.isNotEmpty()) {
      val tileScale = (side * MINIMAP_ZOOM) / MINIMAP_TILE_WORLD_UNITS
      val half = side * 0.5f
      val fallbackScale = half / max(1f, rangeMeters)
      val dotR = dp(3.2f)
      val tileX = minimapTileX
      val tileY = minimapTileY
      val src = srcRect
      val useAbsoluteProjection = bmp != null &&
        src != null &&
        src.width() > 0 &&
        src.height() > 0 &&
        (
          (hasComposite && compositeBaseX != null && compositeBaseY != null) ||
            (tileX != null && tileY != null)
          )

      for (node in s.nodes) {
        val point = if (useAbsoluteProjection) {
          val uv = if (hasComposite) {
            worldToCompositePixel(node.x, node.y, compositeBaseX!!, compositeBaseY!!, bmp!!)
          } else {
            worldToTilePixel(node.x, node.y, tileX!!, tileY!!, bmp!!)
          }
          val sx = mapDst.left + ((uv.first - src!!.left) / src.width().toFloat()) * mapDst.width()
          val sy = mapDst.top + ((uv.second - src.top) / src.height().toFloat()) * mapDst.height()
          Pair(sx, sy)
        } else {
          val dx = node.x - px
          val dy = node.y - py
          val scale = if (tileX != null && tileY != null) tileScale else fallbackScale
          val sx = cx - dy * scale
          val sy = cy - dx * scale
          Pair(sx, sy)
        }
        val px2 = point.first.coerceIn(mapDst.left + dotR, mapDst.right - dotR)
        val py2 = point.second.coerceIn(mapDst.top + dotR, mapDst.bottom - dotR)
        val p = if ((node.kind ?: "") == "mine") minePaint else herbPaint
        canvas.drawCircle(px2, py2, dotR, p)
        val name = node.name?.ifBlank { null } ?: if ((node.kind ?: "") == "mine") "Ore" else "Herb"
        val clipped = if (name.length > 14) name.substring(0, 14) + "..." else name
        val tx = (px2 + dotR + dp(3f)).coerceAtMost(mapDst.right - dp(6f))
        val ty = (py2 - dotR - dp(1f)).coerceAtLeast(mapDst.top + dp(10f))
        val textW = nodeLabelPaint.measureText(clipped)
        val bgRect = RectF(
          (tx - dp(2f)).coerceAtLeast(mapDst.left + dp(2f)),
          ty - nodeLabelPaint.textSize + dp(1f),
          (tx + textW + dp(2f)).coerceAtMost(mapDst.right - dp(2f)),
          ty + dp(2f)
        )
        canvas.drawRoundRect(bgRect, dp(3f), dp(3f), nodeLabelBgPaint)
        canvas.drawText(clipped, tx, ty, nodeLabelPaint)
      }

      val nearest = s.nodes.firstOrNull()
      val title = nearest?.name ?: "No nearby resource"
      val dist = nearest?.distMeters
      canvas.drawText(title, rect.left + dp(6f), rect.top + dp(15f), labelPaint)
      if (dist != null) {
        canvas.drawText(String.format(Locale.US, "%.1fm", dist), rect.left + dp(6f), rect.top + dp(28f), subLabelPaint)
      }
    } else {
      canvas.drawText("Radar waiting", rect.left + dp(6f), rect.top + dp(15f), labelPaint)
    }
  }

  private fun buildZoomSrcRect(
    bitmap: Bitmap,
    playerWx: Float?,
    playerWy: Float?,
    tileX: Int?,
    tileY: Int?
  ): Rect? {
    val tx = tileX ?: return null
    val ty = tileY ?: return null
    val px = playerWx ?: return null
    val py = playerWy ?: return null
    val rawX = (MINIMAP_WORLD_OFFSET - py) / MINIMAP_TILE_WORLD_UNITS
    val rawY = (MINIMAP_WORLD_OFFSET - px) / MINIMAP_TILE_WORLD_UNITS
    var fracX = rawX - tx
    var fracY = rawY - ty
    while (fracX < 0f) fracX += 1f
    while (fracX >= 1f) fracX -= 1f
    while (fracY < 0f) fracY += 1f
    while (fracY >= 1f) fracY -= 1f

    val srcW = max(32f, bitmap.width / MINIMAP_ZOOM)
    val srcH = max(32f, bitmap.height / MINIMAP_ZOOM)
    val cx = (fracX * bitmap.width).coerceIn(0f, bitmap.width.toFloat())
    val cy = (fracY * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
    val left = (cx - srcW * 0.5f).coerceIn(0f, bitmap.width - srcW)
    val top = (cy - srcH * 0.5f).coerceIn(0f, bitmap.height - srcH)
    return Rect(
      left.toInt(),
      top.toInt(),
      (left + srcW).toInt().coerceAtMost(bitmap.width),
      (top + srcH).toInt().coerceAtMost(bitmap.height)
    )
  }

  private fun buildCompositeZoomSrcRect(
    bitmap: Bitmap,
    playerWx: Float?,
    playerWy: Float?,
    baseTileX: Int?,
    baseTileY: Int?
  ): Rect? {
    val wx = playerWx ?: return null
    val wy = playerWy ?: return null
    val btx = baseTileX ?: return null
    val bty = baseTileY ?: return null
    val tileW = (bitmap.width / 3f).coerceAtLeast(1f)
    val tileH = (bitmap.height / 3f).coerceAtLeast(1f)
    val srcW = max(32f, tileW / MINIMAP_ZOOM)
    val srcH = max(32f, tileH / MINIMAP_ZOOM)
    val center = worldToCompositePixel(wx, wy, btx, bty, bitmap)
    val cx = center.first.coerceIn(0f, bitmap.width.toFloat())
    val cy = center.second.coerceIn(0f, bitmap.height.toFloat())
    val left = (cx - srcW * 0.5f).coerceIn(0f, bitmap.width - srcW)
    val top = (cy - srcH * 0.5f).coerceIn(0f, bitmap.height - srcH)
    return Rect(
      left.toInt(),
      top.toInt(),
      (left + srcW).toInt().coerceAtMost(bitmap.width),
      (top + srcH).toInt().coerceAtMost(bitmap.height)
    )
  }

  private fun parseTile(tile: String?): Pair<Int, Int>? {
    val t = tile?.trim()?.lowercase(Locale.US) ?: return null
    if (!t.startsWith("map")) return null
    val under = t.indexOf('_')
    if (under <= 3 || under >= t.length - 1) return null
    val x = t.substring(3, under).toIntOrNull() ?: return null
    val y = t.substring(under + 1).toIntOrNull() ?: return null
    return x to y
  }

  private fun setCameraTarget(wx: Float?, wy: Float?, instant: Boolean) {
    if (wx == null || wy == null) return
    targetWx = wx
    targetWy = wy
    if (instant || cameraWx == null || cameraWy == null) {
      cameraWx = wx
      cameraWy = wy
      lastAnimMs = SystemClock.uptimeMillis()
    }
  }

  private fun stepCamera(): Pair<Float, Float>? {
    val tx = targetWx ?: return null
    val ty = targetWy ?: return null
    var cx = cameraWx
    var cy = cameraWy
    if (cx == null || cy == null) {
      cameraWx = tx
      cameraWy = ty
      lastAnimMs = SystemClock.uptimeMillis()
      return tx to ty
    }
    val now = SystemClock.uptimeMillis()
    val dt = ((now - lastAnimMs).coerceIn(1L, 250L)).toFloat() / 1000f
    lastAnimMs = now
    val alpha = (1f - exp(-CAMERA_SMOOTH_GAIN * dt)).coerceIn(0.02f, 0.85f)
    cx += (tx - cx) * alpha
    cy += (ty - cy) * alpha
    if (kotlin.math.abs(tx - cx) < 0.0015f) cx = tx
    if (kotlin.math.abs(ty - cy) < 0.0015f) cy = ty
    cameraWx = cx
    cameraWy = cy
    return cx to cy
  }

  private fun worldToTileFraction(wx: Float, wy: Float, tileX: Int, tileY: Int): Pair<Float, Float> {
    val rawX = (MINIMAP_WORLD_OFFSET - wy) / MINIMAP_TILE_WORLD_UNITS
    val rawY = (MINIMAP_WORLD_OFFSET - wx) / MINIMAP_TILE_WORLD_UNITS
    val fracX = wrap01(rawX - tileX)
    val fracY = wrap01(rawY - tileY)
    return fracX to fracY
  }

  private fun worldToTilePixel(wx: Float, wy: Float, tileX: Int, tileY: Int, bitmap: Bitmap): Pair<Float, Float> {
    val frac = worldToTileFraction(wx, wy, tileX, tileY)
    return (frac.first * bitmap.width) to (frac.second * bitmap.height)
  }

  private fun worldToCompositePixel(
    wx: Float,
    wy: Float,
    baseTileX: Int,
    baseTileY: Int,
    bitmap: Bitmap
  ): Pair<Float, Float> {
    val rawX = (MINIMAP_WORLD_OFFSET - wy) / MINIMAP_TILE_WORLD_UNITS
    val rawY = (MINIMAP_WORLD_OFFSET - wx) / MINIMAP_TILE_WORLD_UNITS
    val tileW = (bitmap.width / 3f).coerceAtLeast(1f)
    val tileH = (bitmap.height / 3f).coerceAtLeast(1f)
    val px = (rawX - baseTileX) * tileW
    val py = (rawY - baseTileY) * tileH
    return px to py
  }

  private fun wrap01(v: Float): Float {
    var x = v
    while (x < 0f) x += 1f
    while (x >= 1f) x -= 1f
    return x
  }

  private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
