package com.turtlewow.pipboy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
  enum class UiLang { EN, ZH }
  enum class Page { MAP, BAG, IME, DEBUG, CONFIG, CHARACTER }
  enum class BagFilter { ALL, USE, MAT, GEAR, OTHER }
  enum class QuestPanelMode { TRACKER, DETAIL }
  enum class MapDisplayMode { WORLD, MINI }

  data class QuestTrackerRow(
    val questId: Int,
    val title: String,
    val meta: String,
    val complete: Boolean,
    val titleColor: Int
  )

  data class ZoneTileCatalog(
    val dirName: String,
    val files: Set<String>,
    val rootsBySlug: Map<String, String>,
    val baseRoot: String?
  )

  data class RemoteMapIndex(
    val tileDirs: List<String>,
    val iconsAvailable: Boolean,
    val uiTexturesAvailable: Boolean
  )

  data class DebugLogEntry(
    var ts: String,
    val message: String,
    val verbose: Boolean,
    var count: Int
  )

  companion object {
    private const val MAP_LOGICAL_WIDTH = 1002
    private const val MAP_LOGICAL_HEIGHT = 668
    private const val TILE_SIZE = 256
    private const val DEBUG_LOG_MAX_LINES = 500
    private const val BUDDY_MOVE_THRESHOLD = 0.00045f
    private const val BUDDY_HURT_FLASH_MS = 1300L
    private val CHARACTER_VISIBLE_SLOT_SET = setOf(
      "HeadSlot",
      "NeckSlot",
      "ShoulderSlot",
      "BackSlot",
      "ChestSlot",
      "ShirtSlot",
      "TabardSlot",
      "WristSlot",
      "HandsSlot",
      "WaistSlot",
      "LegsSlot",
      "FeetSlot",
      "Finger0Slot",
      "Finger1Slot",
      "Trinket0Slot",
      "Trinket1Slot",
      "MainHandSlot",
      "SecondaryHandSlot",
      "AmmoSlot"
    )
  }

  private val logTag = "PipBoy"
  private lateinit var ipInput: EditText
  private lateinit var portInput: EditText
  private lateinit var connectBtn: Button
  private lateinit var langBtn: Button
  private lateinit var navHeaderText: TextView
  private lateinit var navMapBtn: Button
  private lateinit var navCharBtn: Button
  private lateinit var navBagBtn: Button
  private lateinit var navImeBtn: Button
  private lateinit var navDebugBtn: Button
  private lateinit var navConfigBtn: Button
  private lateinit var titleText: TextView
  private lateinit var connStateText: TextView
  private lateinit var statusText: TextView
  private lateinit var mapModeMiniBtn: Button
  private lateinit var mapModeWorldBtn: Button
  private lateinit var debugStatusText: TextView
  private lateinit var debugVerboseBtn: Button
  private lateinit var debugClearBtn: Button
  private lateinit var logText: TextView
  private lateinit var mapView: MapSurfaceView
  private lateinit var miniMapRadarView: MiniMapRadarView
  private lateinit var pageMap: View
  private lateinit var pageCharacter: View
  private lateinit var pageBag: View
  private lateinit var pageIme: View
  private lateinit var pageDebug: View
  private lateinit var pageConfig: View
  private lateinit var bagFilterAllBtn: Button
  private lateinit var bagFilterUseBtn: Button
  private lateinit var bagFilterMatBtn: Button
  private lateinit var bagFilterGearBtn: Button
  private lateinit var bagFilterOtherBtn: Button
  private lateinit var bagSummaryText: TextView
  private lateinit var bagHintText: TextView
  private lateinit var bagGrid: RecyclerView
  private lateinit var bagDetailIcon: ImageView
  private lateinit var bagDetailName: TextView
  private lateinit var bagDetailType: TextView
  private lateinit var bagDetailClass: TextView
  private lateinit var bagDetailCount: TextView
  private lateinit var bagDetailPos: TextView
  private lateinit var bagDetailId: TextView
  private lateinit var bagDetailPriceUnit: TextView
  private lateinit var bagDetailPriceStack: TextView
  private lateinit var characterPaperDoll: CharacterPaperDollView
  private lateinit var characterStatsTitle: TextView
  private lateinit var characterStatsBody: TextView
  private lateinit var characterDetailTitle: TextView
  private lateinit var characterDetailIconFrame: View
  private lateinit var characterDetailIcon: ImageView
  private lateinit var characterDetailName: TextView
  private lateinit var characterDetailBody: TextView
  private lateinit var imeText: TextView
  private lateinit var imeInput: EditText
  private lateinit var imeInsertBtn: Button
  private lateinit var imeSendBtn: Button
  private lateinit var questPanelTitle: TextView
  private lateinit var questModeTrackerBtn: Button
  private lateinit var questModeDetailBtn: Button
  private lateinit var questTrackerList: RecyclerView
  private lateinit var questDetailScroll: View
  private lateinit var questDetailTitle: TextView
  private lateinit var questDetailLevel: TextView
  private lateinit var questDetailProgress: TextView
  private lateinit var questDetailObjectives: TextView
  private lateinit var questDetailMeta: TextView
  private lateinit var questDetailFocusBtn: Button
  private lateinit var questDetailBackBtn: Button

  private var client: UdpBridgeClient? = null
  private var connected = false
  private var lastPos: PosPacket? = null
  private var lastOverlay: OverlayPacket? = null
  private var lastQuestMarkers: QuestMarkersPacket? = null
  private var lastQuestLog: QuestLogPacket? = null
  private var lastBag: BagPacket? = null
  private var lastEquip: EquipPacket? = null
  private var lastCharStats: CharacterStatsPacket? = null
  private var lastResourceScan: ResourceScanPacket? = null
  private var lastMinimapKey: MinimapKeyPacket? = null
  private var lastMinimapState: MinimapStatePacket? = null
  private var currentMinimapAssetPath: String? = null
  private var currentMinimapCompositeKey: String? = null
  private var lastPosLogMs: Long = 0L
  private var buddyLastX: Float? = null
  private var buddyLastY: Float? = null
  private var buddyLastSampleMs: Long = 0L
  private var buddyLastHp: Int = -1
  private var buddyHurtUntilMs: Long = 0L
  private var currentHost: String = ""
  private var currentPort: Int = 38442
  private var currentAssetPort: Int = AssetBridgeClient.DEFAULT_PORT
  private var remoteAssetsAvailable = false
  private var remoteIconsAvailable = false
  private var remoteUiTexturesAvailable = false
  private var uiLang: UiLang = UiLang.EN
  private var currentPage: Page = Page.MAP
  private var currentBagFilter: BagFilter = BagFilter.ALL
  private var currentQuestPanelMode: QuestPanelMode = QuestPanelMode.TRACKER
  private var currentMapMode: MapDisplayMode = MapDisplayMode.WORLD
  private var currentBagItems: List<BagItem> = emptyList()
  private var selectedBagItem: BagItem? = null
  private var selectedQuestId: Int? = null
  private var selectedQuestTitle: String? = null
  private var selectedEquipSlot: String? = null
  private var focusedQuestId: Int? = null
  private var questRows: List<QuestTrackerRow> = emptyList()
  private var visibleQuestMarkers: List<QuestMarker> = emptyList()
  private lateinit var questAdapter: QuestTrackerAdapter
  private var lastImeFocus = false
  private lateinit var bagAdapter: BagGridAdapter
  private var debugVerboseEnabled = false
  private val debugLogBuffer: ArrayDeque<DebugLogEntry> = ArrayDeque()

  // slug(zone) -> tile dir name, e.g. ELWYNN -> Elwynn
  private val tileZoneIndex = LinkedHashMap<String, String>()
  private val zoneCatalogCache = LinkedHashMap<String, ZoneTileCatalog>()
  private val zoneCatalogFilesRemote = LinkedHashMap<String, Set<String>>()
  private val bitmapCache = LinkedHashMap<String, Bitmap>(128, 0.75f, true)
  private val composedCache = LinkedHashMap<String, Bitmap>(12, 0.75f, true)
  private val mapSourceLogged = LinkedHashSet<String>()
  private val pendingAssetFetch = Collections.synchronizedSet(mutableSetOf<String>())
  private val pendingZoneListFetch = Collections.synchronizedSet(mutableSetOf<String>())
  private val pendingBagIconFetch = Collections.synchronizedSet(mutableSetOf<String>())
  private val pendingUiTextureFetch = Collections.synchronizedSet(mutableSetOf<String>())
  private val bagIconBitmapCache = LinkedHashMap<String, Bitmap>(256, 0.75f, true)
  private val uiTextureBitmapCache = LinkedHashMap<String, Bitmap>(192, 0.75f, true)
  private val equipEmptyProbeLogged = Collections.synchronizedSet(mutableSetOf<String>())
  private val uiTextureMissLogged = Collections.synchronizedSet(mutableSetOf<String>())
  private val assetCacheDir by lazy { File(cacheDir, "map_assets") }
  private val bagIconCacheDir by lazy { File(cacheDir, "bag_icons") }
  private val uiTextureCacheDir by lazy { File(cacheDir, "ui_textures") }

  private val prefs by lazy { getSharedPreferences("pipboy", MODE_PRIVATE) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    navHeaderText = findViewById(R.id.navHeaderText)
    navMapBtn = findViewById(R.id.navMapBtn)
    navCharBtn = findViewById(R.id.navCharBtn)
    navBagBtn = findViewById(R.id.navBagBtn)
    navImeBtn = findViewById(R.id.navImeBtn)
    navDebugBtn = findViewById(R.id.navDebugBtn)
    navConfigBtn = findViewById(R.id.navConfigBtn)
    titleText = findViewById(R.id.titleText)
    connStateText = findViewById(R.id.connStateText)
    pageMap = findViewById(R.id.pageMap)
    pageCharacter = findViewById(R.id.pageCharacter)
    pageBag = findViewById(R.id.pageBag)
    pageIme = findViewById(R.id.pageIme)
    pageDebug = findViewById(R.id.pageDebug)
    pageConfig = findViewById(R.id.pageConfig)
    bagFilterAllBtn = findViewById(R.id.bagFilterAllBtn)
    bagFilterUseBtn = findViewById(R.id.bagFilterUseBtn)
    bagFilterMatBtn = findViewById(R.id.bagFilterMatBtn)
    bagFilterGearBtn = findViewById(R.id.bagFilterGearBtn)
    bagFilterOtherBtn = findViewById(R.id.bagFilterOtherBtn)
    bagSummaryText = findViewById(R.id.bagSummaryText)
    bagHintText = findViewById(R.id.bagHintText)
    bagGrid = findViewById(R.id.bagGrid)
    bagDetailIcon = findViewById(R.id.bagDetailIcon)
    bagDetailName = findViewById(R.id.bagDetailName)
    bagDetailType = findViewById(R.id.bagDetailType)
    bagDetailClass = findViewById(R.id.bagDetailClass)
    bagDetailCount = findViewById(R.id.bagDetailCount)
    bagDetailPos = findViewById(R.id.bagDetailPos)
    bagDetailId = findViewById(R.id.bagDetailId)
    bagDetailPriceUnit = findViewById(R.id.bagDetailPriceUnit)
    bagDetailPriceStack = findViewById(R.id.bagDetailPriceStack)
    characterPaperDoll = findViewById(R.id.characterPaperDoll)
    characterStatsTitle = findViewById(R.id.characterStatsTitle)
    characterStatsBody = findViewById(R.id.characterStatsBody)
    characterDetailTitle = findViewById(R.id.characterDetailTitle)
    characterDetailIconFrame = findViewById(R.id.characterDetailIconFrame)
    characterDetailIcon = findViewById(R.id.characterDetailIcon)
    characterDetailName = findViewById(R.id.characterDetailName)
    characterDetailBody = findViewById(R.id.characterDetailBody)
    imeText = findViewById(R.id.imeText)
    imeInput = findViewById(R.id.imeInput)
    imeInsertBtn = findViewById(R.id.imeInsertBtn)
    imeSendBtn = findViewById(R.id.imeSendBtn)
    questPanelTitle = findViewById(R.id.questPanelTitle)
    questModeTrackerBtn = findViewById(R.id.questModeTrackerBtn)
    questModeDetailBtn = findViewById(R.id.questModeDetailBtn)
    questTrackerList = findViewById(R.id.questTrackerList)
    questDetailScroll = findViewById(R.id.questDetailScroll)
    questDetailTitle = findViewById(R.id.questDetailTitle)
    questDetailLevel = findViewById(R.id.questDetailLevel)
    questDetailProgress = findViewById(R.id.questDetailProgress)
    questDetailObjectives = findViewById(R.id.questDetailObjectives)
    questDetailMeta = findViewById(R.id.questDetailMeta)
    questDetailFocusBtn = findViewById(R.id.questDetailFocusBtn)
    questDetailBackBtn = findViewById(R.id.questDetailBackBtn)
    ipInput = findViewById(R.id.ipInput)
    portInput = findViewById(R.id.portInput)
    connectBtn = findViewById(R.id.connectBtn)
    langBtn = findViewById(R.id.langBtn)
    statusText = findViewById(R.id.statusText)
    mapModeMiniBtn = findViewById(R.id.mapModeMiniBtn)
    mapModeWorldBtn = findViewById(R.id.mapModeWorldBtn)
    debugStatusText = findViewById(R.id.debugStatusText)
    debugVerboseBtn = findViewById(R.id.debugVerboseBtn)
    debugClearBtn = findViewById(R.id.debugClearBtn)
    logText = findViewById(R.id.logText)
    mapView = findViewById(R.id.mapView)
    miniMapRadarView = findViewById(R.id.miniMapRadarView)

    ipInput.setText(prefs.getString("ip", "192.168.0.112"))
    portInput.setText(prefs.getInt("port", 38442).toString())
    currentHost = ipInput.text.toString().trim()
    currentPort = portInput.text.toString().trim().toIntOrNull() ?: 38442
    uiLang = if (prefs.getString("ui_lang", "en") == "zh") UiLang.ZH else UiLang.EN
    currentPage = Page.entries.getOrElse(prefs.getInt("current_page", 0)) { Page.MAP }
    currentMapMode = when (prefs.getString("map_mode", "world")) {
      "mini" -> MapDisplayMode.MINI
      else -> MapDisplayMode.WORLD
    }
    debugVerboseEnabled = prefs.getBoolean("debug_verbose", false)

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
    navCharBtn.setOnClickListener { switchPage(Page.CHARACTER) }
    navBagBtn.setOnClickListener { switchPage(Page.BAG) }
    navImeBtn.setOnClickListener { switchPage(Page.IME) }
    navDebugBtn.setOnClickListener { switchPage(Page.DEBUG) }
    navConfigBtn.setOnClickListener { switchPage(Page.CONFIG) }
    mapModeWorldBtn.setOnClickListener { switchMapDisplayMode(MapDisplayMode.WORLD) }
    mapModeMiniBtn.setOnClickListener { switchMapDisplayMode(MapDisplayMode.MINI) }
    debugVerboseBtn.setOnClickListener {
      debugVerboseEnabled = !debugVerboseEnabled
      prefs.edit().putBoolean("debug_verbose", debugVerboseEnabled).apply()
      refreshDebugLogView()
      updateDebugControls()
      log(
        if (debugVerboseEnabled) {
          t("debug verbose enabled", "调试详细日志已开启")
        } else {
          t("debug verbose disabled", "调试详细日志已关闭")
        }
      )
    }
    debugClearBtn.setOnClickListener {
      debugLogBuffer.clear()
      refreshDebugLogView()
      log(t("debug log cleared", "调试日志已清空"))
    }
    imeInsertBtn.setOnClickListener { sendImeText(submit = false) }
    imeSendBtn.setOnClickListener { sendImeText(submit = true) }
    questModeTrackerBtn.setOnClickListener {
      currentQuestPanelMode = QuestPanelMode.TRACKER
      updateQuestPanel()
    }
    questModeDetailBtn.setOnClickListener {
      if (selectedQuestId != null || !selectedQuestTitle.isNullOrBlank()) {
        currentQuestPanelMode = QuestPanelMode.DETAIL
      }
      updateQuestPanel()
    }
    questDetailBackBtn.setOnClickListener {
      focusedQuestId = null
      currentQuestPanelMode = QuestPanelMode.TRACKER
      updateQuestPanel()
      updateVisibleQuestMarkers()
    }
    questDetailFocusBtn.setOnClickListener {
      val sid = selectedQuestId
      if (sid != null && sid > 0) {
        focusedQuestId = if (focusedQuestId == sid) null else sid
      } else {
        focusedQuestId = null
      }
      updateQuestPanel()
      updateVisibleQuestMarkers()
    }
    mapView.onQuestMarkerTap = { marker -> onQuestMarkerTapped(marker) }
    bagFilterAllBtn.setOnClickListener { setBagFilter(BagFilter.ALL) }
    bagFilterUseBtn.setOnClickListener { setBagFilter(BagFilter.USE) }
    bagFilterMatBtn.setOnClickListener { setBagFilter(BagFilter.MAT) }
    bagFilterGearBtn.setOnClickListener { setBagFilter(BagFilter.GEAR) }
    bagFilterOtherBtn.setOnClickListener { setBagFilter(BagFilter.OTHER) }
    bagAdapter = BagGridAdapter(
      onItemTap = { item -> onBagItemTapped(item) },
      onItemDoubleTap = { item -> onBagItemDoubleTapped(item) },
      bindIcon = { imageView, item -> bindBagSlotIcon(imageView, item) }
    )
    bagGrid.layoutManager = GridLayoutManager(this, 6)
    bagGrid.adapter = bagAdapter
    characterPaperDoll.setIconBinder { imageView, slot, item ->
      bindEquipIcon(imageView, slot, item)
    }
    characterPaperDoll.onSlotTap = { slot, item ->
      onEquipSlotTapped(slot, item)
    }
    characterPaperDoll.onCharacterFrameTap = {
      onCharacterFrameTapped()
    }
    questAdapter = QuestTrackerAdapter { row ->
      onQuestRowTapped(row)
    }
    questTrackerList.layoutManager = LinearLayoutManager(this)
    questTrackerList.adapter = questAdapter
    updateBagFilterButtons()
    assetCacheDir.mkdirs()
    bagIconCacheDir.mkdirs()
    uiTextureCacheDir.mkdirs()
    tileZoneIndex.clear()
    zoneCatalogCache.clear()
    zoneCatalogFilesRemote.clear()
    applyLanguage()
    updateMapDisplayModeUi()
    switchPage(currentPage)
    log("ready")
  }

  override fun onDestroy() {
    super.onDestroy()
    disconnect()
    bitmapCache.clear()
    composedCache.clear()
    bagIconBitmapCache.clear()
    uiTextureBitmapCache.clear()
  }

  private fun connect() {
    val ip = ipInput.text.toString().trim().ifEmpty { "127.0.0.1" }
    val port = portInput.text.toString().trim().toIntOrNull() ?: 38442
    currentHost = ip
    currentPort = port
    currentAssetPort = AssetBridgeClient.DEFAULT_PORT
    remoteAssetsAvailable = false
    remoteIconsAvailable = false
    remoteUiTexturesAvailable = false

    prefs.edit()
      .putString("ip", ip)
      .putInt("port", port)
      .apply()

    val c = UdpBridgeClient(
      host = ip,
      port = port,
      onPos = { p -> runOnUiThread { onPosPacket(p) } },
      onOverlay = { o -> runOnUiThread { onOverlayPacket(o) } },
      onQuestMarkers = { q -> runOnUiThread { onQuestMarkersPacket(q) } },
      onQuestLog = { ql -> runOnUiThread { onQuestLogPacket(ql) } },
      onBag = { b -> runOnUiThread { onBagPacket(b) } },
      onEquip = { e -> runOnUiThread { onEquipPacket(e) } },
      onCharStats = { s -> runOnUiThread { onCharStatsPacket(s) } },
      onResourceScan = { rs -> runOnUiThread { onResourceScanPacket(rs) } },
      onMinimapKey = { mk -> runOnUiThread { onMinimapKeyPacket(mk) } },
      onMinimapState = { ms -> runOnUiThread { onMinimapStatePacket(ms) } },
      onAck = { msg -> runOnUiThread { logVerbose(msg); Log.d(logTag, msg) } },
      onLog = { msg -> runOnUiThread { logVerbose(msg); Log.d(logTag, msg) } }
    )

    c.start(lifecycleScope)
    client = c
    connected = true
    connectBtn.text = t("Disconnect", "断开")
    statusText.text = t("Map: waiting data", "地图: 等待数据")
    connStateText.text = statusConnectedText(ip, port)
    switchPage(Page.MAP)
    refreshRemoteMapIndex()
    refreshMiniMapRenderState()
    Log.i(logTag, "connect host=$ip port=$port")
  }

  private fun disconnect() {
    val c = client
    if (c != null) {
      lifecycleScope.launch {
        c.stop()
      }
    }
    client = null
    connected = false
    lastImeFocus = false
    lastPos = null
    lastOverlay = null
    lastQuestMarkers = null
    lastQuestLog = null
    lastBag = null
    lastEquip = null
    lastCharStats = null
    lastResourceScan = null
    lastMinimapKey = null
    lastMinimapState = null
    currentMinimapAssetPath = null
    currentMinimapCompositeKey = null
    remoteAssetsAvailable = false
    remoteIconsAvailable = false
    remoteUiTexturesAvailable = false
    zoneCatalogFilesRemote.clear()
    zoneCatalogCache.clear()
    mapSourceLogged.clear()
    pendingAssetFetch.clear()
    pendingZoneListFetch.clear()
    pendingBagIconFetch.clear()
    pendingUiTextureFetch.clear()
    bagIconBitmapCache.clear()
    uiTextureBitmapCache.clear()
    currentBagItems = emptyList()
    selectedBagItem = null
    selectedEquipSlot = null
    selectedQuestId = null
    selectedQuestTitle = null
    focusedQuestId = null
    questRows = emptyList()
    connectBtn.text = t("Connect", "连接")
    statusText.text = t("Map: waiting data", "地图: 等待数据")
    connStateText.text = t("Idle", "空闲")
    val waiting = t("debug: waiting packets", "调试: 等待数据包")
    debugStatusText.text = waiting
    updateBagPanel(null)
    updateEquipPanel(null)
    characterPaperDoll.setBuddyState(PipBoyBuddyView.State.IDLE)
    buddyLastX = null
    buddyLastY = null
    buddyLastSampleMs = 0L
    buddyLastHp = -1
    buddyHurtUntilMs = 0L
    mapView.setQuestMarkers(emptyList(), null)
    miniMapRadarView.setPosition(null)
    miniMapRadarView.setResourceScan(null)
    miniMapRadarView.setMiniMapComposite(null, null, null)
    miniMapRadarView.setMiniMapBitmap(null)
    miniMapRadarView.setMinimapState(null)
    miniMapRadarView.setMinimapKey(null)
    miniMapRadarView.setRenderActive(false)
    updateQuestPanel()
    Log.i(logTag, "disconnect")
  }

  private fun onPosPacket(p: PosPacket) {
    val nowLocal = System.currentTimeMillis()
    val prevX = buddyLastX
    val prevY = buddyLastY
    var moving = false
    if (prevX != null && prevY != null && buddyLastSampleMs > 0L) {
      val dtSec = ((nowLocal - buddyLastSampleMs).coerceAtLeast(1L)) / 1000f
      val dx = abs(p.x - prevX)
      val dy = abs(p.y - prevY)
      val speed = (dx + dy) / dtSec
      moving = speed >= BUDDY_MOVE_THRESHOLD
    }
    buddyLastX = p.x
    buddyLastY = p.y
    buddyLastSampleMs = nowLocal
    if (buddyLastHp > 0 && p.hp in 0 until buddyLastHp) {
      buddyHurtUntilMs = nowLocal + BUDDY_HURT_FLASH_MS
    }
    buddyLastHp = p.hp

    val buddyState = when {
      nowLocal < buddyHurtUntilMs -> PipBoyBuddyView.State.HURT
      moving -> PipBoyBuddyView.State.WALK
      else -> PipBoyBuddyView.State.IDLE
    }
    characterPaperDoll.setBuddyState(buddyState)

    lastPos = p
    updateMapStatus(p)
    ensureMapLoaded(p.map, p.zone)
    mapView.setPosition(p)
    miniMapRadarView.setPosition(p)
    updateVisibleQuestMarkers()
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

  private fun onQuestMarkersPacket(q: QuestMarkersPacket) {
    lastQuestMarkers = q
    updateVisibleQuestMarkers()
    updateQuestPanel()
    refreshDebugPanel()
    Log.d(logTag, "quest map=${q.map} zone=${q.zone} mapId=${q.mapId} count=${q.count} rx=${q.markers.size} ts=${q.ts}")
  }

  private fun onQuestLogPacket(packet: QuestLogPacket) {
    lastQuestLog = packet
    updateQuestPanel()
    refreshDebugPanel()
    Log.d(logTag, "quest_log count=${packet.count} rx=${packet.entries.size} ts=${packet.ts}")
  }

  private fun onBagPacket(b: BagPacket) {
    lastBag = b
    updateBagPanel(b)
    refreshDebugPanel()
    Log.d(logTag, "bag rev=${b.rev} items=${b.itemCount} rx=${b.items.size} ts=${b.ts}")
  }

  private fun onEquipPacket(packet: EquipPacket) {
    lastEquip = packet
    updateEquipPanel(packet)
    refreshDebugPanel()
    Log.d(logTag, "equip count=${packet.count} eq=${packet.equippedCount} y=${packet.yellowCount} r=${packet.redCount} ts=${packet.ts}")
  }

  private fun onCharStatsPacket(packet: CharacterStatsPacket) {
    lastCharStats = packet
    updateCharacterStatsPanel()
    refreshDebugPanel()
    Log.d(logTag, "char_stats count=${packet.count} rx=${packet.rows.size} ts=${packet.ts}")
  }

  private fun onResourceScanPacket(packet: ResourceScanPacket) {
    lastResourceScan = packet
    miniMapRadarView.setResourceScan(packet)
    updateMiniMapTexture()
    refreshDebugPanel()
    Log.d(logTag, "gobj_scan count=${packet.count} scanned=${packet.scanned} nodes=${packet.nodes.size} ts=${packet.ts}")
  }

  private fun onMinimapKeyPacket(packet: MinimapKeyPacket) {
    lastMinimapKey = packet
    if (lastMinimapState != null) {
      return
    }
    miniMapRadarView.setMinimapKey(packet)
    val normalizedAsset = packet.asset.trim()
    if (normalizedAsset.isNotEmpty()) {
      currentMinimapAssetPath = "minimap_tiles/$normalizedAsset"
      updateMiniMapTexture()
    }
    logVerbose("minimap_key zone=${packet.zone} tile=${packet.tile} asset=${packet.asset}")
    Log.d(logTag, "minimap_key zone=${packet.zone} tile=${packet.tile} asset=${packet.asset} ts=${packet.ts}")
  }

  private fun onMinimapStatePacket(packet: MinimapStatePacket) {
    lastMinimapState = packet
    miniMapRadarView.setMinimapState(packet)
    val normalizedAsset = packet.asset.trim()
    if (normalizedAsset.isNotEmpty()) {
      currentMinimapAssetPath = "minimap_tiles/$normalizedAsset"
      if (!updateMiniMapCompositeTexture(force = false)) {
        updateMiniMapTexture()
      }
    }
    logVerbose("minimap_state zone=${packet.zone} tile=${packet.tile} asset=${packet.asset} wx=${packet.playerWx} wy=${packet.playerWy}")
    Log.d(logTag, "minimap_state zone=${packet.zone} tile=${packet.tile} asset=${packet.asset} ts=${packet.ts}")
  }

  private fun updateMiniMapTexture() {
    val assetPath = currentMinimapAssetPath ?: run {
      miniMapRadarView.setMiniMapBitmap(null)
      return
    }
    val bmp = loadBitmapCached(assetPath)
    miniMapRadarView.setMiniMapBitmap(bmp)
  }

  private fun updateMiniMapCompositeTexture(force: Boolean = false): Boolean {
    val state = lastMinimapState ?: return false
    val zonePrefix = minimapZonePrefix(state) ?: return false
    val centerX = state.tileX
    val centerY = state.tileY
    val key = "$zonePrefix:$centerX:$centerY"
    if (!force && key == currentMinimapCompositeKey) {
      return true
    }

    val tiles = arrayOfNulls<Bitmap>(9)
    var any = false
    var tileW = 0
    var tileH = 0
    var idx = 0
    for (dy in -1..1) {
      for (dx in -1..1) {
        val tx = centerX + dx
        val ty = centerY + dy
        val path = "minimap_tiles/${zonePrefix}_map${tx}_${ty}.png"
        val bmp = loadBitmapCached(path)
        tiles[idx++] = bmp
        if (bmp != null && !bmp.isRecycled) {
          any = true
          if (tileW <= 0 || tileH <= 0) {
            tileW = bmp.width
            tileH = bmp.height
          }
        }
      }
    }
    if (!any || tileW <= 0 || tileH <= 0) {
      return false
    }

    val out = Bitmap.createBitmap(tileW * 3, tileH * 3, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    idx = 0
    for (row in 0 until 3) {
      for (col in 0 until 3) {
        val bmp = tiles[idx++]
        if (bmp == null || bmp.isRecycled) continue
        val dx = col * tileW
        val dy = row * tileH
        canvas.drawBitmap(bmp, null, Rect(dx, dy, dx + tileW, dy + tileH), null)
      }
    }

    miniMapRadarView.setMiniMapComposite(out, centerX - 1, centerY - 1)
    currentMinimapCompositeKey = key
    return true
  }

  private fun minimapZonePrefix(packet: MinimapStatePacket): String? {
    val asset = packet.asset.trim()
    if (asset.isNotEmpty()) {
      val idx = asset.indexOf("_map", ignoreCase = true)
      if (idx > 0) {
        return asset.substring(0, idx)
      }
    }
    val zone = packet.zone.trim()
    if (zone.isEmpty()) return null
    return zone
  }

  private fun refreshDebugPanel() {
    val p = lastPos
    val o = lastOverlay
    val q = lastQuestMarkers
    val ql = lastQuestLog
    val b = lastBag
    val e = lastEquip
    val rs = lastResourceScan
    val ms = lastMinimapState
    val now = System.currentTimeMillis()
    val overlayAge = if (o != null && o.ts > 0L) (now - o.ts).coerceAtLeast(0L) else -1L
    val questAge = if (q != null && q.ts > 0L) (now - q.ts).coerceAtLeast(0L) else -1L
    val questLogAge = if (ql != null && ql.ts > 0L) (now - ql.ts).coerceAtLeast(0L) else -1L
    val bagAge = if (b != null && b.ts > 0L) (now - b.ts).coerceAtLeast(0L) else -1L
    val equipAge = if (e != null && e.ts > 0L) (now - e.ts).coerceAtLeast(0L) else -1L

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
    val bagLine = if (b == null) {
      t("bag=waiting", "bag=等待中")
    } else {
      val age = if (bagAge >= 0) bagAge else 0
      if (uiLang == UiLang.ZH) {
        "bag rev=${b.rev} 数量=${b.itemCount} 接收=${b.items.size} 延迟=${age}ms"
      } else {
        "bag rev=${b.rev} items=${b.itemCount} rx=${b.items.size} age=${age}ms"
      }
    }
    val equipLine = if (e == null) {
      t("equip=waiting", "equip=等待中")
    } else {
      val age = if (equipAge >= 0) equipAge else 0
      if (uiLang == UiLang.ZH) {
        "equip 总槽位=${e.count} 已装备=${e.equippedCount} 黄=${e.yellowCount} 红=${e.redCount} 延迟=${age}ms"
      } else {
        "equip slots=${e.count} equipped=${e.equippedCount} yellow=${e.yellowCount} red=${e.redCount} age=${age}ms"
      }
    }
    val resourceLine = if (rs == null) {
      t("resource=waiting", "resource=等待中")
    } else {
      val nearest = rs.nodes.firstOrNull()
      if (uiLang == UiLang.ZH) {
        "resource count=${rs.count}/${rs.scanned} 最近=${nearest?.name ?: "-"}"
      } else {
        "resource count=${rs.count}/${rs.scanned} nearest=${nearest?.name ?: "-"}"
      }
    }
    val minimapLine = if (ms == null) {
      t("minimap_state=waiting", "minimap_state=等待中")
    } else {
      if (uiLang == UiLang.ZH) {
        "minimap_state tile=${ms.tile} 资源=${ms.asset} wx=${"%.1f".format(ms.playerWx ?: 0f)} wy=${"%.1f".format(ms.playerWy ?: 0f)}"
      } else {
        "minimap_state tile=${ms.tile} asset=${ms.asset} wx=${"%.1f".format(ms.playerWx ?: 0f)} wy=${"%.1f".format(ms.playerWy ?: 0f)}"
      }
    }
    val questLine = if (q == null) {
      t("quest=waiting", "quest=等待中")
    } else {
      val age = if (questAge >= 0) questAge else 0
      if (uiLang == UiLang.ZH) {
        "quest=${q.map.ifBlank { q.zone }}/${q.zone} 数量=${q.count} 接收=${q.markers.size} 延迟=${age}ms"
      } else {
        "quest=${q.map.ifBlank { q.zone }}/${q.zone} count=${q.count} rx=${q.markers.size} age=${age}ms"
      }
    }
    val questLogLine = if (ql == null) {
      t("questlog=waiting", "questlog=等待中")
    } else {
      val age = if (questLogAge >= 0) questLogAge else 0
      if (uiLang == UiLang.ZH) {
        "questlog count=${ql.count} rx=${ql.entries.size} 延迟=${age}ms"
      } else {
        "questlog count=${ql.count} rx=${ql.entries.size} age=${age}ms"
      }
    }
    val text = "facing_src=$facingSrc\n$overlayLine\n$questLine\n$questLogLine\n$bagLine\n$equipLine\n$resourceLine\n$minimapLine\n$imeLine"
    debugStatusText.text = text
  }

  private fun updateVisibleQuestMarkers() {
    val p = lastPos
    val q = lastQuestMarkers
    if (p == null || q == null) {
      visibleQuestMarkers = emptyList()
      mapView.setQuestMarkers(emptyList(), focusedQuestId)
      return
    }

    val posMap = slug(p.map)
    val posZone = slug(p.zone)
    val markMap = slug(q.map)
    val markZone = slug(q.zone)
    val matches = (posMap.isNotEmpty() && (posMap == markMap || posMap == markZone)) ||
      (posZone.isNotEmpty() && (posZone == markMap || posZone == markZone))

    visibleQuestMarkers = if (matches) q.markers else emptyList()
    mapView.setQuestMarkers(visibleQuestMarkers, focusedQuestId)
  }

  private fun switchPage(page: Page) {
    currentPage = page
    prefs.edit().putInt("current_page", page.ordinal).apply()

    pageMap.visibility = if (page == Page.MAP) View.VISIBLE else View.GONE
    pageCharacter.visibility = if (page == Page.CHARACTER) View.VISIBLE else View.GONE
    pageBag.visibility = if (page == Page.BAG) View.VISIBLE else View.GONE
    pageIme.visibility = if (page == Page.IME) View.VISIBLE else View.GONE
    pageDebug.visibility = if (page == Page.DEBUG) View.VISIBLE else View.GONE
    pageConfig.visibility = if (page == Page.CONFIG) View.VISIBLE else View.GONE

    navMapBtn.isSelected = page == Page.MAP
    navCharBtn.isSelected = page == Page.CHARACTER
    navBagBtn.isSelected = page == Page.BAG
    navImeBtn.isSelected = page == Page.IME
    navDebugBtn.isSelected = page == Page.DEBUG
    navConfigBtn.isSelected = page == Page.CONFIG

    titleText.text = t("TurtleWoW Companion", "乌龟服伴侣") + " · " + pageLabel(page)
    if (page == Page.IME) {
      showImeKeyboard()
    }
    refreshMiniMapRenderState()
  }

  private fun applyNavLabels() {
    val map = t("MAP", "地图")
    val char = t("CHAR", "角色")
    val bag = t("BAG", "背包")
    val ime = "IME"
    val debug = t("DEBUG", "调试")
    val config = t("CONFIG", "设置")

    navMapBtn.text = map
    navCharBtn.text = char
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
    bagFilterAllBtn.text = t("ALL", "全部")
    bagFilterUseBtn.text = t("USE", "消耗")
    bagFilterMatBtn.text = t("MAT", "材料")
    bagFilterGearBtn.text = t("GEAR", "装备")
    bagFilterOtherBtn.text = t("OTHER", "其他")
    bagHintText.text = t("Tap to view details · Double tap to use", "单击查看详情 · 双击使用")
    imeText.text = t("IME Bridge", "输入法桥接")
    imeInput.hint = t("Type text here...", "在这里输入文本...")
    imeInsertBtn.text = t("Insert", "插入")
    imeSendBtn.text = t("Insert + Send", "插入并发送")
    characterStatsTitle.text = t("STATS", "属性")
    characterDetailTitle.text = t("EQUIPMENT DETAIL", "装备详情")
    updateDebugControls()
    updateCharacterStatsPanel()
    applyMapModeLabels()
    applyNavLabels()

    connStateText.text = if (connected) {
      statusConnectedText(currentHost, currentPort)
    } else {
      t("Idle", "空闲")
    }
    val p = lastPos
    if (p != null) {
      updateMapStatus(p)
    } else {
      statusText.text = t("Map: waiting data", "地图: 等待数据")
    }
    updateBagFilterButtons()
    updateBagPanel(lastBag)
    updateEquipPanel(lastEquip)
    updateQuestPanel()
    refreshDebugPanel()
    refreshDebugLogView()
  }

  private fun applyMapModeLabels() {
    mapModeMiniBtn.text = "MINI"
    mapModeWorldBtn.text = "WORLD"
  }

  private fun switchMapDisplayMode(mode: MapDisplayMode) {
    if (currentMapMode == mode) return
    currentMapMode = mode
    prefs.edit().putString("map_mode", if (mode == MapDisplayMode.MINI) "mini" else "world").apply()
    updateMapDisplayModeUi()
  }

  private fun updateMapDisplayModeUi() {
    val world = currentMapMode == MapDisplayMode.WORLD
    mapView.visibility = if (world) View.VISIBLE else View.GONE
    miniMapRadarView.visibility = if (world) View.GONE else View.VISIBLE
    mapModeWorldBtn.isSelected = world
    mapModeMiniBtn.isSelected = !world
    if (!world) {
      updateMiniMapTexture()
    }
    refreshMiniMapRenderState()
  }

  private fun refreshMiniMapRenderState() {
    val active = connected && currentPage == Page.MAP && currentMapMode == MapDisplayMode.MINI
    miniMapRadarView.setRenderActive(active)
  }

  private fun updateDebugControls() {
    debugVerboseBtn.text = if (debugVerboseEnabled) {
      t("Verbose ON", "详细 开")
    } else {
      t("Verbose OFF", "详细 关")
    }
    debugVerboseBtn.isSelected = debugVerboseEnabled
    debugClearBtn.text = t("Clear", "清空")
  }

  private fun statusConnectedText(ip: String, port: Int): String {
    return t("Connected -> $ip:$port", "已连接 -> $ip:$port")
  }

  private fun t(en: String, zh: String): String {
    return if (uiLang == UiLang.ZH) zh else en
  }

  private fun dp(value: Int): Int {
    val px = value * resources.displayMetrics.density
    return px.toInt().coerceAtLeast(1)
  }

  private fun pageLabel(page: Page): String {
    return when (page) {
      Page.MAP -> t("Map", "地图")
      Page.CHARACTER -> t("Character", "角色")
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

  private fun onQuestRowTapped(row: QuestTrackerRow) {
    selectedQuestId = row.questId.takeIf { it > 0 }
    selectedQuestTitle = row.title
    currentQuestPanelMode = QuestPanelMode.DETAIL
    focusedQuestId = selectedQuestId
    updateQuestPanel()
    updateVisibleQuestMarkers()
  }

  private fun onQuestMarkerTapped(marker: QuestMarker) {
    val packet = lastQuestLog
    if (packet == null || packet.entries.isEmpty()) return
    var entry = packet.entries.firstOrNull { marker.questId > 0 && it.questId == marker.questId }
    if (entry == null && marker.title.isNotBlank()) {
      entry = packet.entries.firstOrNull { it.title == marker.title }
    }
    if (entry == null) return
    selectedQuestId = entry.questId.takeIf { it > 0 }
    selectedQuestTitle = entry.title
    currentQuestPanelMode = QuestPanelMode.DETAIL
    focusedQuestId = selectedQuestId
    updateQuestPanel()
    updateVisibleQuestMarkers()
  }

  private fun updateQuestPanel() {
    questPanelTitle.text = t("QUEST TRACKER", "任务追踪")
    questModeTrackerBtn.text = t("TRACKER", "追踪")
    questModeDetailBtn.text = t("DETAIL", "详情")
    questDetailBackBtn.text = t("Back", "返回")

    questRows = buildQuestRows()
    questAdapter.submitRows(questRows)
    questAdapter.setSelectedQuest(selectedQuestId)

    val hasSelection = selectedQuestId != null || !selectedQuestTitle.isNullOrBlank()
    if (currentQuestPanelMode == QuestPanelMode.DETAIL && !hasSelection) {
      currentQuestPanelMode = QuestPanelMode.TRACKER
    }
    val inDetail = currentQuestPanelMode == QuestPanelMode.DETAIL
    questTrackerList.visibility = if (inDetail) View.GONE else View.VISIBLE
    questDetailScroll.visibility = if (inDetail) View.VISIBLE else View.GONE
    questModeTrackerBtn.isSelected = !inDetail
    questModeDetailBtn.isSelected = inDetail

    if (!inDetail) return

    val entry = findQuestEntryBySelection()
    if (entry == null) {
      questDetailTitle.text = t("No quest selected", "未选择任务")
      questDetailTitle.setTextColor(Color.parseColor("#F4E6C8"))
      questDetailLevel.text = t("Level: -", "等级: -")
      questDetailProgress.text = t("Progress: -", "进度: -")
      questDetailObjectives.text = "-"
      questDetailMeta.text = t("Tap a tracker item or map marker.", "点击任务条目或地图标记查看详情。")
      questDetailFocusBtn.text = t("Focus", "聚焦")
      return
    }

    val done = entry.objectives.count { it.done }
    val total = entry.objectives.size
    val progress = if (total <= 0) t("No objectives", "无目标") else "$done/$total"
    val status = if (entry.complete) t("Ready to turn in", "可交付") else t("In progress", "进行中")

    questDetailTitle.text = entry.title
    questDetailTitle.setTextColor(questDifficultyColor(entry.level))
    questDetailLevel.text = if (entry.level > 0) {
      if (uiLang == UiLang.ZH) "等级: ${entry.level}" else "Level: ${entry.level}"
    } else {
      if (uiLang == UiLang.ZH) "等级: -" else "Level: -"
    }
    questDetailProgress.text = if (uiLang == UiLang.ZH) "进度: $progress · $status" else "Progress: $progress · $status"
    questDetailObjectives.text = if (entry.objectives.isEmpty()) {
      t("No objective text.", "没有目标文本。")
    } else {
      entry.objectives.joinToString("\n") { o ->
        val bullet = if (o.done) "✓" else "•"
        "$bullet ${o.text}"
      }
    }
    val markerCount = visibleMarkerCountForQuest(entry.questId, entry.title)
    questDetailMeta.text = if (uiLang == UiLang.ZH) {
      "QuestID=${entry.questId} · Marker=${markerCount}"
    } else {
      "QuestID=${entry.questId} · Markers=${markerCount}"
    }

    val focused = (entry.questId > 0 && focusedQuestId == entry.questId)
    questDetailFocusBtn.text = if (focused) t("Clear Focus", "取消聚焦") else t("Focus", "聚焦")
  }

  private fun visibleMarkerCountForQuest(questId: Int, title: String): Int {
    val markers = visibleQuestMarkers
    return markers.count { m ->
      (questId > 0 && m.questId == questId) || (questId <= 0 && title.isNotBlank() && m.title == title)
    }
  }

  private fun findQuestEntryBySelection(): QuestLogEntry? {
    val packet = lastQuestLog ?: return null
    val id = selectedQuestId
    if (id != null && id > 0) {
      packet.entries.firstOrNull { it.questId == id }?.let { return it }
    }
    val title = selectedQuestTitle
    if (!title.isNullOrBlank()) {
      packet.entries.firstOrNull { it.title == title }?.let { return it }
    }
    return null
  }

  private fun buildQuestRows(): List<QuestTrackerRow> {
    val packet = lastQuestLog ?: return emptyList()
    if (packet.entries.isEmpty()) return emptyList()
    val visible = visibleQuestMarkers
    val markerCountByQuestId = HashMap<Int, Int>()
    val markerCountByTitle = HashMap<String, Int>()
    for (m in visible) {
      if (m.questId > 0) {
        markerCountByQuestId[m.questId] = (markerCountByQuestId[m.questId] ?: 0) + 1
      } else if (m.title.isNotBlank()) {
        markerCountByTitle[m.title] = (markerCountByTitle[m.title] ?: 0) + 1
      }
    }

    val rows = ArrayList<QuestTrackerRow>(packet.entries.size)
    for (entry in packet.entries) {
      val done = entry.objectives.count { it.done }
      val total = entry.objectives.size
      val progress = if (total <= 0) "-" else "$done/$total"
      val markerCount = if (entry.questId > 0) {
        markerCountByQuestId[entry.questId] ?: 0
      } else {
        markerCountByTitle[entry.title] ?: 0
      }
      val status = if (entry.complete) t("turn-in", "可交") else t("active", "进行中")
      val levelText = if (entry.level > 0) "${entry.level}" else "?"
      val meta = if (uiLang == UiLang.ZH) {
        "Lv$levelText · 进度 $progress · 标记 $markerCount · $status"
      } else {
        "Lv$levelText · $progress · markers $markerCount · $status"
      }
      rows.add(
        QuestTrackerRow(
          questId = entry.questId,
          title = entry.title,
          meta = meta,
          complete = entry.complete,
          titleColor = questDifficultyColor(entry.level)
        )
      )
    }

    rows.sortWith(
      compareBy<QuestTrackerRow> { if (it.complete) 1 else 0 }
        .thenBy { it.title }
    )
    return rows
  }

  private fun questDifficultyColor(questLevel: Int): Int {
    val playerLevel = (lastPos?.level ?: 0).coerceAtLeast(1)
    val level = questLevel.coerceAtLeast(1)
    val delta = level - playerLevel
    return when {
      delta >= 5 -> Color.parseColor("#FF4040") // red
      delta >= 3 -> Color.parseColor("#FF8040") // orange
      delta >= -2 -> Color.parseColor("#FFD100") // yellow
      delta >= -6 -> Color.parseColor("#40C840") // green
      else -> Color.parseColor("#A0A0A0") // gray
    }
  }

  private fun updateEquipPanel(packet: EquipPacket?) {
    if (packet == null) {
      characterPaperDoll.setItems(emptyList())
      characterPaperDoll.setSelectedSlot(null)
      updateCharacterStatsPanel()
      characterDetailName.text = t("No slot selected", "未选择槽位")
      characterDetailBody.text = t("Tap a slot around the character to view details.", "点击角色周围的装备槽查看详情。")
      characterDetailIcon.setImageResource(R.drawable.bag_slot_placeholder)
      applyDetailIconFrameBorder(Color.parseColor("#6B5536"), 1)
      return
    }

    val visibleItems = packet.items.filter { CHARACTER_VISIBLE_SLOT_SET.contains(it.slot) }
    probeEquipEmptyTextures(visibleItems)
    characterPaperDoll.setItems(visibleItems)
    if (selectedEquipSlot.isNullOrBlank() || !CHARACTER_VISIBLE_SLOT_SET.contains(selectedEquipSlot)) {
      selectedEquipSlot = visibleItems.firstOrNull { it.equipped }?.slot ?: "HeadSlot"
    }
    val selected = visibleItems.firstOrNull { it.slot == selectedEquipSlot }
    characterPaperDoll.setSelectedSlot(selectedEquipSlot)
    updateEquipDetailPanel(selectedEquipSlot ?: "", selected)

    updateCharacterStatsPanel()
  }

  private fun updateCharacterStatsPanel() {
    val packet = lastCharStats
    if (packet == null || packet.rows.isEmpty()) {
      characterStatsBody.text = if (uiLang == UiLang.ZH) {
        "等待 BetterCharacterStats 推送..."
      } else {
        "Waiting BetterCharacterStats feed..."
      }
      return
    }

    val rows = packet.rows
      .filter { it.label.isNotBlank() && it.value.isNotBlank() }
    if (rows.isEmpty()) {
      characterStatsBody.text = if (uiLang == UiLang.ZH) {
        "等待 BetterCharacterStats 推送..."
      } else {
        "Waiting BetterCharacterStats feed..."
      }
      return
    }
    characterStatsBody.text = rows.joinToString("\n") { "${it.label}: ${it.value}" }
  }

  private fun onEquipSlotTapped(slot: String, item: EquipItem?) {
    selectedEquipSlot = slot
    characterPaperDoll.setSelectedSlot(slot)
    updateEquipDetailPanel(slot, item)
  }

  private fun onCharacterFrameTapped() {
    val c = client
    if (c == null) {
      log(t("autorun toggle skipped: not connected", "切换自动跑步失败: 未连接"))
      return
    }
    c.sendAutoRunToggle()
    log(t("autorun toggle requested (NumLock)", "已请求切换自动跑步 (NumLock)"))
  }

  private fun updateEquipDetailPanel(slot: String, item: EquipItem?) {
    val slotLabel = formatEquipSlotName(slot)
    if (item == null || !item.equipped) {
      characterDetailName.text = t("Empty", "空槽")
      characterDetailName.setTextColor(Color.parseColor("#C7B28A"))
      characterDetailBody.text = if (uiLang == UiLang.ZH) {
        "槽位: $slotLabel\n未装备物品"
      } else {
        "Slot: $slotLabel\nNo item equipped"
      }
      bindEquipIcon(characterDetailIcon, slot, item)
      applyDetailIconFrameBorder(Color.parseColor("#6B5536"), 1)
      return
    }

    val qualityName = qualityName(item.quality)
    val durabilityLine = if (item.durabilityPct >= 0) {
      if (uiLang == UiLang.ZH) {
        "耐久: ${item.durabilityCur}/${item.durabilityMax} (${item.durabilityPct}%)"
      } else {
        "Durability: ${item.durabilityCur}/${item.durabilityMax} (${item.durabilityPct}%)"
      }
    } else {
      t("Durability: N/A", "耐久: 无")
    }
    val classLine = buildClassLabelFromEquip(item)
    val equipLoc = item.equipLoc?.takeIf { it.isNotBlank() } ?: "-"
    val statsLine = formatEquipStats(item.stats)
    characterDetailName.text = item.name?.takeIf { it.isNotBlank() } ?: "Item #${item.itemId}"
    characterDetailName.setTextColor(qualityTextColor(item.quality))
    characterDetailBody.text = if (uiLang == UiLang.ZH) {
      "槽位: $slotLabel\n品质: $qualityName\n类型: $classLine\n装备位: $equipLoc\n$durabilityLine\nItem ID: ${item.itemId}\n属性:\n$statsLine"
    } else {
      "Slot: $slotLabel\nQuality: $qualityName\nType: $classLine\nEquipLoc: $equipLoc\n$durabilityLine\nItem ID: ${item.itemId}\nStats:\n$statsLine"
    }
    bindEquipIcon(characterDetailIcon, slot, item)
    applyDetailIconFrameBorder(qualityTextColor(item.quality), 2)
  }

  private fun applyDetailIconFrameBorder(color: Int, widthDp: Int) {
    characterDetailIconFrame.background = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(5).toFloat()
      setColor(Color.parseColor("#1A130E"))
      setStroke(dp(widthDp.coerceAtLeast(1)), color)
    }
  }

  private fun bindEquipIcon(imageView: ImageView, slot: String, item: EquipItem?) {
    val fallbackUi = defaultEmptyTextureForSlot(slot)
    val bmp = when {
      item == null -> {
        loadUiTextureBitmap(fallbackUi)
      }
      item.equipped -> loadBagIconBitmap(item.iconTex)
      else -> {
        val preferred = item.emptyTex
        loadUiTextureBitmap(preferred)
          ?: loadUiTextureBitmap(fallbackUi)
      }
    }
    if (bmp != null && !bmp.isRecycled) {
      imageView.setImageBitmap(bmp)
    } else {
      imageView.setImageResource(R.drawable.bag_slot_placeholder)
    }
  }

  private fun formatEquipSlotName(slot: String): String {
    return when (slot.uppercase(Locale.US)) {
      "HEADSLOT" -> t("Head", "头部")
      "NECKSLOT" -> t("Neck", "颈部")
      "SHOULDERSLOT" -> t("Shoulder", "肩部")
      "BACKSLOT" -> t("Back", "披风")
      "CHESTSLOT" -> t("Chest", "胸部")
      "SHIRTSLOT" -> t("Shirt", "衬衣")
      "TABARDSLOT" -> t("Tabard", "战袍")
      "WRISTSLOT" -> t("Wrist", "护腕")
      "HANDSSLOT" -> t("Hands", "手部")
      "WAISTSLOT" -> t("Waist", "腰部")
      "LEGSSLOT" -> t("Legs", "腿部")
      "FEETSLOT" -> t("Feet", "脚部")
      "FINGER0SLOT" -> t("Ring 1", "戒指1")
      "FINGER1SLOT" -> t("Ring 2", "戒指2")
      "TRINKET0SLOT" -> t("Trinket 1", "饰品1")
      "TRINKET1SLOT" -> t("Trinket 2", "饰品2")
      "MAINHANDSLOT" -> t("Main Hand", "主手")
      "SECONDARYHANDSLOT" -> t("Off Hand", "副手")
      "RANGEDSLOT" -> t("Ranged", "远程")
      "AMMOSLOT" -> t("Ammo", "弹药")
      else -> slot
    }
  }

  private fun defaultEmptyTextureForSlot(slot: String): String {
    val stem = when (slot.uppercase(Locale.US)) {
      "HEADSLOT" -> "UI-PaperDoll-Slot-Head"
      "NECKSLOT" -> "UI-PaperDoll-Slot-Neck"
      "SHOULDERSLOT" -> "UI-PaperDoll-Slot-Shoulder"
      "SHIRTSLOT" -> "UI-PaperDoll-Slot-Shirt"
      "CHESTSLOT" -> "UI-PaperDoll-Slot-Chest"
      "WAISTSLOT" -> "UI-PaperDoll-Slot-Waist"
      "LEGSSLOT" -> "UI-PaperDoll-Slot-Legs"
      "FEETSLOT" -> "UI-PaperDoll-Slot-Feet"
      "WRISTSLOT" -> "UI-PaperDoll-Slot-Wrists"
      "HANDSSLOT" -> "UI-PaperDoll-Slot-Hands"
      "FINGER0SLOT", "FINGER1SLOT" -> "UI-PaperDoll-Slot-Finger"
      "TRINKET0SLOT", "TRINKET1SLOT" -> "UI-PaperDoll-Slot-Trinket"
      "BACKSLOT" -> "UI-PaperDoll-Slot-Chest"
      "MAINHANDSLOT" -> "UI-PaperDoll-Slot-MainHand"
      "SECONDARYHANDSLOT" -> "UI-PaperDoll-Slot-SecondaryHand"
      "RANGEDSLOT" -> "UI-PaperDoll-Slot-Ranged"
      "AMMOSLOT" -> "UI-PaperDoll-Slot-Ammo"
      "TABARDSLOT" -> "UI-PaperDoll-Slot-Tabard"
      else -> "UI-PaperDoll-Slot-Head"
    }
    return "Interface/PaperDoll/$stem"
  }

  private fun buildClassLabelFromEquip(item: EquipItem): String {
    val cls = item.itemClass?.trim().orEmpty()
    val sub = item.itemSubClass?.trim().orEmpty()
    return when {
      cls.isNotEmpty() && sub.isNotEmpty() -> "$cls / $sub"
      cls.isNotEmpty() -> cls
      sub.isNotEmpty() -> sub
      else -> "-"
    }
  }

  private fun qualityName(quality: Int): String {
    return when (quality) {
      0 -> t("Poor", "粗糙")
      1 -> t("Common", "普通")
      2 -> t("Uncommon", "优秀")
      3 -> t("Rare", "精良")
      4 -> t("Epic", "史诗")
      5 -> t("Legendary", "传说")
      else -> t("Unknown", "未知")
    }
  }

  private fun qualityTextColor(quality: Int): Int {
    return when {
      quality >= 5 -> Color.parseColor("#FF8000")
      quality == 4 -> Color.parseColor("#A335EE")
      quality == 3 -> Color.parseColor("#0070DD")
      quality == 2 -> Color.parseColor("#1EFF00")
      quality == 1 -> Color.parseColor("#FFFFFF")
      else -> Color.parseColor("#9D9D9D")
    }
  }

  private fun formatEquipStats(stats: Map<String, Int>): String {
    if (stats.isEmpty()) {
      return t("-", "-")
    }
    val lines = ArrayList<String>(stats.size)
    for ((key, value) in stats.toSortedMap()) {
      if (value == 0) continue
      val label = statLabel(key)
      val sign = if (value > 0) "+" else ""
      lines.add("$label $sign$value")
    }
    return if (lines.isEmpty()) t("-", "-") else lines.joinToString("\n")
  }

  private fun statLabel(key: String): String {
    val normalized = key.uppercase(Locale.US)
    val mapped = when (normalized) {
      "ITEM_MOD_STRENGTH_SHORT" -> t("Strength", "力量")
      "ITEM_MOD_AGILITY_SHORT" -> t("Agility", "敏捷")
      "ITEM_MOD_STAMINA_SHORT" -> t("Stamina", "耐力")
      "ITEM_MOD_INTELLECT_SHORT" -> t("Intellect", "智力")
      "ITEM_MOD_SPIRIT_SHORT" -> t("Spirit", "精神")
      "ITEM_MOD_ARMOR_SHORT" -> t("Armor", "护甲")
      "ITEM_MOD_ATTACK_POWER_SHORT" -> t("Attack Power", "攻击强度")
      "ITEM_MOD_RANGED_ATTACK_POWER_SHORT" -> t("Ranged AP", "远程攻强")
      "ITEM_MOD_SPELL_POWER_SHORT" -> t("Spell Power", "法术强度")
      "ITEM_MOD_SPELL_HEALING_DONE_SHORT" -> t("Healing", "治疗效果")
      "ITEM_MOD_HEALTH_SHORT" -> t("Health", "生命")
      "ITEM_MOD_MANA_SHORT" -> t("Mana", "法力")
      "ITEM_MOD_DEFENSE_SKILL_RATING_SHORT" -> t("Defense", "防御等级")
      "STR" -> t("Strength", "力量")
      "AGI" -> t("Agility", "敏捷")
      "STA" -> t("Stamina", "耐力")
      "INT" -> t("Intellect", "智力")
      "SPI" -> t("Spirit", "精神")
      "ARMOR" -> t("Armor", "护甲")
      "AP" -> t("Attack Power", "攻击强度")
      "RANGEDAP" -> t("Ranged AP", "远程攻强")
      "SPELLDMG", "DMG" -> t("Spell Power", "法术强度")
      "HEAL" -> t("Healing", "治疗效果")
      "HP" -> t("Health", "生命")
      "MANA" -> t("Mana", "法力")
      else -> null
    }
    if (mapped != null) return mapped

    val compact = normalized
      .removePrefix("ITEM_MOD_")
      .removeSuffix("_SHORT")
      .replace('_', ' ')
      .trim()
    return if (compact.isEmpty()) key else compact.lowercase(Locale.US)
      .split(' ')
      .filter { it.isNotBlank() }
      .joinToString(" ") { part ->
        part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString() }
      }
  }

  private fun setBagFilter(filter: BagFilter) {
    if (currentBagFilter == filter) return
    currentBagFilter = filter
    updateBagFilterButtons()
    updateBagPanel(lastBag)
  }

  private fun updateBagFilterButtons() {
    bagFilterAllBtn.isSelected = currentBagFilter == BagFilter.ALL
    bagFilterUseBtn.isSelected = currentBagFilter == BagFilter.USE
    bagFilterMatBtn.isSelected = currentBagFilter == BagFilter.MAT
    bagFilterGearBtn.isSelected = currentBagFilter == BagFilter.GEAR
    bagFilterOtherBtn.isSelected = currentBagFilter == BagFilter.OTHER
  }

  private fun onBagItemTapped(item: BagItem) {
    selectedBagItem = item
    bagAdapter.setSelected(item)
    updateBagDetail(item)
  }

  private fun onBagItemDoubleTapped(item: BagItem) {
    val c = client
    if (c == null) {
      log(t("item use skipped: not connected", "使用物品失败: 未连接"))
      return
    }
    c.sendItemUse(item.bag, item.slot, item.itemId)
    log(
      if (uiLang == UiLang.ZH) {
        "请求使用物品: bag=${item.bag} slot=${item.slot} id=${item.itemId}"
      } else {
        "item use requested: bag=${item.bag} slot=${item.slot} id=${item.itemId}"
      }
    )
  }

  private fun updateBagPanel(packet: BagPacket?) {
    if (packet == null) {
      bagSummaryText.text = t("Bag: waiting data", "背包: 等待数据")
      currentBagItems = emptyList()
      selectedBagItem = null
      bagAdapter.submitItems(emptyList())
      bagAdapter.setSelected(null)
      updateBagDetail(null)
      return
    }

    currentBagItems = packet.items.sortedWith(compareBy<BagItem> { it.bag }.thenBy { it.slot })
    val filteredItems = currentBagItems.filter { matchesBagFilter(it, currentBagFilter) }

    if (selectedBagItem != null) {
      selectedBagItem = currentBagItems.firstOrNull { sameBagSlot(it, selectedBagItem!!) }
    }
    if (selectedBagItem == null || filteredItems.none { sameBagSlot(it, selectedBagItem!!) }) {
      selectedBagItem = filteredItems.firstOrNull()
    }

    bagSummaryText.text = if (uiLang == UiLang.ZH) {
      "背包 ${packet.itemCount} 项 · 筛选 ${filteredItems.size} 项 · rev=${packet.rev}"
    } else {
      "Bag ${packet.itemCount} items · ${bagFilterLabel(currentBagFilter)} ${filteredItems.size} · rev=${packet.rev}"
    }
    bagAdapter.submitItems(filteredItems)
    bagAdapter.setSelected(selectedBagItem)
    updateBagDetail(selectedBagItem)
  }

  private fun bagFilterLabel(filter: BagFilter): String {
    return when (filter) {
      BagFilter.ALL -> t("all", "全部")
      BagFilter.USE -> t("use", "消耗")
      BagFilter.MAT -> t("mat", "材料")
      BagFilter.GEAR -> t("gear", "装备")
      BagFilter.OTHER -> t("other", "其他")
    }
  }

  private fun sameBagSlot(a: BagItem, b: BagItem): Boolean {
    return a.bag == b.bag && a.slot == b.slot
  }

  private fun matchesBagFilter(item: BagItem, filter: BagFilter): Boolean {
    if (filter == BagFilter.ALL) return true
    return classifyBagItem(item) == filter
  }

  private fun classifyBagItem(item: BagItem): BagFilter {
    val cls = item.itemClass?.trim()?.lowercase(Locale.US).orEmpty()
    val sub = item.itemSubClass?.trim()?.lowercase(Locale.US).orEmpty()
    val equip = item.equipLoc?.trim().orEmpty()

    if (
      containsAny(
        cls,
        "consumable", "消耗品", "药水", "药剂", "食物", "饮料", "绷带", "卷轴"
      ) ||
      containsAny(
        sub,
        "potion", "elixir", "flask", "food", "drink", "bandage", "scroll",
        "药水", "药剂", "食物", "饮料", "绷带", "卷轴"
      )
    ) {
      return BagFilter.USE
    }

    if (
      containsAny(
        cls,
        "trade goods", "tradegood", "recipe", "reagent", "projectile", "quiver", "key",
        "商品", "材料", "弹药", "箭袋", "钥匙", "配方"
      ) ||
      containsAny(
        sub,
        "herb", "metal", "ore", "cloth", "leather", "elemental", "parts", "enchanting", "engineering",
        "草药", "矿石", "布料", "皮革", "元素", "零件", "附魔", "工程"
      )
    ) {
      return BagFilter.MAT
    }

    if (
      containsAny(cls, "weapon", "armor", "武器", "护甲", "装备") ||
      isGearEquipLoc(equip)
    ) {
      return BagFilter.GEAR
    }

    val key = item.iconTex
      ?.substringAfterLast('\\')
      ?.substringAfterLast('/')
      ?.lowercase(Locale.US)
      .orEmpty()

    if (containsAny(key, "potion", "food", "bandage", "scroll", "water", "drink")) return BagFilter.USE
    if (containsAny(key, "herb", "ore", "bar_", "cloth", "leather", "fang", "spider", "fish", "feather", "web")) return BagFilter.MAT
    if (containsAny(key, "helmet", "chest", "boots", "pants", "belt", "shield", "sword", "axe", "mace", "staff", "gauntlets", "bracer", "cloak", "ring", "amulet")) return BagFilter.GEAR
    return BagFilter.OTHER
  }

  private fun isGearEquipLoc(equipLoc: String): Boolean {
    if (equipLoc.isBlank()) return false
    val loc = equipLoc
      .trim()
      .uppercase(Locale.US)
      .removePrefix("INVTYPE_")

    return when (loc) {
      "HEAD", "NECK", "SHOULDER", "BODY", "CHEST", "ROBE", "WAIST", "LEGS", "FEET", "WRIST",
      "HANDS", "FINGER", "TRINKET", "CLOAK", "WEAPON", "SHIELD", "2HWEAPON", "WEAPONMAINHAND",
      "WEAPONOFFHAND", "HOLDABLE", "RANGED", "THROWN", "RANGEDRIGHT", "RELIC", "TABARD" -> true
      else -> false
    }
  }

  private fun containsAny(text: String, vararg needles: String): Boolean {
    for (n in needles) {
      if (text.contains(n)) return true
    }
    return false
  }

  private fun updateBagDetail(item: BagItem?) {
    if (item == null) {
      bagDetailIcon.setImageResource(R.drawable.bag_slot_placeholder)
      bagDetailName.text = t("No item selected", "未选中物品")
      bagDetailType.text = t("Type: -", "类型: -")
      bagDetailClass.text = t("Class: -", "分类: -")
      bagDetailCount.text = t("Count: -", "数量: -")
      bagDetailPos.text = t("Bag/Slot: -", "背包/格子: -")
      bagDetailId.text = t("Item ID: -", "物品ID: -")
      bagDetailPriceUnit.text = t("Unit: -", "单价: -")
      bagDetailPriceStack.text = t("Stack: -", "总价: -")
      return
    }

    val itemName = item.name?.takeIf { it.isNotBlank() } ?: parseItemNameFromLink(item.link) ?: "Item #${item.itemId}"
    val typeLabel = bagFilterLabel(classifyBagItem(item))
    val classLabel = buildClassLabel(item)
    val unitCopper = item.sellPrice.toLong().coerceAtLeast(0L)
    val stackCopper = unitCopper * item.count.toLong().coerceAtLeast(0L)
    bagDetailName.text = itemName
    bagDetailType.text = if (uiLang == UiLang.ZH) "类型: $typeLabel" else "Type: $typeLabel"
    bagDetailClass.text = if (uiLang == UiLang.ZH) "分类: $classLabel" else "Class: $classLabel"
    bagDetailCount.text = if (uiLang == UiLang.ZH) "数量: ${item.count}" else "Count: ${item.count}"
    bagDetailPos.text = if (uiLang == UiLang.ZH) "背包/格子: ${item.bag}/${item.slot}" else "Bag/Slot: ${item.bag}/${item.slot}"
    bagDetailId.text = if (uiLang == UiLang.ZH) "物品ID: ${item.itemId}" else "Item ID: ${item.itemId}"
    bagDetailPriceUnit.text = if (uiLang == UiLang.ZH) "单价: ${formatCoin(unitCopper)}" else "Unit: ${formatCoin(unitCopper)}"
    bagDetailPriceStack.text = if (uiLang == UiLang.ZH) "总价: ${formatCoin(stackCopper)}" else "Stack: ${formatCoin(stackCopper)}"
    val iconBmp = loadBagIconBitmap(item.iconTex)
    if (iconBmp != null && !iconBmp.isRecycled) {
      bagDetailIcon.setImageBitmap(iconBmp)
    } else {
      bagDetailIcon.setImageResource(R.drawable.bag_slot_placeholder)
    }
  }

  private fun buildClassLabel(item: BagItem): String {
    val cls = item.itemClass?.trim().orEmpty()
    val sub = item.itemSubClass?.trim().orEmpty()
    val equip = formatEquipLoc(item.equipLoc)

    val base = when {
      cls.isNotEmpty() && sub.isNotEmpty() -> "$cls / $sub"
      cls.isNotEmpty() -> cls
      sub.isNotEmpty() -> sub
      else -> t("-", "-")
    }
    return if (equip.isNotEmpty()) "$base · $equip" else base
  }

  private fun formatEquipLoc(equipLoc: String?): String {
    if (equipLoc.isNullOrBlank()) return ""
    val raw = equipLoc.trim()
    val normalized = raw.removePrefix("INVTYPE_").replace('_', ' ')
    if (normalized.isBlank()) return ""
    val words = normalized.lowercase(Locale.US).split(' ').filter { it.isNotBlank() }
    return words.joinToString(" ") { part ->
      part.replaceFirstChar { c ->
        if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString()
      }
    }
  }

  private fun formatCoin(copper: Long): String {
    val safe = copper.coerceAtLeast(0L)
    val gold = safe / 10_000L
    val silver = (safe % 10_000L) / 100L
    val copperLeft = safe % 100L
    val parts = ArrayList<String>(3)

    if (uiLang == UiLang.ZH) {
      if (gold > 0L) parts.add("${gold}金")
      if (silver > 0L) parts.add("${silver}银")
      if (copperLeft > 0L || parts.isEmpty()) parts.add("${copperLeft}铜")
    } else {
      if (gold > 0L) parts.add("${gold}g")
      if (silver > 0L) parts.add("${silver}s")
      if (copperLeft > 0L || parts.isEmpty()) parts.add("${copperLeft}c")
    }
    return parts.joinToString(" ")
  }

  private fun parseItemNameFromLink(link: String?): String? {
    if (link.isNullOrBlank()) return null
    val m = Regex("\\|h\\[(.*?)]\\|h").find(link) ?: return null
    val n = m.groupValues.getOrNull(1)?.trim().orEmpty()
    return if (n.isEmpty()) null else n
  }

  private fun bindBagSlotIcon(imageView: ImageView, item: BagItem) {
    val bmp = loadBagIconBitmap(item.iconTex)
    if (bmp != null && !bmp.isRecycled) {
      imageView.setImageBitmap(bmp)
    } else {
      imageView.setImageResource(R.drawable.bag_slot_placeholder)
    }
  }

  private fun normalizeIconStem(iconTex: String?): String? {
    if (iconTex.isNullOrBlank()) return null
    val raw = iconTex
      .substringAfterLast('\\')
      .substringAfterLast('/')
      .trim()
    if (raw.isBlank()) return null
    val stem = raw.substringBeforeLast('.', raw)
    if (stem.isBlank()) return null
    return stem
  }

  private fun loadBagIconBitmap(iconTex: String?): Bitmap? {
    val stem = normalizeIconStem(iconTex) ?: return null
    val key = "icons/$stem.png"

    val cached = bagIconBitmapCache[key]
    if (cached != null && !cached.isRecycled) {
      return cached
    }

    val cacheFile = File(bagIconCacheDir, "$stem.png")
    val fromDisk = if (cacheFile.exists()) {
      runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
    } else null
    if (fromDisk != null) {
      bagIconBitmapCache[key] = fromDisk
      trimBagIconCache()
      return fromDisk
    }

    scheduleBagIconFetch(stem)
    return null
  }

  private fun scheduleBagIconFetch(stem: String) {
    if (!pendingBagIconFetch.add(stem)) return
    if (!connected || currentHost.isBlank() || !remoteIconsAvailable) {
      pendingBagIconFetch.remove(stem)
      return
    }

    lifecycleScope.launch(Dispatchers.IO) {
      val bytes = AssetBridgeClient.requestFile(
        host = currentHost,
        root = "icons",
        path = "$stem.png",
        port = currentAssetPort
      )
      var ok = false
      if (bytes != null && bytes.isNotEmpty()) {
        val cacheFile = File(bagIconCacheDir, "$stem.png")
        ok = runCatching {
          cacheFile.parentFile?.mkdirs()
          cacheFile.writeBytes(bytes)
          true
        }.getOrDefault(false)
      }

      withContext(Dispatchers.Main) {
        pendingBagIconFetch.remove(stem)
        if (ok) {
          val p = lastBag
          if (p != null) {
            updateBagPanel(p)
          }
          if (selectedBagItem != null) {
            updateBagDetail(selectedBagItem)
          }
        }
      }
    }
  }

  private fun normalizeUiTexturePath(texturePath: String?): String? {
    if (texturePath.isNullOrBlank()) return null
    var p = texturePath.trim().replace('\\', '/')
    if (p.startsWith("interface/", ignoreCase = true)) {
      p = p.substring("interface/".length)
    }
    if (p.startsWith("/")) p = p.removePrefix("/")
    if (!p.contains('/')) {
      if (p.startsWith("UI-PaperDoll-Slot-", ignoreCase = true)) {
        p = "PaperDoll/$p"
      }
    }
    if (p.isBlank()) return null
    p = when {
      p.endsWith(".png", ignoreCase = true) -> p
      p.endsWith(".blp", ignoreCase = true) -> p.dropLast(4) + ".png"
      else -> "$p.png"
    }
    return p
  }

  private fun probeEquipEmptyTextures(items: List<EquipItem>) {
    for (item in items) {
      if (item.equipped) continue
      val raw = item.emptyTex?.trim().orEmpty()
      val normalized = normalizeUiTexturePath(raw)
      val key = "${item.slot}|$raw|${normalized.orEmpty()}"
      if (!equipEmptyProbeLogged.add(key)) continue
      val msg = "equip_empty_probe slot=${item.slot} empty_tex=${if (raw.isBlank()) "<empty>" else raw} normalized=${normalized ?: "<nil>"}"
      Log.i(logTag, msg)
      log(msg)
    }
  }

  private fun uiTextureCacheFileName(normalizedPath: String): String {
    return normalizedPath.lowercase(Locale.US).replace('/', '_').replace('\\', '_')
  }

  private fun loadUiTextureBitmap(texturePath: String?): Bitmap? {
    val normalized = normalizeUiTexturePath(texturePath) ?: return null
    val key = "ui/$normalized"
    val cached = uiTextureBitmapCache[key]
    if (cached != null && !cached.isRecycled) {
      return cached
    }

    val cacheFile = File(uiTextureCacheDir, uiTextureCacheFileName(normalized))
    val fromDisk = if (cacheFile.exists()) {
      runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
    } else null
    if (fromDisk != null) {
      uiTextureBitmapCache[key] = fromDisk
      trimUiTextureCache()
      return fromDisk
    }

    scheduleUiTextureFetch(normalized)
    return null
  }

  private fun scheduleUiTextureFetch(normalizedPath: String) {
    if (!pendingUiTextureFetch.add(normalizedPath)) return
    if (!connected || currentHost.isBlank() || !remoteUiTexturesAvailable) {
      pendingUiTextureFetch.remove(normalizedPath)
      return
    }

    lifecycleScope.launch(Dispatchers.IO) {
      val bytes = AssetBridgeClient.requestFile(
        host = currentHost,
        root = "ui_textures",
        path = normalizedPath,
        port = currentAssetPort
      )
      var ok = false
      if (bytes != null && bytes.isNotEmpty()) {
        val cacheFile = File(uiTextureCacheDir, uiTextureCacheFileName(normalizedPath))
        ok = runCatching {
          cacheFile.parentFile?.mkdirs()
          cacheFile.writeBytes(bytes)
          true
        }.getOrDefault(false)
      }

      withContext(Dispatchers.Main) {
        pendingUiTextureFetch.remove(normalizedPath)
        if (!ok) {
          if (uiTextureMissLogged.add(normalizedPath)) {
            val msg = "ui_texture_miss path=$normalizedPath"
            Log.w(logTag, msg)
            log(msg)
          }
          return@withContext
        }
        if (lastEquip != null) {
          updateEquipPanel(lastEquip)
        }
      }
    }
  }

  private fun trimBagIconCache(maxEntries: Int = 256) {
    while (bagIconBitmapCache.size > maxEntries) {
      val first = bagIconBitmapCache.entries.firstOrNull() ?: break
      bagIconBitmapCache.remove(first.key)
    }
  }

  private fun trimUiTextureCache(maxEntries: Int = 192) {
    while (uiTextureBitmapCache.size > maxEntries) {
      val first = uiTextureBitmapCache.entries.firstOrNull() ?: break
      uiTextureBitmapCache.remove(first.key)
    }
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

  private fun refreshRemoteMapIndex() {
    val host = currentHost
    val port = currentAssetPort
    if (host.isBlank() || !connected) return

    lifecycleScope.launch {
      var remoteIndex: RemoteMapIndex? = null
      for (attempt in 1..8) {
        if (!connected || host != currentHost) return@launch
        remoteIndex = withContext(Dispatchers.IO) {
          fetchRemoteMapIndex(host, port)
        }
        if (remoteIndex != null) break
        remoteAssetsAvailable = false
        remoteIconsAvailable = false
        remoteUiTexturesAvailable = false
        Log.w(logTag, "asset tcp unavailable host=$host port=$port attempt=$attempt")
        delay(1200)
      }
      if (!connected || host != currentHost) return@launch
      if (remoteIndex == null) {
        log("asset tcp unavailable: $host:$port")
        return@launch
      }
      applyRemoteMapIndex(remoteIndex)
      Log.i(logTag, "asset tcp indexed tiles=${remoteIndex.tileDirs.size}")
    }
  }

  private fun fetchRemoteMapIndex(host: String, port: Int): RemoteMapIndex? {
    val manifest = AssetBridgeClient.requestManifest(host, port) ?: return null
    val tileAvailable = manifest.roots["worldmap_tiles"] == true
    val iconsAvailable = manifest.roots["icons"] == true
    val uiTexturesAvailable = manifest.roots["ui_textures"] == true
    if (!tileAvailable) return null

    val tileDirs = if (tileAvailable) {
      AssetBridgeClient.requestList(host, root = "worldmap_tiles", path = "", port = port)
        ?.map { it.removeSuffix("/") }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.sorted()
        ?: emptyList()
    } else {
      emptyList()
    }

    return RemoteMapIndex(tileDirs = tileDirs, iconsAvailable = iconsAvailable, uiTexturesAvailable = uiTexturesAvailable)
  }

  private fun applyRemoteMapIndex(index: RemoteMapIndex) {
    var addedTiles = 0
    for (dir in index.tileDirs) {
      if (dir.isBlank()) continue
      val key = slug(dir)
      if (key.isBlank()) continue
      if (!tileZoneIndex.containsKey(key)) {
        tileZoneIndex[key] = dir
        addedTiles++
      }
    }
    remoteAssetsAvailable = index.tileDirs.isNotEmpty()
    remoteIconsAvailable = index.iconsAvailable
    remoteUiTexturesAvailable = index.uiTexturesAvailable
    if (remoteAssetsAvailable) {
      log("asset tcp indexed: +$addedTiles tiles")
    }
  }

  private fun getZoneCatalog(dirName: String): ZoneTileCatalog? {
    val cached = zoneCatalogCache[dirName]
    if (cached != null) return cached

    val pngFiles = getZonePngFiles(dirName)
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

  private fun getZonePngFiles(dirName: String): Set<String> {
    val remoteCached = zoneCatalogFilesRemote[dirName]
    if (remoteCached != null) return remoteCached
    if (!connected || !remoteAssetsAvailable || currentHost.isBlank()) return emptySet()

    if (Looper.myLooper() == Looper.getMainLooper()) {
      scheduleZoneListFetch(dirName)
      return emptySet()
    }

    val entries = AssetBridgeClient.requestList(
      host = currentHost,
      root = "worldmap_tiles",
      path = dirName,
      port = currentAssetPort
    ) ?: return emptySet()
    val remoteFiles = entries
      .filter { !it.endsWith("/") && it.endsWith(".png", ignoreCase = true) }
      .toSet()
    if (remoteFiles.isNotEmpty()) {
      zoneCatalogFilesRemote[dirName] = remoteFiles
    }
    return remoteFiles
  }

  private fun scheduleZoneListFetch(dirName: String) {
    if (!pendingZoneListFetch.add(dirName)) return
    if (!connected || !remoteAssetsAvailable || currentHost.isBlank()) {
      pendingZoneListFetch.remove(dirName)
      return
    }

    lifecycleScope.launch(Dispatchers.IO) {
      val entries = AssetBridgeClient.requestList(
        host = currentHost,
        root = "worldmap_tiles",
        path = dirName,
        port = currentAssetPort
      )
      val remoteFiles = entries
        ?.filter { !it.endsWith("/") && it.endsWith(".png", ignoreCase = true) }
        ?.toSet()
        ?: emptySet()

      withContext(Dispatchers.Main) {
        pendingZoneListFetch.remove(dirName)
        if (!connected || currentHost.isBlank()) return@withContext
        if (remoteFiles.isNotEmpty()) {
          zoneCatalogFilesRemote[dirName] = remoteFiles
          zoneCatalogCache.remove(dirName)
          val p = lastPos
          if (p != null) {
            ensureMapLoaded(p.map, p.zone)
          }
        }
      }
    }
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

  private fun loadBitmapCached(assetPath: String): Bitmap? {
    val cached = bitmapCache[assetPath]
    if (cached != null && !cached.isRecycled) {
      logMapSource(assetPath, "memory_cache")
      return cached
    }

    val cacheFile = File(assetCacheDir, assetPath.replace('/', File.separatorChar))
    val bmpFromDisk = if (cacheFile.exists()) {
      runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
    } else {
      null
    }
    if (bmpFromDisk != null) {
      bitmapCache[assetPath] = bmpFromDisk
      logMapSource(assetPath, "disk_cache")
      return bmpFromDisk
    }

    if (connected && remoteAssetsAvailable && currentHost.isNotBlank()) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        scheduleAssetFetch(assetPath)
        logMapSource(assetPath, "tcp_pending")
      } else {
        val bytes = fetchAssetBytes(assetPath)
        if (bytes != null && bytes.isNotEmpty()) {
          runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(bytes)
          }
          val netBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          if (netBmp != null) {
            bitmapCache[assetPath] = netBmp
            logMapSource(assetPath, "tcp")
            return netBmp
          }
        }
      }
    }

    logMapSource(assetPath, "tcp_miss")
    return null
  }

  private fun scheduleAssetFetch(assetPath: String) {
    if (!pendingAssetFetch.add(assetPath)) return
    if (!connected || !remoteAssetsAvailable || currentHost.isBlank()) {
      pendingAssetFetch.remove(assetPath)
      return
    }

    lifecycleScope.launch(Dispatchers.IO) {
      val bytes = fetchAssetBytes(assetPath)
      var ok = false
      if (bytes != null && bytes.isNotEmpty()) {
        val cacheFile = File(assetCacheDir, assetPath.replace('/', File.separatorChar))
        ok = runCatching {
          cacheFile.parentFile?.mkdirs()
          cacheFile.writeBytes(bytes)
          true
        }.getOrDefault(false)
      }
      withContext(Dispatchers.Main) {
        pendingAssetFetch.remove(assetPath)
        if (ok) {
          logMapSource(assetPath, "tcp")
          if (assetPath.startsWith("minimap_tiles/", ignoreCase = true)) {
            val composed = updateMiniMapCompositeTexture(force = true)
            if (!composed && assetPath.equals(currentMinimapAssetPath, ignoreCase = true)) {
              updateMiniMapTexture()
            }
          } else {
            val p = lastPos
            if (p != null) {
              ensureMapLoaded(p.map, p.zone)
            }
          }
        }
      }
    }
  }

  private fun fetchAssetBytes(assetPath: String): ByteArray? {
    val root = assetPath.substringBefore('/', "")
    val rel = assetPath.substringAfter('/', "")
    if (root.isBlank() || rel.isBlank()) return null
    return AssetBridgeClient.requestFile(
      host = currentHost,
      root = root,
      path = rel,
      port = currentAssetPort
    )
  }

  private fun logMapSource(assetPath: String, source: String) {
    if (!isMapAssetPath(assetPath)) return
    val key = "$source|$assetPath"
    if (!mapSourceLogged.add(key)) return
    Log.i(logTag, "map_source source=$source path=$assetPath")
  }

  private fun isMapAssetPath(assetPath: String): Boolean {
    return assetPath.startsWith("worldmap_", ignoreCase = true) ||
      assetPath.startsWith("minimap_", ignoreCase = true)
  }

  private fun slug(s: String): String {
    return s.replace(Regex("[^A-Za-z0-9]"), "").uppercase(Locale.US)
  }

  private fun logVerbose(message: String) {
    appendDebugLog(message, verbose = true)
  }

  private fun log(message: String) {
    appendDebugLog(message, verbose = false)
  }

  private fun appendDebugLog(message: String, verbose: Boolean) {
    val msg = message.trim()
    if (msg.isEmpty()) return

    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    val last = debugLogBuffer.peekLast()
    if (last != null && last.message == msg && last.verbose == verbose) {
      last.count += 1
      last.ts = ts
    } else {
      debugLogBuffer.addLast(DebugLogEntry(ts = ts, message = msg, verbose = verbose, count = 1))
      while (debugLogBuffer.size > DEBUG_LOG_MAX_LINES) {
        debugLogBuffer.removeFirst()
      }
    }
    refreshDebugLogView()
  }

  private fun refreshDebugLogView() {
    if (!::logText.isInitialized) return
    if (debugLogBuffer.isEmpty()) {
      logText.text = ""
      return
    }
    val sb = StringBuilder(8192)
    for (entry in debugLogBuffer) {
      if (entry.verbose && !debugVerboseEnabled) continue
      sb.append('[').append(entry.ts).append("] ").append(entry.message)
      if (entry.count > 1) {
        sb.append(" (x").append(entry.count).append(')')
      }
      sb.append('\n')
    }
    logText.text = sb.toString()
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
