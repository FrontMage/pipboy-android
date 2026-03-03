package com.turtlewow.pipboy

data class PosPacket(
  val ts: Long,
  val player: String,
  val map: String,
  val zone: String,
  val x: Float,
  val y: Float,
  val hp: Int,
  val hpMax: Int,
  val level: Int,
  val facingRad: Float?,
  val facingDeg: Float?,
  val facingSrc: String?,
  val imeFocus: Boolean,
  val imeBox: String?
)

data class OverlayRect(
  val tex: String,
  val x: Int,
  val y: Int,
  val w: Int,
  val h: Int
)

data class OverlayPacket(
  val ts: Long,
  val map: String,
  val zone: String,
  val count: Int,
  val overlays: List<OverlayRect>
)
