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

data class QuestMarker(
  val x: Float,
  val y: Float,
  val title: String,
  val questId: Int,
  val qtype: String?,
  val icon: String?,
  val layer: Int,
  val cluster: Boolean
)

data class QuestMarkersPacket(
  val ts: Long,
  val map: String,
  val zone: String,
  val mapId: Int,
  val count: Int,
  val mapWidth: Float?,
  val mapHeight: Float?,
  val playerX: Float?,
  val playerY: Float?,
  val markers: List<QuestMarker>
)

data class QuestObjective(
  val text: String,
  val done: Boolean
)

data class QuestLogEntry(
  val questId: Int,
  val qlogId: Int,
  val title: String,
  val level: Int,
  val watched: Boolean,
  val complete: Boolean,
  val objectives: List<QuestObjective>
)

data class QuestLogPacket(
  val ts: Long,
  val count: Int,
  val entries: List<QuestLogEntry>
)

data class BagItem(
  val bag: Int,
  val slot: Int,
  val itemId: Int,
  val count: Int,
  val iconTex: String?,
  val link: String?,
  val name: String?,
  val quality: Int,
  val itemClass: String?,
  val itemSubClass: String?,
  val equipLoc: String?,
  val sellPrice: Int
)

data class BagPacket(
  val ts: Long,
  val rev: Int,
  val bankKnown: Boolean,
  val itemCount: Int,
  val items: List<BagItem>
)

data class EquipItem(
  val slot: String,
  val slotId: Int,
  val equipped: Boolean,
  val itemId: Int,
  val emptyTex: String?,
  val iconTex: String?,
  val link: String?,
  val name: String?,
  val quality: Int,
  val itemClass: String?,
  val itemSubClass: String?,
  val equipLoc: String?,
  val durabilityCur: Int,
  val durabilityMax: Int,
  val durabilityPct: Int,
  val warn: String?,
  val stats: Map<String, Int>
)

data class EquipPacket(
  val ts: Long,
  val count: Int,
  val equippedCount: Int,
  val yellowCount: Int,
  val redCount: Int,
  val items: List<EquipItem>
)

data class CharacterStatRow(
  val key: String,
  val label: String,
  val value: String
)

data class CharacterStatsPacket(
  val ts: Long,
  val count: Int,
  val rows: List<CharacterStatRow>
)

data class ResourceBlip(
  val entry: Int,
  val goType: Int,
  val x: Float,
  val y: Float,
  val z: Float,
  val facing: Float?,
  val kind: String?,
  val name: String?,
  val skill: Int,
  val distMeters: Float?
)

data class ResourceScanPacket(
  val ts: Long,
  val count: Int,
  val scanned: Int,
  val playerWx: Float?,
  val playerWy: Float?,
  val nodes: List<ResourceBlip>
)

data class MinimapKeyPacket(
  val ts: Long,
  val zone: String,
  val tile: String,
  val asset: String,
  val src: String?
)

data class MinimapStatePacket(
  val ts: Long,
  val zone: String,
  val tile: String,
  val tileX: Int,
  val tileY: Int,
  val asset: String,
  val playerWx: Float?,
  val playerWy: Float?,
  val src: String?
)
