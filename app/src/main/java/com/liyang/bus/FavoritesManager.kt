package com.liyang.bus

import android.content.Context
import android.content.SharedPreferences
import com.liyang.bus.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

data class FavLine(val lineId: String, val lineName: String, val defaultStream: Int = -1)

object FavoritesManager {

    private const val PREFS_NAME = "bus_favorites"
    private const val KEY_FAVORITES = "favorites"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        AppLogger.log("Favorites", "FavoritesManager initialized, count=${getFavorites().size}")
    }

    fun isFavorite(lineId: String): Boolean {
        return getFavorites().any { it.lineId == lineId }
    }

    fun toggleFavorite(lineId: String, lineName: String): Boolean {
        val favorites = getFavorites().toMutableList()
        val existing = favorites.find { it.lineId == lineId }
        return if (existing != null) {
            favorites.remove(existing)
            saveFavorites(favorites)
            AppLogger.log("Favorites", "Removed: $lineId ($lineName)")
            false
        } else {
            favorites.add(FavLine(lineId, lineName))
            saveFavorites(favorites)
            AppLogger.log("Favorites", "Added: $lineId ($lineName)")
            true
        }
    }

    fun getFavorites(): List<FavLine> {
        val str = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(str)
            val list = mutableListOf<FavLine>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ds = if (obj.has("defaultStream")) obj.getInt("defaultStream") else -1
                list.add(FavLine(
                    lineId = obj.getString("lineId"),
                    lineName = obj.getString("lineName"),
                    defaultStream = ds
                ))
            }
            list
        } catch (e: Exception) {
            AppLogger.log("Favorites", "Parse error: ${e.message}")
            emptyList()
        }
    }

    fun setDefaultStream(lineId: String, stream: Int) {
        val favorites = getFavorites().toMutableList()
        val idx = favorites.indexOfFirst { it.lineId == lineId }
        if (idx >= 0) {
            favorites[idx] = favorites[idx].copy(defaultStream = stream)
            saveFavorites(favorites)
            AppLogger.log("Favorites", "Set defaultStream=$stream for $lineId")
        }
    }

    fun moveUp(lineId: String) {
        val favorites = getFavorites().toMutableList()
        val idx = favorites.indexOfFirst { it.lineId == lineId }
        if (idx > 0) {
            val temp = favorites[idx]
            favorites[idx] = favorites[idx - 1]
            favorites[idx - 1] = temp
            saveFavorites(favorites)
        }
    }

    fun moveDown(lineId: String) {
        val favorites = getFavorites().toMutableList()
        val idx = favorites.indexOfFirst { it.lineId == lineId }
        if (idx >= 0 && idx < favorites.size - 1) {
            val temp = favorites[idx]
            favorites[idx] = favorites[idx + 1]
            favorites[idx + 1] = temp
            saveFavorites(favorites)
        }
    }

    private fun saveFavorites(favorites: List<FavLine>) {
        val arr = JSONArray()
        for (fav in favorites) {
            val obj = JSONObject()
            obj.put("lineId", fav.lineId)
            obj.put("lineName", fav.lineName)
            if (fav.defaultStream >= 0) obj.put("defaultStream", fav.defaultStream)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }
}
