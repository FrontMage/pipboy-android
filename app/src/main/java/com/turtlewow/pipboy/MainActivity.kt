package com.turtlewow.pipboy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
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
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.Locale
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
  enum class UiLang { EN, ZH }
  enum class Page { MAP, BAG, IME, DEBUG, CONFIG }
  enum class BagFilter { ALL, USE, MAT, GEAR, OTHER }

  data class ZoneTileCatalog(
    val dirName: String,
    val files: Set<String>,
    val rootsBySlug: Map<String, String>,
    val baseRoot: String?
  )

  data class RemoteMapIndex(
    val tileDirs: List<String>,
    val iconsAvailable: Boolean
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
  private var lastBag: BagPacket? = null
  private var lastPosLogMs: Long = 0L
  private var currentHost: String = ""
  private var currentPort: Int = 38442
  private var currentAssetPort: Int = AssetBridgeClient.DEFAULT_PORT
  private var remoteAssetsAvailable = false
  private var remoteIconsAvailable = false
  private var uiLang: UiLang = UiLang.EN
  private var currentPage: Page = Page.MAP
  private var currentBagFilter: BagFilter = BagFilter.ALL
  private var currentBagItems: List<BagItem> = emptyList()
  private var selectedBagItem: BagItem? = null
  private var lastImeFocus = false
  private lateinit var bagAdapter: BagGridAdapter

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
  private val bagIconBitmapCache = LinkedHashMap<String, Bitmap>(256, 0.75f, true)
  private val assetCacheDir by lazy { File(cacheDir, "map_assets") }
  private val bagIconCacheDir by lazy { File(cacheDir, "bag_icons") }

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
    bagFilterAllBtn.setOnClickListener { setBagFilter(BagFilter.ALL) }
    bagFilterUseBtn.setOnClickListener { setBagFilter(BagFilter.USE) }
    bagFilterMatBtn.setOnClickListener { setBagFilter(BagFilter.MAT) }
    bagFilterGearBtn.setOnClickListener { setBagFilter(BagFilter.GEAR) }
    bagFilterOtherBtn.setOnClickListener { setBagFilter(BagFilter.OTHER) }
    bagAdapter = BagGridAdapter(
      onItemTap = { item -> onBagItemTapped(item) },
      bindIcon = { imageView, item -> bindBagSlotIcon(imageView, item) }
    )
    bagGrid.layoutManager = GridLayoutManager(this, 6)
    bagGrid.adapter = bagAdapter
    updateBagFilterButtons()
    assetCacheDir.mkdirs()
    bagIconCacheDir.mkdirs()
    tileZoneIndex.clear()
    zoneCatalogCache.clear()
    zoneCatalogFilesRemote.clear()
    applyLanguage()
    switchPage(currentPage)
    log("ready")
  }

  override fun onDestroy() {
    super.onDestroy()
    disconnect()
    bitmapCache.clear()
    composedCache.clear()
    bagIconBitmapCache.clear()
  }

  private fun connect() {
    val ip = ipInput.text.toString().trim().ifEmpty { "127.0.0.1" }
    val port = portInput.text.toString().trim().toIntOrNull() ?: 38442
    currentHost = ip
    currentPort = port
    currentAssetPort = AssetBridgeClient.DEFAULT_PORT
    remoteAssetsAvailable = false
    remoteIconsAvailable = false

    prefs.edit()
      .putString("ip", ip)
      .putInt("port", port)
      .apply()

    val c = UdpBridgeClient(
      host = ip,
      port = port,
      onPos = { p -> runOnUiThread { onPosPacket(p) } },
      onOverlay = { o -> runOnUiThread { onOverlayPacket(o) } },
      onBag = { b -> runOnUiThread { onBagPacket(b) } },
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
    refreshRemoteMapIndex()
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
    lastBag = null
    remoteAssetsAvailable = false
    remoteIconsAvailable = false
    zoneCatalogFilesRemote.clear()
    zoneCatalogCache.clear()
    mapSourceLogged.clear()
    pendingAssetFetch.clear()
    pendingZoneListFetch.clear()
    pendingBagIconFetch.clear()
    bagIconBitmapCache.clear()
    currentBagItems = emptyList()
    selectedBagItem = null
    connectBtn.text = t("Connect", "连接")
    statusText.text = t("Map: waiting data", "地图: 等待数据")
    connStateText.text = t("Idle", "空闲")
    val waiting = t("debug: waiting packets", "调试: 等待数据包")
    debugStatusText.text = waiting
    updatePlayerPanel(null)
    updateBagPanel(null)
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

  private fun onBagPacket(b: BagPacket) {
    lastBag = b
    updateBagPanel(b)
    refreshDebugPanel()
    Log.d(logTag, "bag rev=${b.rev} items=${b.itemCount} rx=${b.items.size} ts=${b.ts}")
  }

  private fun refreshDebugPanel() {
    val p = lastPos
    val o = lastOverlay
    val b = lastBag
    val now = System.currentTimeMillis()
    val overlayAge = if (o != null && o.ts > 0L) (now - o.ts).coerceAtLeast(0L) else -1L
    val bagAge = if (b != null && b.ts > 0L) (now - b.ts).coerceAtLeast(0L) else -1L

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
    val text = "facing_src=$facingSrc\n$overlayLine\n$bagLine\n$imeLine"
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
    bagFilterAllBtn.text = t("ALL", "全部")
    bagFilterUseBtn.text = t("USE", "消耗")
    bagFilterMatBtn.text = t("MAT", "材料")
    bagFilterGearBtn.text = t("GEAR", "装备")
    bagFilterOtherBtn.text = t("OTHER", "其他")
    bagHintText.text = t("Tap an item to view details", "点物品查看详情")
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
    updateBagFilterButtons()
    updateBagPanel(lastBag)
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

  private fun trimBagIconCache(maxEntries: Int = 256) {
    while (bagIconBitmapCache.size > maxEntries) {
      val first = bagIconBitmapCache.entries.firstOrNull() ?: break
      bagIconBitmapCache.remove(first.key)
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

    return RemoteMapIndex(tileDirs = tileDirs, iconsAvailable = iconsAvailable)
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
          val p = lastPos
          if (p != null) {
            ensureMapLoaded(p.map, p.zone)
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
    return assetPath.startsWith("worldmap_", ignoreCase = true)
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
