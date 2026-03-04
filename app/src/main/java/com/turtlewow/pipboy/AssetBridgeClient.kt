package com.turtlewow.pipboy

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object AssetBridgeClient {
  const val DEFAULT_PORT = 38443
  private const val MAX_LINE = 256 * 1024
  private const val MAX_FILE = 8 * 1024 * 1024
  private const val MAX_RESP_LINES = 16

  data class Manifest(
    val roots: Map<String, Boolean>
  )

  fun requestManifest(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 1200): Manifest? {
    val req = JSONObject()
      .put("op", "manifest")
      .toString()
    val obj = requestJson(host, port, req, timeoutMs) ?: return null
    if (!isOk(obj)) return null
    val roots = LinkedHashMap<String, Boolean>()
    val arr = obj.optJSONArray("roots")
    if (arr != null) {
      for (i in 0 until arr.length()) {
        val node = arr.optJSONObject(i) ?: continue
        val name = node.optString("name").trim()
        if (name.isEmpty()) continue
        roots[name] = node.optInt("available", 0) == 1
      }
    }
    return Manifest(roots)
  }

  fun requestList(
    host: String,
    root: String,
    path: String,
    port: Int = DEFAULT_PORT,
    timeoutMs: Int = 1500
  ): List<String>? {
    val req = JSONObject()
      .put("op", "list")
      .put("root", root)
      .put("path", path)
      .toString()
    val obj = requestJson(host, port, req, timeoutMs) ?: return null
    if (!isOk(obj)) return null
    val arr = obj.optJSONArray("entries") ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
      val v = arr.optString(i).trim()
      if (v.isNotEmpty()) out.add(v)
    }
    return out
  }

  fun requestFile(
    host: String,
    root: String,
    path: String,
    port: Int = DEFAULT_PORT,
    timeoutMs: Int = 2500
  ): ByteArray? {
    val req = JSONObject()
      .put("op", "get")
      .put("root", root)
      .put("path", path)
      .toString()
    return requestFileInternal(host, port, req, timeoutMs)
  }

  private fun requestJson(host: String, port: Int, request: String, timeoutMs: Int): JSONObject? {
    return withSocket(host, port, timeoutMs) { input, output ->
      output.write(request.toByteArray(Charsets.UTF_8))
      output.write('\n'.code)
      output.flush()
      readJsonResponse(input)
    }
  }

  private fun requestFileInternal(
    host: String,
    port: Int,
    request: String,
    timeoutMs: Int
  ): ByteArray? {
    return withSocket(host, port, timeoutMs) { input, output ->
      output.write(request.toByteArray(Charsets.UTF_8))
      output.write('\n'.code)
      output.flush()

      val header = readJsonResponse(input) ?: return@withSocket null
      if (!isOk(header)) return@withSocket null
      if (header.optString("type") != "asset_file") return@withSocket null
      val size = header.optInt("size", -1)
      if (size <= 0 || size > MAX_FILE) return@withSocket null

      val data = ByteArray(size)
      var read = 0
      while (read < size) {
        val n = input.read(data, read, size - read)
        if (n <= 0) return@withSocket null
        read += n
      }
      data
    }
  }

  private fun readJsonResponse(input: BufferedInputStream): JSONObject? {
    repeat(MAX_RESP_LINES) {
      val line = readLineUtf8(input, MAX_LINE) ?: return null
      val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@repeat
      when (obj.optString("type")) {
        "asset_welcome", "hello_ack" -> return@repeat
        else -> return obj
      }
    }
    return null
  }

  private fun isOk(obj: JSONObject): Boolean {
    if (obj.optBoolean("ok", false)) return true
    return obj.optInt("ok", 0) == 1
  }

  private inline fun <T> withSocket(
    host: String,
    port: Int,
    timeoutMs: Int,
    block: (BufferedInputStream, BufferedOutputStream) -> T?
  ): T? {
    val socket = Socket()
    return try {
      socket.soTimeout = timeoutMs
      socket.connect(InetSocketAddress(host, port), timeoutMs)
      val input = BufferedInputStream(socket.getInputStream())
      val output = BufferedOutputStream(socket.getOutputStream())
      block(input, output)
    } catch (_: IOException) {
      null
    } finally {
      runCatching { socket.close() }
    }
  }

  private fun readLineUtf8(input: BufferedInputStream, maxLen: Int): String? {
    val out = ByteArray(maxLen)
    var n = 0
    while (n < maxLen) {
      val b = input.read()
      if (b < 0) return if (n > 0) String(out, 0, n, Charsets.UTF_8) else null
      if (b == '\n'.code) break
      if (b == '\r'.code) continue
      out[n++] = b.toByte()
    }
    if (n == maxLen) return null
    return String(out, 0, n, Charsets.UTF_8)
  }
}
