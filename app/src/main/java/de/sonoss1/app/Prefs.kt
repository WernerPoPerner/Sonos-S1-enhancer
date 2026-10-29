package de.sonoss1.app

import android.content.Context
import de.sonoss1.core.PlayableItem
import de.sonoss1.core.StreamKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("s1player", Context.MODE_PRIVATE)

    var manualIp: String
        get() = sp.getString("manual_ip", "").orEmpty()
        set(value) = sp.edit().putString("manual_ip", value.trim()).apply()

    /** Sends https links as http – helps older S1 speakers that fail on modern TLS certificates. */
    var forceHttp: Boolean
        get() = sp.getBoolean("force_http", false)
        set(value) = sp.edit().putBoolean("force_http", value).apply()

    /** Start playback right away when exactly one item was found and a room is known. */
    var autoPlayOnShare: Boolean
        get() = sp.getBoolean("auto_play", false)
        set(value) = sp.edit().putBoolean("auto_play", value).apply()

    var lastGroupId: String?
        get() = sp.getString("last_group", null)
        set(value) = sp.edit().putString("last_group", value).apply()

    var knownIps: List<String>
        get() = sp.getString("known_ips", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(value) = sp.edit().putString("known_ips", value.joinToString(",")).apply()

    val history: List<PlayableItem>
        get() = runCatching {
            Json.parseToJsonElement(sp.getString("history", "[]").orEmpty()).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                PlayableItem(
                    title = o.str("title") ?: return@mapNotNull null,
                    url = o.str("url") ?: return@mapNotNull null,
                    kind = runCatching { StreamKind.valueOf(o.str("kind").orEmpty()) }.getOrDefault(StreamKind.EPISODE),
                    subtitle = o.str("subtitle"),
                    artUrl = o.str("art"),
                )
            }
        }.getOrDefault(emptyList())

    fun addToHistory(item: PlayableItem) {
        val list = (listOf(item) + history.filter { it.url != item.url }).take(MAX_HISTORY)
        val json = JsonArray(list.map {
            buildJsonObject {
                put("title", it.title)
                put("url", it.url)
                put("kind", it.kind.name)
                put("subtitle", it.subtitle)
                put("art", it.artUrl)
            }
        })
        sp.edit().putString("history", json.toString()).apply()
    }

    fun removeFromHistory(item: PlayableItem) {
        val json = JsonArray(history.filter { it.url != item.url }.map {
            buildJsonObject {
                put("title", it.title)
                put("url", it.url)
                put("kind", it.kind.name)
                put("subtitle", it.subtitle)
                put("art", it.artUrl)
            }
        })
        sp.edit().putString("history", json.toString()).apply()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val MAX_HISTORY = 15
    }
}
