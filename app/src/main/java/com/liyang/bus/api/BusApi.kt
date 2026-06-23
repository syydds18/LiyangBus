package com.liyang.bus.api

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object BusApi {
    private const val BASE = "https://www.ly-xing.com/"
    private const val PHONE = "j+iLpIfHcsJT7TrMRYtcBw"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Helper: POST form-urlencoded, return JSONObject
    private suspend fun post(path: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val request = Request.Builder()
            .url(BASE + path)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return suspendCoroutine { cont ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
                override fun onResponse(call: Call, resp: Response) {
                    val text = resp.body?.string() ?: "{}"
                    try {
                        // API returns double-encoded JSON: "{\"message\":\"success\",...}"
                        val json = parseJsonResponse(text)
                        cont.resume(json)
                    } catch (e: Exception) {
                        cont.resumeWithException(Exception("API parse error: ${text.take(100)}"))
                    }
                }
            })
        }
    }

    // Search stations/lines by keyword
    // If keyword is numeric → search by lineId; else → search by siteName
    suspend fun search(keyword: String): List<StationResult> {
        val isNumeric = keyword.all { it.isDigit() }
        val params = if (isNumeric) {
            mapOf("siteName" to "", "lineId" to keyword)
        } else {
            mapOf("siteName" to keyword, "lineId" to "", "phone" to PHONE)
        }
        val json = post("taxi/json/getSiteInfoBySiteName", params)
        return parseStationResults(json)
    }

    // Get nearby stations by location
    suspend fun getNearby(lat: Double, lon: Double): List<StationResult> {
        val url = "taxi/json/nanda?url=" + java.net.URLEncoder.encode(
            "getNearestSite.action?lat=$lat&lon=$lon", "UTF-8"
        )
        val json = post(url, emptyMap())
        return parseStationResults(json)
    }

    // Get lines passing through a station
    suspend fun getLinesByStation(siteName: String, siteId: String): List<LineResult> {
        val json = post("taxi/json/getLineBySiteIdName", mapOf(
            "siteName" to siteName, "phone" to PHONE, "siteId" to siteId
        ))
        return parseLineResults(json)
    }

    // Get line info (first/last bus, price, etc.)
    suspend fun getLineInfo(lineId: String): LineInfo {
        val url = "taxi/json/nanda?url=" + java.net.URLEncoder.encode(
            "busLineInfo.action?lineId=$lineId&typeId=&lineName=", "UTF-8"
        )
        val json = post(url, emptyMap())
        val arr = json.getJSONArray("result")
        if (arr.length() == 0) throw Exception("线路不存在")
        val obj = arr.getJSONObject(0)
        return LineInfo(
            lineId = obj.optString("lineId"),
            lineName = obj.optString("lineName"),
            startSiteName = obj.optString("startSiteName"),
            endSiteName = obj.optString("endSiteName"),
            startSiteName1 = obj.optString("startSiteName1"),
            endSiteName1 = obj.optString("endSiteName1"),
            firstTime1 = obj.optString("firstTime1"),
            lastTime1 = obj.optString("lastTime1"),
            firstTime2 = obj.optString("firstTime2"),
            lastTime2 = obj.optString("lastTime2"),
            price = obj.optString("price"),
            vehicleAmount = obj.optInt("vehicleAmount"),
            typeId = obj.optString("typeId", ""),
            interval = obj.optString("interval", ""),
            mileage = obj.optString("mileage", "")
        )
    }

    // Get all stations on a line
    suspend fun getLineStations(lineId: String): List<LineStation> {
        val url = "taxi/json/nanda?url=" + java.net.URLEncoder.encode(
            "getSiteByLine.action?lineId=$lineId", "UTF-8"
        )
        val json = post(url, emptyMap())
        val arr = json.getJSONArray("result")
        val list = mutableListOf<LineStation>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(LineStation(
                siteId = o.optString("siteId"),
                siteName = o.optString("siteName"),
                stream = o.optInt("stream"),
                orderNo = o.optInt("orderNo"),
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon")
            ))
        }
        return list
    }

    // Get real-time bus positions on a line
    suspend fun getBusStatus(lineId: String): List<BusPosition> {
        val url = "taxi/json/nanda?url=" + java.net.URLEncoder.encode(
            "getWebLineBusStatus.action?lineId=$lineId", "UTF-8"
        )
        val json = post(url, emptyMap())
        val arr = json.optJSONArray("result") ?: return emptyList()
        val list = mutableListOf<BusPosition>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(BusPosition(
                carNo = o.optString("carNo"),
                siteId = o.optString("siteId"),
                siteName = o.optString("siteName"),
                stream = o.optString("stream"),
                status = o.optInt("status"),  // 0=舒适 1=正常 2=拥挤
                onlines = o.optInt("onlines")
            ))
        }
        return list
    }

    // Get nearest bus distance from a station
    suspend fun getNearestBus(lineId: String, siteId: String, stream: String): NearestBus? {
        val url = "taxi/json/nanda?url=" + java.net.URLEncoder.encode(
            "getNearestBus.action?lineId=$lineId&siteId=$siteId&stream=$stream", "UTF-8"
        )
        val json = post(url, emptyMap())
        val retCode = json.opt("retCode")
        // retCode can be int 0 or string like "没有车辆"
        val isSuccess = when (retCode) {
            is Int -> retCode == 0
            is String -> retCode == "0" || retCode == "success"
            else -> false
        }
        if (!isSuccess) return null
        val r = json.optJSONObject("result") ?: return null
        return NearestBus(
            distance = r.optInt("redistance"),
            stationsAway = r.optInt("site")
        )
    }

    // Get bus timetable (departure times) for a line direction
    suspend fun getBusTimeList(lineId: String, stream: String): List<BusTimeEntry> {
        val url = "taxi/json/nanda?url=" + java.net.URLEncoder.encode(
            "getBusTimeList.action?lineId=$lineId&stream=$stream", "UTF-8"
        )
        val json = post(url, emptyMap())
        val arr = json.optJSONArray("result") ?: return emptyList()
        val list = mutableListOf<BusTimeEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(BusTimeEntry(
                siteId = o.optString("siteId"),
                siteName = o.optString("siteName"),
                busTime = o.optString("busTime"),
                carNo = o.optString("carNo", "")
            ))
        }
        return list
    }

    // --- Data classes ---
    data class StationResult(
        val siteId: String, val siteName: String,
        val lineId: String, val lineName: String,
        val lat: Double, val lon: Double,
        val distance: Int? = null,
        val siteType: String = "",
        val boardId: String = "",
        val address: String = ""
    )

    data class LineResult(
        val lineId: String, val lineName: String,
        val siteId: String, val distance: Int? = null
    )

    data class LineInfo(
        val lineId: String, val lineName: String,
        val startSiteName: String, val endSiteName: String,
        val startSiteName1: String, val endSiteName1: String,
        val firstTime1: String, val lastTime1: String,
        val firstTime2: String, val lastTime2: String,
        val price: String, val vehicleAmount: Int,
        val typeId: String = "", val interval: String = "",
        val mileage: String = ""
    )

    data class LineStation(
        val siteId: String, val siteName: String,
        val stream: Int, val orderNo: Int,
        val lat: Double, val lon: Double
    )

    data class BusPosition(
        val carNo: String, val siteId: String, val siteName: String,
        val stream: String, val status: Int, val onlines: Int
    )

    data class NearestBus(val distance: Int, val stationsAway: Int)

    data class BusTimeEntry(
        val siteId: String, val siteName: String, val busTime: String,
        val carNo: String = ""
    )

    // --- JSON parsing helper ---
    private fun parseJsonResponse(text: String): JSONObject {
        // Try direct parse first (normal JSON)
        try { return JSONObject(text) } catch (_: Exception) {}
        // Double-encoded: "{\"message\":\"success\",...}"
        // Strip outer quotes and unescape
        val trimmed = text.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            val inner = trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
            return JSONObject(inner)
        }
        throw Exception("Unknown response format")
    }

    // --- Parsers ---
    private fun parseStationResults(json: JSONObject): List<StationResult> {
        val arr = json.optJSONArray("result") ?: return emptyList()
        val list = mutableListOf<StationResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(StationResult(
                siteId = o.optString("siteId"),
                siteName = o.optString("siteName"),
                lineId = o.optString("lineId"),
                lineName = o.optString("lineName"),
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
                distance = if (o.has("distance") && !o.isNull("distance")) o.optInt("distance") else null,
                siteType = o.optString("siteType", ""),
                boardId = o.optString("boardId", ""),
                address = o.optString("address", "")
            ))
        }
        return list
    }

    private fun parseLineResults(json: JSONObject): List<LineResult> {
        val arr = json.optJSONArray("result") ?: return emptyList()
        val list = mutableListOf<LineResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(LineResult(
                lineId = o.optString("lineId"),
                lineName = o.optString("lineName"),
                siteId = o.optString("siteId"),
                distance = if (o.has("distance") && !o.isNull("distance")) o.optInt("distance") else null
            ))
        }
        return list
    }
}
