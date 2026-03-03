package com.turtlewow.pipboy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
  enum class UiLang { EN, ZH }
  enum class Page { MAP, BAG, IME, DEBUG, CONFIG }

  data class ZoneTileCatalog(
    val dirName: String,
    val files: Set<String>,
    val rootsBySlug: Map<String, String>,
    val baseRoot: String?
  )

  companion object {
    private const val MAP_LOGICAL_WIDTH = 1002
    private const val MAP_LOGICAL_HEIGHT = 668
    private const val TILE_SIZE = 256
  }

  private val logTag = "PipBoy"
  private lateinit var ipInput: EditText
  private lateinit var portInput: EditText
  private lateinit var connectBtn: Button
  private lateinit var langBtn: Button
  private lateinit var navHeaderText: TextView
  private lateinit var navMapBtn: Button
  private lateinit var navBagBtn: Button
  private lateinit var navImeBtn: Button
  private lateinit var navDebugBtn: Button
  private lateinit var navConfigBtn: Button
  private lateinit var titleText: TextView
  private lateinit var connStateText: TextView
  private lateinit var statusText: TextView
  private lateinit var debugStatusText: TextView
  private lateinit var logText: TextView
  private lateinit var mapView: MapSurfaceView
  private lateinit var pageMap: View
  private lateinit var pageBag: View
  private lateinit var pageIme: View
  private lateinit var pageDebug: View
  private lateinit var pageConfig: View
  private lateinit var bagText: TextView
  private lateinit var imeText: TextView
  private lateinit var imeInput: EditText
  private lateinit var imeInsertBtn: Button
  private lateinit var imeSendBtn: Button
  private lateinit var playerPanelTitle: TextView
  private lateinit var playerNameLabel: TextView
  private lateinit var playerLevelLabel: TextView
  private lateinit var playerHpLabel: TextView
  private lateinit var playerZoneLabel: TextView
  private lateinit var playerCoordsLabel: TextView
  private lateinit var playerNameValue: TextView
  private lateinit var playerLevelValue: TextView
  private lateinit var playerHpValue: TextView
  private lateinit var playerZoneValue: TextView
  private lateinit var playerCoordsValue: TextView

  private var client: UdpBridgeClient? = null
  private var connected = false
  private var lastPos: PosPacket? = null
  private var lastOverlay: OverlayPacket? = null
  private var lastPosLogMs: Long = 0L
  private var currentHost: String = ""
  private var currentPort: Int = 38442
  private var uiLang: UiLang = UiLang.EN
  private var currentPage: Page = Page.MAP
  private var lastImeFocus = false

  // slug(zone) -> asset path, e.g. worldmap_atlas/Elwynn_atlas_rowmajor.png
  private val atlasIndex = LinkedHashMap<String, String>()
  // slug(zone) -> tile dir name, e.g. ELWYNN -> Elwynn
  private val tileZoneIndex = LinkedHashMap<String, String>()
  private val zoneCatalogCache = LinkedHashMap<String, ZoneTileCatalog>()
  private val bitmapCache = LinkedHashMap<String, Bitmap>(128, 0.75f, true)
  private val composedCache = LinkedHashMap<String, Bitmap>(12, 0.75f, true)

  private val prefs by lazy { getSharedPreferences("pipboy", MODE_PRIVATE) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    navHeaderText = findViewById(R.id.navHeaderText)
    navMapBtn = findViewById(R.id.navMapBtn)
    navBagBtn = findViewById(R.id.navBagBtn)
    navImeBtn = findViewById(R.id.navImeBtn)
    navDebugBtn = findViewById(R.id.navDebugBtn)
    navConfigBtn = findViewById(R.id.navConfigBtn)
    titleText = findViewById(R.id.titleText)
    connStateText = findViewById(R.id.connStateText)
    pageMap = findViewById(R.id.pageMap)
    pageBag = findViewById(R.id.pageBag)
    pageIme = findViewById(R.id.pageIme)
    pageDebug = findViewById(R.id.pageDebug)
    pageConfig = findViewById(R.id.pageConfig)
    bagText = findViewById(R.id.bagText)
    imeText = findViewById(R.id.imeText)
    imeInput = findViewById(R.id.imeInput)
    imeInsertBtn = findViewById(R.id.imeInsertBtn)
    imeSendBtn = findViewById(R.id.imeSendBtn)
    playerPanelTitle = findViewById(R.id.playerPanelTitle)
    playerNameLabel = findViewById(R.id.playerNameLabel)
    playerLevelLabel = findViewById(R.id.playerLevelLabel)
    playerHpLabel = findViewById(R.id.playerHpLabel)
    playerZoneLabel = findViewById(R.id.playerZoneLabel)
    playerCoordsLabel = findViewById(R.id.playerCoordsLabel)
    ipInput = findViewById(R.id.ipInput)
    portInput = findViewById(R.id.portInput)
    connectBtn = findViewById(R.id.connectBtn)
    langBtn = findViewById(R.id.langBtn)
    statusText = findViewById(R.id.statusText)
    debugStatusText = findViewById(R.id.debugStatusText)
    logText = findViewById(R.id.logText)
    mapView = findViewById(R.id.mapView)
    playerNameValue = findViewById(R.id.playerNameValue)
    playerLevelValue = findViewById(R.id.playerLevelValue)
    playerHpValue = findViewById(R.id.playerHpValue)
    playerZoneValue = findViewById(R.id.playerZoneValue)
    playerCoordsValue = findViewById(R.id.playerCoordsValue)

    ipInput.setText(prefs.getString("ip", "192.168.0.112"))
    portInput.setText(prefs.getInt("port", 38442).toString())
    currentHost = ipInput.text.toString().trim()
    currentPort = portInput.text.toString().trim().toIntOrNull() ?: 38442
    uiLang = if (prefs.getString("ui_lang", "en") == "zh") UiLang.ZH else UiLang.EN
    currentPage = Page.entries.getOrElse(prefs.getInt("current_page", 0)) { Page.MAP }

    connectBtn.setOnClickListener {
      if (connected) {
        disconnect()
      } else {
        connect()
      }
    }
    langBtn.setOnClickListener {
      uiLang = if (uiLang == UiLang.EN) UiLang.ZH else UiLang.EN
      prefs.edit().putString("ui_lang", if (uiLang == UiLang.ZH) "zh" else "en").apply()
      applyLanguage()
    }
    navMapBtn.setOnClickListener { switchPage(Page.MAP) }
    navBagBtn.setOnClickListener { switchPage(Page.BAG) }
    navImeBtn.setOnClickListener { switchPage(Page.IME) }
    navDebugBtn.setOnClickListener { switchPage(Page.DEBUG) }
    navConfigBtn.setOnClickListener { switchPage(Page.CONFIG) }
    imeInsertBtn.setOnClickListener { sendImeText(submit = false) }
    imeSendBtn.setOnClickListener { sendImeText(submit = true) }

    rebuildAtlasIndexFromAssets()
    rebuildTileIndexFromAssets()
    applyLanguage()
    switchPage(currentPage)
    log("ready")
  }

  override fun onDestroy() {
    super.onDestroy()
    disconnect()
    bitmapCache.clear()
    composedCache.clear()
  }

  private fun connect() {
    val ip = ipInput.text.toString().trim().ifEmpty { "127.0.0.1" }
    val port = portInput.text.toString().trim().toIntOrNull() ?: 38442
    currentHost = ip
    currentPort = port

    prefs.edit()
      .putString("ip", ip)
      .putInt("port", port)
      .apply()

    val c = UdpBridgeClient(
      host = ip,
      port = port,
      onPos = { p -> runOnUiThread { onPosPacket(p) } },
      onOverlay = { o -> runOnUiThread { onOverlayPacket(o) } },
      onAck = { msg -> runOnUiThread { log(msg); Log.d(logTag, msg) } },
      onLog = { msg -> runOnUiThread { log(msg); Log.d(logTag, msg) } }
    )

    c.start(lifecycleScope)
    client = c
    connected = true
    connectBtn.text = t("Disconnect", "断开")
    statusText.text = t("Map: waiting data", "地图: 等待数据")
    connStateText.text = statusConnectedText(ip, port)
    switchPage(Page.MAP)
    Log.i(logTag, "connect host=$ip port=$port")
  }

  private fun disconnect() {
    val c = client ?: return
    lifecycleScope.launch {
      c.stop()
    }
    client = null
    connected = false
    lastImeFocus = false
    lastPos = null
    lastOverlay = null
    connectBtn.text = t("Connect", "连接")
    statusText.text = t("Map: waiting data", "地图: 等待数据")
    connStateText.text = t("Idle", "空闲")
    val waiting = t("debug: waiting packets", "调试: 等待数据包")
    debugStatusText.text = waiting
    updatePlayerPanel(null)
    Log.i(logTag, "disconnect")
  }

  private fun onPosPacket(p: PosPacket) {
    lastPos = p
    updateMapStatus(p)
    updatePlayerPanel(p)
    ensureMapLoaded(p.map, p.zone)
    mapView.setPosition(p)
    refreshDebugPanel()

    if (p.imeFocus && !lastImeFocus) {
      switchPage(Page.IME)
      showImeKeyboard()
    }
    lastImeFocus = p.imeFocus

    val now = System.currentTimeMillis()
    if (now - lastPosLogMs >= 1000L) {
      lastPosLogMs = now
      Log.d(
        logTag,
        "pos map=${p.map} zone=${p.zone} x=${"%.3f".format(p.x)} y=${"%.3f".format(p.y)} facing=${"%.2f".format(p.facingDeg ?: 0f)} src=${p.facingSrc ?: "none"}"
      )
    }
  }

  private fun onOverlayPacket(o: OverlayPacket) {
    lastOverlay = o
    ensureMapLoaded(o.map, o.zone)
    log("overlay ${o.map}/${o.zone} count=${o.count}")
    refreshDebugPanel()
    Log.d(logTag, "overlay map=${o.map} zone=${o.zone} count=${o.count} rx=${o.overlays.size} ts=${o.ts}")
  }

  private fun refreshDebugPanel() {
    val p = lastPos
    val o = lastOverlay
    val now = System.currentTimeMillis()
    val overlayAge = if (o != null && o.ts > 0L) (now - o.ts).coerceAtLeast(0L) else -1L

    val facingSrc = p?.facingSrc?.ifBlank { "none" } ?: "none"
    val imeLine = if (p == null) {
      "ime=waiting"
    } else {
      "ime_focus=${p.imeFocus} ime_box=${p.imeBox ?: "-"}"
    }
    val overlayLine = if (o == null) {
      t("overlay=waiting", "overlay=等待中")
    } else {
      val age = if (overlayAge >= 0) overlayAge else 0
      if (uiLang == UiLang.ZH) {
        "overlay=${o.map.ifBlank { o.zone }}/${o.zone} 数量=${o.count} 接收=${o.overlays.size} 延迟=${age}ms"
      } else {
        "overlay=${o.map.ifBlank { o.zone }}/${o.zone} count=${o.count} rx=${o.overlays.size} age=${age}ms"
      }
    }
    val text = "facing_src=$facingSrc\n$overlayLine\n$imeLine"
    debugStatusText.text = text
  }

  private fun switchPage(page: Page) {
    currentPage = page
    prefs.edit().putInt("current_page", page.ordinal).apply()

    pageMap.visibility = if (page == Page.MAP) View.VISIBLE else View.GONE
    pageBag.visibility = if (page == Page.BAG) View.VISIBLE else View.GONE
    pageIme.visibility = if (page == Page.IME) View.VISIBLE else View.GONE
    pageDebug.visibility = if (page == Page.DEBUG) View.VISIBLE else View.GONE
    pageConfig.visibility = if (page == Page.CONFIG) View.VISIBLE else View.GONE

    navMapBtn.isSelected = page == Page.MAP
    navBagBtn.isSelected = page == Page.BAG
    navImeBtn.isSelected = page == Page.IME
    navDebugBtn.isSelected = page == Page.DEBUG
    navConfigBtn.isSelected = page == Page.CONFIG

    titleText.text = t("TurtleWoW Companion", "乌龟服伴侣") + " · " + pageLabel(page)
    if (page == Page.IME) {
      showImeKeyboard()
    }
  }

  private fun applyNavLabels() {
    val map = t("MAP", "地图")
    val bag = t("BAG", "背包")
    val ime = "IME"
    val debug = t("DEBUG", "调试")
    val config = t("CONFIG", "设置")

    navMapBtn.text = map
    navBagBtn.text = bag
    navImeBtn.text = ime
    navDebugBtn.text = debug
    navConfigBtn.text = config
  }

  private fun applyLanguage() {
    titleText.text = t("TurtleWoW Companion", "乌龟服伴侣") + " · " + pageLabel(currentPage)
    navHeaderText.text = t("Navigation", "导航")
    ipInput.hint = t("WoW IP", "WoW 地址")
    portInput.hint = t("Port", "端口")
    connectBtn.text = if (connected) t("Disconnect", "断开") else t("Connect", "连接")
    langBtn.text = if (uiLang == UiLang.EN) "EN" else "中文"
    bagText.text = t("Bag page (placeholder)", "背包页面（占位）")
    imeText.text = t("IME Bridge", "输入法桥接")
    imeInput.hint = t("Type text here...", "在这里输入文本...")
    imeInsertBtn.text = t("Insert", "插入")
    imeSendBtn.text = t("Insert + Send", "插入并发送")
    applyNavLabels()

    connStateText.text = if (connected) {
      statusConnectedText(currentHost, currentPort)
    } else {
      t("Idle", "空闲")
    }
    val p = lastPos
    if (p != null) {
      updateMapStatus(p)
      updatePlayerPanel(p)
    } else {
      statusText.text = t("Map: waiting data", "地图: 等待数据")
      updatePlayerPanel(null)
    }
    refreshDebugPanel()
  }

  private fun statusConnectedText(ip: String, port: Int): String {
    return t("Connected -> $ip:$port", "已连接 -> $ip:$port")
  }

  private fun t(en: String, zh: String): String {
    return if (uiLang == UiLang.ZH) zh else en
  }

  private fun pageLabel(page: Page): String {
    return when (page) {
      Page.MAP -> t("Map", "地图")
      Page.BAG -> t("Bag", "背包")
      Page.IME -> "IME"
      Page.DEBUG -> t("Debug", "调试")
      Page.CONFIG -> t("Config", "设置")
    }
  }

  private fun updateMapStatus(p: PosPacket) {
    statusText.text = if (uiLang == UiLang.ZH) {
      "${p.zone}  x=${"%.3f".format(p.x)} y=${"%.3f".format(p.y)}"
    } else {
      "${p.zone}  x=${"%.3f".format(p.x)} y=${"%.3f".format(p.y)}"
    }
  }

  private fun updatePlayerPanel(p: PosPacket?) {
    playerPanelTitle.text = t("PLAYER", "角色")
    playerNameLabel.text = t("Name", "名称")
    playerLevelLabel.text = t("Level", "等级")
    playerHpLabel.text = "HP"
    playerZoneLabel.text = t("Zone", "区域")
    playerCoordsLabel.text = t("Coords", "坐标")

    if (p == null) {
      playerNameValue.text = "-"
      playerLevelValue.text = "-"
      playerHpValue.text = "-"
      playerZoneValue.text = "-"
      playerCoordsValue.text = "-"
      return
    }
    playerNameValue.text = p.player
    playerLevelValue.text = p.level.toString()
    playerHpValue.text = "${p.hp}/${p.hpMax}"
    playerZoneValue.text = p.zone
    playerCoordsValue.text = "x=${"%.3f".format(p.x)} y=${"%.3f".format(p.y)}"
  }

  private fun ensureMapLoaded(map: String, zone: String) {
    val keyCandidates = listOf(map, zone).map { slug(it) }.filter { it.isNotEmpty() }

    for (k in keyCandidates) {
      val dirName = tileZoneIndex[k] ?: continue
      val overlay = lastOverlay?.takeIf { overlayMatchesCurrentMap(it, keyCandidates) }
      val renderKey = buildTileRenderKey(dirName, overlay)
      val cached = composedCache[renderKey]
      if (cached != null && !cached.isRecycled) {
        mapView.setMap(cached, "${map.ifBlank { zone }} (tiles)")
        return
      }
      val bmp = composeMapWithTiles(dirName, overlay)
      if (bmp != null) {
        composedCache[renderKey] = bmp
        trimComposedCache()
        mapView.setMap(bmp, "${map.ifBlank { zone }} (tiles)")
        return
      }
    }

    for (k in keyCandidates) {
      val assetPath = atlasIndex[k] ?: continue
      val bmp = loadBitmapCached(assetPath) ?: continue
      mapView.setMap(bmp, "${map.ifBlank { zone }} (${overlayLabel(assetPath)})")
      return
    }
  }

  private fun overlayLabel(assetPath: String): String {
    val fileName = assetPath.substringAfterLast('/')
    return fileName.removeSuffix("_atlas_rowmajor.png")
  }

  private fun overlayMatchesCurrentMap(overlay: OverlayPacket, keyCandidates: List<String>): Boolean {
    val overlayMap = slug(overlay.map)
    val overlayZone = slug(overlay.zone)
    return keyCandidates.contains(overlayMap) || keyCandidates.contains(overlayZone)
  }

  private fun buildTileRenderKey(dirName: String, overlay: OverlayPacket?): String {
    return if (overlay == null) {
      "tile:$dirName:unexplored"
    } else {
      "tile:$dirName:${overlay.ts}:${overlay.count}:${overlay.overlays.size}"
    }
  }

  private fun trimComposedCache(maxEntries: Int = 12) {
    while (composedCache.size > maxEntries) {
      val first = composedCache.entries.firstOrNull() ?: break
      composedCache.remove(first.key)
    }
  }

  private fun rebuildTileIndexFromAssets() {
    tileZoneIndex.clear()
    zoneCatalogCache.clear()
    val base = "worldmap_tiles"
    val zoneDirs = assets.list(base) ?: emptyArray()
    for (dir in zoneDirs) {
      val files = assets.list("$base/$dir") ?: continue
      if (files.none { it.endsWith(".png", ignoreCase = true) }) continue
      tileZoneIndex[slug(dir)] = dir
    }
    log("tile zones indexed from assets: ${tileZoneIndex.size}")
  }

  private fun getZoneCatalog(dirName: String): ZoneTileCatalog? {
    val cached = zoneCatalogCache[dirName]
    if (cached != null) return cached

    val names = assets.list("worldmap_tiles/$dirName") ?: return null
    val pngFiles = names.filter { it.endsWith(".png", ignoreCase = true) }.toSet()
    if (pngFiles.isEmpty()) return null

    val rootsBySlug = LinkedHashMap<String, String>()
    val rootCounts = LinkedHashMap<String, Int>()
    for (file in pngFiles) {
      val stem = file.removeSuffix(".png")
      val m = Regex("^(.*?)(\\d+)$").matchEntire(stem) ?: continue
      val root = m.groupValues[1]
      val idx = m.groupValues[2].toIntOrNull() ?: continue
      val rootSlug = slug(root)
      if (!rootsBySlug.containsKey(rootSlug)) {
        rootsBySlug[rootSlug] = root
      }
      val prev = rootCounts[root] ?: 0
      if (idx > prev) rootCounts[root] = idx
    }

    var baseRoot: String? = rootsBySlug[slug(dirName)]
    if (baseRoot == null && rootCounts.isNotEmpty()) {
      baseRoot = rootCounts.maxByOrNull { it.value }?.key
    }

    val catalog = ZoneTileCatalog(
      dirName = dirName,
      files = pngFiles,
      rootsBySlug = rootsBySlug,
      baseRoot = baseRoot
    )
    zoneCatalogCache[dirName] = catalog
    return catalog
  }

  private fun composeMapWithTiles(dirName: String, overlay: OverlayPacket?): Bitmap? {
    val catalog = getZoneCatalog(dirName) ?: return null
    val base = buildUnexploredBase(catalog) ?: return null
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)

    if (overlay != null) {
      for (rect in overlay.overlays) {
        drawOverlayTiles(canvas, catalog, rect)
      }
    }

    val cropW = minOf(MAP_LOGICAL_WIDTH, out.width)
    val cropH = minOf(MAP_LOGICAL_HEIGHT, out.height)
    return if (cropW == out.width && cropH == out.height) out else Bitmap.createBitmap(out, 0, 0, cropW, cropH)
  }

  private fun buildUnexploredBase(catalog: ZoneTileCatalog): Bitmap? {
    val baseRoot = catalog.baseRoot ?: return null
    val indexRegex = Regex("^${Regex.escape(baseRoot)}(\\d+)\\.png$")
    var maxIdx = 0
    for (name in catalog.files) {
      val m = indexRegex.matchEntire(name) ?: continue
      val idx = m.groupValues[1].toIntOrNull() ?: continue
      if (idx > maxIdx) maxIdx = idx
    }
    if (maxIdx <= 0) return null

    val cols = 4
    val rows = ceil(maxIdx / cols.toDouble()).toInt().coerceAtLeast(1)
    val base = Bitmap.createBitmap(cols * TILE_SIZE, rows * TILE_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(base)

    var pasted = 0
    for (idx in 1..maxIdx) {
      val name = "$baseRoot$idx.png"
      if (!catalog.files.contains(name)) continue
      val tile = loadBitmapCached("worldmap_tiles/${catalog.dirName}/$name") ?: continue
      val col = (idx - 1) % cols
      val row = (idx - 1) / cols
      val dx = col * TILE_SIZE
      val dy = row * TILE_SIZE
      canvas.drawBitmap(tile, null, Rect(dx, dy, dx + TILE_SIZE, dy + TILE_SIZE), null)
      pasted++
    }
    return if (pasted > 0) base else null
  }

  private fun drawOverlayTiles(canvas: Canvas, catalog: ZoneTileCatalog, rect: OverlayRect) {
    val rootName = rect.tex.substringAfterLast('\\')
    val root = catalog.rootsBySlug[slug(rootName)] ?: return
    val cols = ceil(rect.w / TILE_SIZE.toDouble()).toInt().coerceAtLeast(1)
    val rows = ceil(rect.h / TILE_SIZE.toDouble()).toInt().coerceAtLeast(1)
    var idx = 1

    for (r in 0 until rows) {
      for (c in 0 until cols) {
        val preferredName = "$root$idx.png"
        val fallbackName = "${root}1.png"
        val tileName = when {
          catalog.files.contains(preferredName) -> preferredName
          catalog.files.contains(fallbackName) -> fallbackName
          else -> {
            idx++
            continue
          }
        }

        val tile = loadBitmapCached("worldmap_tiles/${catalog.dirName}/$tileName")
        if (tile != null) {
          val drawW = minOf(TILE_SIZE, rect.w - c * TILE_SIZE)
          val drawH = minOf(TILE_SIZE, rect.h - r * TILE_SIZE)
          if (drawW > 0 && drawH > 0) {
            val src = Rect(0, 0, drawW, drawH)
            val dx = rect.x + c * TILE_SIZE
            val dy = rect.y + r * TILE_SIZE
            val dst = Rect(dx, dy, dx + drawW, dy + drawH)
            canvas.drawBitmap(tile, src, dst, null)
          }
        }
        idx++
      }
    }
  }

  private fun rebuildAtlasIndexFromAssets() {
    atlasIndex.clear()
    val base = "worldmap_atlas"
    val names = assets.list(base) ?: emptyArray()
    for (name in names) {
      if (!name.endsWith("_atlas_rowmajor.png")) continue
      val zoneName = name.removeSuffix("_atlas_rowmajor.png")
      atlasIndex[slug(zoneName)] = "$base/$name"
    }
    log("atlas indexed from assets: ${atlasIndex.size} zones")
  }

  private fun loadBitmapCached(assetPath: String): Bitmap? {
    val cached = bitmapCache[assetPath]
    if (cached != null && !cached.isRecycled) {
      return cached
    }

    val bmp = try {
      assets.open(assetPath).use { input ->
        BitmapFactory.decodeStream(input)
      }
    } catch (_: Throwable) {
      null
    } ?: return null

    bitmapCache[assetPath] = bmp

    // trim cache size
    while (bitmapCache.size > 160) {
      val first = bitmapCache.entries.firstOrNull() ?: break
      bitmapCache.remove(first.key)
    }
    return bmp
  }

  private fun slug(s: String): String {
    return s.replace(Regex("[^A-Za-z0-9]"), "").uppercase(Locale.US)
  }

  private fun log(message: String) {
    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    val line = "[$ts] $message\n"
    val existing = logText.text?.toString().orEmpty()
    val merged = existing + line
    logText.text = if (merged.length > 7000) merged.takeLast(7000) else merged
  }

  private fun showImeKeyboard() {
    imeInput.requestFocus()
    imeInput.post {
      val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.showSoftInput(imeInput, InputMethodManager.SHOW_IMPLICIT)
    }
  }

  private fun sendImeText(submit: Boolean) {
    val text = imeInput.text?.toString().orEmpty()
    if (text.isEmpty()) return
    val c = client
    if (c == null) {
      log(t("ime send skipped: not connected", "IME发送跳过: 未连接"))
      return
    }
    c.sendImeCommit(text, submit)
    imeInput.text?.clear()
    if (!submit) {
      showImeKeyboard()
    }
  }
}
