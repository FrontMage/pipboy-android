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
import java.util.concurrent.atomic.AtomicLong

class UdpBridgeClient(
  private val host: String,
  private val port: Int,
  private val onPos: (PosPacket) -> Unit,
  private val onOverlay: (OverlayPacket) -> Unit,
  private val onAck: (String) -> Unit,
  private val onLog: (String) -> Unit
) {
  private var job: Job? = null
  private var ioScope: CoroutineScope? = null
  @Volatile private var socketRef: DatagramSocket? = null
  @Volatile private var addressRef: InetAddress? = null
  private val imeSeq = AtomicLong(1L)

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
            sendJson(socket, address, port, "{\"type\":\"hello\",\"proto\":1,\"client\":\"pipboy-android\",\"want\":\"pos,overlay\"}")
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

      "ime_ack" -> {
        val seq = obj.optLong("seq", 0L)
        val ok = obj.optInt("ok", 0) != 0
        onAck("ime_ack seq=$seq ok=$ok")
      }
    }
  }
}

private fun JSONObject.optNullableFloat(name: String): Float? {
  if (!has(name) || isNull(name)) return null
  return try {
    optDouble(name).toFloat()
  } catch (_: Throwable) {
    null
  }
}
