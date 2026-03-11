package com.turtlewow.pipboy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class UdpBridgeClient(
  private val host: String,
  private val port: Int,
  private val onPos: (PosPacket) -> Unit,
  private val onOverlay: (OverlayPacket) -> Unit,
  private val onQuestMarkers: (QuestMarkersPacket) -> Unit,
  private val onQuestLog: (QuestLogPacket) -> Unit,
  private val onBag: (BagPacket) -> Unit,
  private val onEquip: (EquipPacket) -> Unit,
  private val onCharStats: (CharacterStatsPacket) -> Unit,
  private val onResourceScan: (ResourceScanPacket) -> Unit,
  private val onMinimapKey: (MinimapKeyPacket) -> Unit,
  private val onMinimapState: (MinimapStatePacket) -> Unit,
  private val onAck: (String) -> Unit,
  private val onLog: (String) -> Unit
) {
  private var job: Job? = null
  private var ioScope: CoroutineScope? = null
  @Volatile private var socketRef: DatagramSocket? = null
  @Volatile private var addressRef: InetAddress? = null
  private val imeSeq = AtomicLong(1L)
  private val itemUseSeq = AtomicLong(1L)
  private val hotkeySeq = AtomicLong(1L)

  fun start(scope: CoroutineScope) {
    if (job != null) return
    ioScope = scope
    job = scope.launch(Dispatchers.IO) {
      val socket = DatagramSocket()
      socket.soTimeout = 250
      val address = InetAddress.getByName(host)
      socketRef = socket
      addressRef = address
      val recvBuffer = ByteArray(65535)
      var lastHelloMs = 0L

      try {
        onLog("UDP started -> $host:$port")
        while (isActive) {
          val now = System.currentTimeMillis()
          if (now - lastHelloMs >= 2000L) {
            sendJson(socket, address, port, "{\"type\":\"hello\",\"proto\":1,\"client\":\"pipboy-android\",\"want\":\"pos,overlay,quest,questlog,bag,equip,charstats,gobj,minimap_key,minimap_state\"}")
            lastHelloMs = now
          }

          try {
            val pkt = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(pkt)
            val text = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
            handlePacket(text)
          } catch (_: SocketTimeoutException) {
            // keep loop alive for hello keepalive
          } catch (t: Throwable) {
            onLog("recv error: ${t.message}")
          }
        }
      } finally {
        socketRef = null
        addressRef = null
        socket.close()
        onLog("UDP stopped")
      }
    }
  }

  suspend fun stop() {
    val j = job ?: return
    job = null
    ioScope = null
    socketRef = null
    addressRef = null
    j.cancelAndJoin()
  }

  fun sendImeCommit(text: String, submit: Boolean) {
    val trimmed = text
    if (trimmed.isEmpty()) return

    val scope = ioScope ?: run {
      onLog("ime send skipped: client offline")
      return
    }
    val socket = socketRef ?: run {
      onLog("ime send skipped: socket unavailable")
      return
    }
    val address = addressRef ?: run {
      onLog("ime send skipped: address unavailable")
      return
    }

    val seq = imeSeq.getAndIncrement()
    val b64 = Base64.getEncoder().encodeToString(trimmed.toByteArray(StandardCharsets.UTF_8))
    val payload = JSONObject()
      .put("type", "ime_commit")
      .put("proto", 1)
      .put("client", "pipboy-android")
      .put("seq", seq)
      .put("submit", if (submit) 1 else 0)
      .put("text_b64", b64)
      .toString()

    scope.launch(Dispatchers.IO) {
      try {
        sendJson(socket, address, port, payload)
      } catch (t: Throwable) {
        onLog("ime send error: ${t.message}")
      }
    }
  }

  fun sendItemUse(bag: Int, slot: Int, itemId: Int = 0) {
    if (bag < 0 || slot <= 0) {
      onLog("item use skipped: invalid bag/slot $bag/$slot")
      return
    }

    val scope = ioScope ?: run {
      onLog("item use skipped: client offline")
      return
    }
    val socket = socketRef ?: run {
      onLog("item use skipped: socket unavailable")
      return
    }
    val address = addressRef ?: run {
      onLog("item use skipped: address unavailable")
      return
    }

    val seq = itemUseSeq.getAndIncrement()
    val payload = JSONObject()
      .put("type", "item_use")
      .put("proto", 1)
      .put("client", "pipboy-android")
      .put("seq", seq)
      .put("bag", bag)
      .put("slot", slot)
      .put("item_id", itemId)
      .toString()

    scope.launch(Dispatchers.IO) {
      try {
        sendJson(socket, address, port, payload)
      } catch (t: Throwable) {
        onLog("item use send error: ${t.message}")
      }
    }
  }

  fun sendAutoRunToggle() {
    val scope = ioScope ?: run {
      onLog("autorun toggle skipped: client offline")
      return
    }
    val socket = socketRef ?: run {
      onLog("autorun toggle skipped: socket unavailable")
      return
    }
    val address = addressRef ?: run {
      onLog("autorun toggle skipped: address unavailable")
      return
    }

    val seq = hotkeySeq.getAndIncrement()
    val payload = JSONObject()
      .put("type", "hotkey")
      .put("proto", 1)
      .put("client", "pipboy-android")
      .put("seq", seq)
      .put("vk", 0x90) // VK_NUMLOCK
      .toString()

    scope.launch(Dispatchers.IO) {
      try {
        sendJson(socket, address, port, payload)
      } catch (t: Throwable) {
        onLog("autorun toggle send error: ${t.message}")
      }
    }
  }

  private fun sendJson(socket: DatagramSocket, addr: InetAddress, port: Int, text: String) {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val packet = DatagramPacket(bytes, bytes.size, addr, port)
    socket.send(packet)
  }

  private fun handlePacket(text: String) {
    val obj = try {
      JSONObject(text)
    } catch (_: Throwable) {
      return
    }

    when (obj.optString("type")) {
      "hello_ack" -> {
        val tick = obj.optInt("tick_hz", 0)
        onAck("hello_ack tick_hz=$tick")
      }

      "discover_ack" -> {
        onAck("discover_ack port=${obj.optInt("port", port)}")
      }

      "pos" -> {
        onPos(
          PosPacket(
            ts = obj.optLong("ts", 0L),
            player = obj.optString("player", ""),
            map = obj.optString("map", ""),
            zone = obj.optString("zone", ""),
            x = obj.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f),
            y = obj.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f),
            hp = obj.optInt("hp", 0),
            hpMax = obj.optInt("hp_max", 0),
            level = obj.optInt("level", 0),
            facingRad = obj.optNullableFloat("facing_rad"),
            facingDeg = obj.optNullableFloat("facing_deg"),
            facingSrc = obj.optString("facing_src", "").ifBlank { null },
            imeFocus = obj.optInt("ime_focus", 0) != 0 || obj.optBoolean("ime_focus", false),
            imeBox = obj.optString("ime_box", "").ifBlank { null }
          )
        )
      }

      "map_overlay_sync" -> {
        val arr = obj.optJSONArray("overlays") ?: JSONArray()
        val overlays = ArrayList<OverlayRect>(arr.length())
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          overlays.add(
            OverlayRect(
              tex = o.optString("tex", ""),
              x = o.optInt("x", 0),
              y = o.optInt("y", 0),
              w = o.optInt("w", 0),
              h = o.optInt("h", 0)
            )
          )
        }
        onOverlay(
          OverlayPacket(
            ts = obj.optLong("ts", 0L),
            map = obj.optString("map", ""),
            zone = obj.optString("zone", ""),
            count = obj.optInt("count", overlays.size),
            overlays = overlays
          )
        )
      }

      "quest_markers_sync" -> {
        val arr = obj.optJSONArray("markers") ?: JSONArray()
        val markers = ArrayList<QuestMarker>(arr.length())
        for (i in 0 until arr.length()) {
          val m = arr.optJSONObject(i) ?: continue
          markers.add(
            QuestMarker(
              x = m.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f),
              y = m.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f),
              title = m.optString("title", ""),
              questId = m.optInt("questid", 0),
              qtype = m.optString("qtype", "").ifBlank { null },
              icon = m.optString("icon", "").ifBlank { null },
              layer = m.optInt("layer", 0),
              cluster = m.optInt("cluster", 0) != 0 || m.optBoolean("cluster", false)
            )
          )
        }
        onQuestMarkers(
          QuestMarkersPacket(
            ts = obj.optLong("ts", 0L),
            map = obj.optString("map", ""),
            zone = obj.optString("zone", ""),
            mapId = obj.optInt("map_id", 0),
            count = obj.optInt("count", markers.size),
            mapWidth = obj.optNullableFloat("map_w"),
            mapHeight = obj.optNullableFloat("map_h"),
            playerX = obj.optNullableFloat("player_x"),
            playerY = obj.optNullableFloat("player_y"),
            markers = markers
          )
        )
      }

      "quest_log_sync" -> {
        val arr = obj.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<QuestLogEntry>(arr.length())
        for (i in 0 until arr.length()) {
          val e = arr.optJSONObject(i) ?: continue
          val objectiveArr = e.optJSONArray("objectives") ?: JSONArray()
          val objectives = ArrayList<QuestObjective>(objectiveArr.length())
          for (j in 0 until objectiveArr.length()) {
            val o = objectiveArr.optJSONObject(j) ?: continue
            val objectiveText = o.optString("text", "")
            val done = o.optInt("done", 0) != 0 || o.optBoolean("done", false)
            objectives.add(QuestObjective(text = objectiveText, done = done))
          }
          entries.add(
            QuestLogEntry(
              questId = e.optInt("questid", 0),
              qlogId = e.optInt("qlogid", 0),
              title = e.optString("title", ""),
              level = e.optInt("level", 0),
              watched = e.optInt("watched", 0) != 0 || e.optBoolean("watched", false),
              complete = e.optInt("complete", 0) != 0 || e.optBoolean("complete", false),
              objectives = objectives
            )
          )
        }
        onQuestLog(
          QuestLogPacket(
            ts = obj.optLong("ts", 0L),
            count = obj.optInt("count", entries.size),
            entries = entries
          )
        )
      }

      "bag_sync" -> {
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<BagItem>(arr.length())
        for (i in 0 until arr.length()) {
          val it = arr.optJSONObject(i) ?: continue
          items.add(
            BagItem(
              bag = it.optInt("bag", 0),
              slot = it.optInt("slot", 0),
              itemId = it.optInt("item_id", 0),
              count = it.optInt("count", 0),
              iconTex = it.optString("icon_tex", "").ifBlank { null },
              link = it.optString("link", "").ifBlank { null },
              name = it.optString("name", "").ifBlank { null },
              quality = it.optInt("quality", 0),
              itemClass = it.optString("item_class", "").ifBlank { null },
              itemSubClass = it.optString("item_subclass", "").ifBlank { null },
              equipLoc = it.optString("equip_loc", "").ifBlank { null },
              sellPrice = it.optInt("sell_price", 0)
            )
          )
        }
        onBag(
          BagPacket(
            ts = obj.optLong("ts", 0L),
            rev = obj.optInt("rev", 0),
            bankKnown = obj.optInt("bank_known", 0) != 0 || obj.optBoolean("bank_known", false),
            itemCount = obj.optInt("item_count", items.size),
            items = items
          )
        )
      }

      "equip_sync" -> {
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<EquipItem>(arr.length())
        for (i in 0 until arr.length()) {
          val it = arr.optJSONObject(i) ?: continue
          val statsObj = it.optJSONObject("stats")
          val stats = LinkedHashMap<String, Int>()
          if (statsObj != null) {
            val keys = statsObj.keys()
            while (keys.hasNext()) {
              val key = keys.next()
              val value = runCatching { statsObj.optInt(key, 0) }.getOrDefault(0)
              stats[key] = value
            }
          }
          items.add(
            EquipItem(
              slot = it.optString("slot", ""),
              slotId = it.optInt("slot_id", 0),
              equipped = it.optInt("equipped", 0) != 0 || it.optBoolean("equipped", false),
              itemId = it.optInt("item_id", 0),
              emptyTex = it.optString("empty_tex", "").ifBlank { null },
              iconTex = it.optString("icon_tex", "").ifBlank { null },
              link = it.optString("link", "").ifBlank { null },
              name = it.optString("name", "").ifBlank { null },
              quality = it.optInt("quality", 0),
              itemClass = it.optString("item_class", "").ifBlank { null },
              itemSubClass = it.optString("item_subclass", "").ifBlank { null },
              equipLoc = it.optString("equip_loc", "").ifBlank { null },
              durabilityCur = it.optInt("durability_cur", -1),
              durabilityMax = it.optInt("durability_max", -1),
              durabilityPct = it.optInt("durability_pct", -1),
              warn = it.optString("warn", "").ifBlank { null },
              stats = stats
            )
          )
        }
        onEquip(
          EquipPacket(
            ts = obj.optLong("ts", 0L),
            count = obj.optInt("count", items.size),
            equippedCount = obj.optInt("equipped_count", items.count { e -> e.equipped }),
            yellowCount = obj.optInt("yellow_count", 0),
            redCount = obj.optInt("red_count", 0),
            items = items
          )
        )
      }

      "char_stats_sync" -> {
        val arr = obj.optJSONArray("rows") ?: JSONArray()
        val rows = ArrayList<CharacterStatRow>(arr.length())
        for (i in 0 until arr.length()) {
          val it = arr.optJSONObject(i) ?: continue
          rows.add(
            CharacterStatRow(
              key = it.optString("k", ""),
              label = it.optString("label", ""),
              value = it.optString("v", "")
            )
          )
        }
        onCharStats(
          CharacterStatsPacket(
            ts = obj.optLong("ts", 0L),
            count = obj.optInt("count", rows.size),
            rows = rows
          )
        )
      }

      "gobj_scan" -> {
        val arr = obj.optJSONArray("nodes") ?: JSONArray()
        val nodes = ArrayList<ResourceBlip>(arr.length())
        for (i in 0 until arr.length()) {
          val it = arr.optJSONObject(i) ?: continue
          nodes.add(
            ResourceBlip(
              entry = it.optInt("entry", 0),
              goType = it.optInt("go_type", 0),
              x = it.optDouble("x", 0.0).toFloat(),
              y = it.optDouble("y", 0.0).toFloat(),
              z = it.optDouble("z", 0.0).toFloat(),
              facing = it.optNullableFloat("facing"),
              kind = it.optString("resource_kind", "").ifBlank { null },
              name = it.optString("resource_name", "").ifBlank { null },
              skill = it.optInt("resource_skill", 0),
              distMeters = it.optNullableFloat("dist_m")
            )
          )
        }
        onResourceScan(
          ResourceScanPacket(
            ts = obj.optLong("ts", 0L),
            count = obj.optInt("count", nodes.size),
            scanned = obj.optInt("scanned", 0),
            playerWx = obj.optNullableFloat("player_wx"),
            playerWy = obj.optNullableFloat("player_wy"),
            nodes = nodes
          )
        )
      }

      "minimap_key" -> {
        val zone = obj.optString("zone", "").trim()
        val tile = obj.optString("tile", "").trim()
        val asset = obj.optString("asset", "").trim()
        if (zone.isNotEmpty() && tile.isNotEmpty() && asset.isNotEmpty()) {
          onMinimapKey(
            MinimapKeyPacket(
              ts = obj.optLong("ts", 0L),
              zone = zone,
              tile = tile,
              asset = asset,
              src = obj.optString("src", "").ifBlank { null }
            )
          )
        }
      }

      "minimap_state" -> {
        val zone = obj.optString("zone", "").trim()
        val tile = obj.optString("tile", "").trim()
        val asset = obj.optString("asset", "").trim()
        val tilePair = parseMinimapTile(tile)
        if (zone.isNotEmpty() && tilePair != null) {
          onMinimapState(
            MinimapStatePacket(
              ts = obj.optLong("ts", 0L),
              zone = zone,
              tile = tile,
              tileX = tilePair.first,
              tileY = tilePair.second,
              asset = asset,
              playerWx = obj.optNullableFloat("player_wx"),
              playerWy = obj.optNullableFloat("player_wy"),
              src = obj.optString("src", "").ifBlank { null }
            )
          )
        }
      }

      "ime_ack" -> {
        val seq = obj.optLong("seq", 0L)
        val ok = obj.optInt("ok", 0) != 0
        onAck("ime_ack seq=$seq ok=$ok")
      }

      "item_use_ack" -> {
        val seq = obj.optLong("seq", 0L)
        val ok = obj.optInt("ok", 0) != 0
        val bag = obj.optInt("bag", -1)
        val slot = obj.optInt("slot", -1)
        onAck("item_use_ack seq=$seq ok=$ok bag=$bag slot=$slot")
      }

      "hotkey_ack" -> {
        val seq = obj.optLong("seq", 0L)
        val ok = obj.optInt("ok", 0) != 0
        val vk = obj.optInt("vk", 0)
        onAck("hotkey_ack seq=$seq ok=$ok vk=$vk")
      }
    }
  }
}

private fun parseMinimapTile(tile: String?): Pair<Int, Int>? {
  val t = tile?.trim()?.lowercase(Locale.US) ?: return null
  if (!t.startsWith("map")) return null
  val under = t.indexOf('_')
  if (under <= 3 || under >= t.length - 1) return null
  val x = t.substring(3, under).toIntOrNull() ?: return null
  val y = t.substring(under + 1).toIntOrNull() ?: return null
  return x to y
}

private fun JSONObject.optNullableFloat(name: String): Float? {
  if (!has(name) || isNull(name)) return null
  return try {
    optDouble(name).toFloat()
  } catch (_: Throwable) {
    null
  }
}
