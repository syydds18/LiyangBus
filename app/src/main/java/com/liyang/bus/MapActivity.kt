package com.liyang.bus

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.liyang.bus.api.BusApi
import com.liyang.bus.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MapActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvTitle: TextView
    private lateinit var btnDirection: TextView

    private var lineId: String = ""
    private var lineName: String = ""
    private var stream: String = "0"

    private var stations: List<BusApi.LineStation> = emptyList()
    private var busPositions: List<BusApi.BusPosition> = emptyList()
    private var lineInfo: BusApi.LineInfo? = null
    private var pageLoaded = false
    private var dataReady = false
    private var mapRendered = false
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        lineId = intent.getStringExtra("lineId") ?: ""
        lineName = intent.getStringExtra("lineName") ?: ""
        stream = intent.getStringExtra("stream") ?: "0"

        AppLogger.log("Map", "onCreate lineId=$lineId lineName=$lineName stream=$stream")

        initViews()
        setupWebView()
        loadData()
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        btnDirection = findViewById(R.id.btnDirection)
        tvTitle.text = "$lineName - 地图"
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        btnDirection.setOnClickListener {
            stream = if (stream == "0") "1" else "0"
            AppLogger.log("Map", "Direction switched to stream=$stream")
            loadData()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    AppLogger.log("MapJS", "${it.messageLevel()}: ${it.message()}")
                }
                return true
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMapReady() {
                AppLogger.log("Map", "JS reports map ready!")
                pageLoaded = true
                runOnUiThread {
                    if (dataReady) pushDataToMap()
                }
            }
        }, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                if (dataReady) pushDataToMap()
            }
        }

        webView.loadDataWithBaseURL(
            "file:///android_asset/leaflet/",
            getMapHtml(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                val stList = try {
                    withContext(Dispatchers.IO) { BusApi.getLineStations(lineId) }
                } catch (e: Exception) { emptyList() }
                stations = stList

                val info = lineInfo ?: try {
                    withContext(Dispatchers.IO) { BusApi.getLineInfo(lineId) }
                } catch (e: Exception) { null }
                if (info != null) lineInfo = info

                val positions = try {
                    withContext(Dispatchers.IO) { BusApi.getBusStatus(lineId) }
                } catch (e: Exception) { emptyList() }
                busPositions = positions

                AppLogger.log("Map", "Got ${stList.size} stations, ${positions.size} buses")
                dataReady = true
                if (pageLoaded) {
                    pushDataToMap()
                } else {
                    webView.postDelayed({ if (pageLoaded && !mapRendered) pushDataToMap() }, 1000)
                    webView.postDelayed({ if (pageLoaded && !mapRendered) pushDataToMap() }, 3000)
                }
                startAutoRefresh()
            } catch (e: Exception) {
                AppLogger.log("Map", "loadData ERROR: ${e.message}")
            }
        }
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(15000)
                if (!isActive) break
                try {
                    val positions = withContext(Dispatchers.IO) { BusApi.getBusStatus(lineId) }
                    busPositions = positions
                    pushDataToMap()
                } catch (e: Exception) {
                    AppLogger.log("Map", "refresh ERROR: ${e.message}")
                }
            }
        }
    }

    private fun pushDataToMap() {
        val filteredStations = stations.filter { it.stream.toString() == stream }
            .sortedBy { it.orderNo }
        val stationList = if (filteredStations.isEmpty()) stations.sortedBy { it.orderNo } else filteredStations

        if (stationList.isEmpty()) return

        val currentStreamName = getCurrentStreamName()
        val filteredBuses = busPositions.filter {
            it.stream == currentStreamName || it.stream == stream
        }

        // API returns GCJ-02 coordinates, same as AMap tiles - no conversion needed
        val stationsArr = JSONArray()
        for (s in stationList) {
            stationsArr.put(JSONObject().apply {
                put("siteId", s.siteId)
                put("siteName", s.siteName)
                put("lat", s.lat)
                put("lon", s.lon)
                put("orderNo", s.orderNo)
            })
        }

        val busesArr = JSONArray()
        for (b in filteredBuses) {
            val station = stationList.find { it.siteId == b.siteId }
            if (station != null) {
                busesArr.put(JSONObject().apply {
                    put("carNo", b.carNo)
                    put("siteId", b.siteId)
                    put("lat", station.lat)
                    put("lon", station.lon)
                    put("status", b.status)
                })
            }
        }

        val dataJson = JSONObject().apply {
            put("stations", stationsArr)
            put("buses", busesArr)
            put("stream", stream)
        }

        val jsonStr = dataJson.toString()
        val base64 = android.util.Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

        runOnUiThread {
            webView.evaluateJavascript("updateMapFromBase64('$base64')", null)
            mapRendered = true
        }
    }

    private fun getCurrentStreamName(): String {
        val info = lineInfo ?: return ""
        return if (stream == "0") "${info.startSiteName}--${info.endSiteName}"
        else "${info.startSiteName1}--${info.endSiteName1}"
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        refreshJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (stations.isNotEmpty()) startAutoRefresh()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        webView.destroy()
        super.onDestroy()
    }

    private fun getMapHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=2.0, user-scalable=yes" />
    <link rel="stylesheet" href="leaflet.css" />
    <script src="leaflet.js"></script>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { width: 100%; height: 100%; background: #f0f0f0; }
        #map { width: 100%; height: 100%; }
        @media (prefers-color-scheme: dark) {
            html, body { background: #1a1a1a; }
            .leaflet-tile-pane { filter: brightness(0.6) invert(1) contrast(3) hue-rotate(200deg) saturate(0.3) brightness(0.7); }
        }
        .bus-icon {
            background: #FF6D00; border-radius: 50%; width: 32px; height: 32px;
            display: flex; align-items: center; justify-content: center;
            font-size: 16px; color: white; border: 2px solid #fff;
            box-shadow: 0 2px 6px rgba(0,0,0,0.35);
        }
        .station-icon {
            background: #2196F3; border-radius: 50%; width: 14px; height: 14px;
            border: 2px solid #fff; box-shadow: 0 1px 3px rgba(0,0,0,0.3);
        }
        .endpoint-icon {
            background: #9C27B0; border-radius: 50%; width: 18px; height: 18px;
            border: 2px solid #fff; box-shadow: 0 1px 3px rgba(0,0,0,0.3);
        }
        .bus-label {
            background: rgba(255,109,0,0.92) !important; color: white !important;
            padding: 3px 8px !important; border-radius: 4px !important;
            font-size: 11px !important; font-weight: bold !important;
            border: 1px solid #fff !important; box-shadow: 0 1px 4px rgba(0,0,0,0.3) !important;
        }
        .station-label {
            background: rgba(33,150,243,0.9) !important; color: white !important;
            padding: 2px 6px !important; border-radius: 3px !important;
            font-size: 10px !important; border: none !important;
        }
        .info-bar {
            position: absolute; bottom: 12px; left: 12px; right: 12px; z-index: 1000;
            background: rgba(255,255,255,0.95); border-radius: 10px;
            padding: 10px 14px; font-size: 12px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.15);
            display: flex; justify-content: space-between; align-items: center;
        }
        @media (prefers-color-scheme: dark) {
            .info-bar { background: rgba(40,40,40,0.95); color: #e0e0e0; }
            .update-time { color: #aaa !important; }
        }
        .legend { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
        .legend-item { display: flex; align-items: center; gap: 4px; font-size: 11px; }
        .legend-dot { width: 10px; height: 10px; border-radius: 50%; border: 1px solid rgba(0,0,0,0.15); }
        .update-time { font-size: 11px; color: #888; white-space: nowrap; }
        #loadingOverlay {
            position: absolute; top: 0; left: 0; right: 0; bottom: 0;
            z-index: 2000; background: rgba(255,255,255,0.85);
            display: flex; align-items: center; justify-content: center;
            font-size: 16px; color: #666;
        }
        #loadingOverlay.hidden { display: none; }
    </style>
</head>
<body>
    <div id="map"></div>
    <div id="loadingOverlay">🗺️ 地图加载中...</div>
    <script>
        var map = null, stationMarkers = [], busMarkers = [], stationLine = null, mapReady = false;
        try {
            map = L.map('map', { zoomControl: true, attributionControl: true })
                .setView([31.389, 119.499], 13);
            L.tileLayer('https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
                maxZoom: 18, attribution: '&copy; 高德地图'
            }).addTo(map);
            mapReady = true;
            console.log('Map initialized OK');
            try { AndroidBridge.onMapReady(); } catch(e) {}
        } catch(e) {
            console.error('Map init failed:', e);
            document.getElementById('loadingOverlay').innerHTML = '❌ 地图加载失败: ' + e.message;
        }

        function updateMapFromBase64(b64) {
            try {
                var binaryStr = atob(b64);
                var bytes = new Uint8Array(binaryStr.length);
                for (var i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i);
                var jsonStr = new TextDecoder('utf-8').decode(bytes);
                renderMapData(JSON.parse(jsonStr));
            } catch(e) {
                console.error('updateMapFromBase64 error:', e);
                document.getElementById('loadingOverlay').innerHTML = '❌ 数据解析失败: ' + e.message;
            }
        }

        function renderMapData(data) {
            if (!mapReady) return;
            stationMarkers.forEach(function(m) { map.removeLayer(m); });
            busMarkers.forEach(function(m) { map.removeLayer(m); });
            if (stationLine) { map.removeLayer(stationLine); }
            stationMarkers = []; busMarkers = []; stationLine = null;
            document.getElementById('loadingOverlay').className = 'hidden';

            var stations = data.stations || [];
            var buses = data.buses || [];
            if (stations.length === 0) {
                document.getElementById('loadingOverlay').className = '';
                document.getElementById('loadingOverlay').innerHTML = '📍 暂无站点数据';
                return;
            }

            var coords = stations.map(function(s) { return [s.lat, s.lon]; });
            stationLine = L.polyline(coords, {
                color: '#2196F3', weight: 4, opacity: 0.6, dashArray: '8, 6'
            }).addTo(map);

            stations.forEach(function(s, idx) {
                var isEndpoint = (idx === 0 || idx === stations.length - 1);
                var className = isEndpoint ? 'endpoint-icon' : 'station-icon';
                var size = isEndpoint ? [18, 18] : [14, 14];
                var marker = L.marker([s.lat, s.lon], {
                    icon: L.divIcon({
                        className: '', html: '<div class="' + className + '"></div>',
                        iconSize: size, iconAnchor: [size[0]/2, size[1]/2]
                    })
                }).addTo(map);
                marker.bindTooltip(s.siteName, {
                    permanent: false, direction: 'top', offset: [0, -8], className: 'station-label'
                });
                stationMarkers.push(marker);
            });

            var statusColors = ['#4CAF50', '#FF9800', '#F44336'];
            var statusLabels = ['舒适', '正常', '拥挤'];
            buses.forEach(function(b) {
                var color = statusColors[b.status] || '#FF6D00';
                var marker = L.marker([b.lat, b.lon], {
                    icon: L.divIcon({
                        className: '',
                        html: '<div class="bus-icon" style="background:' + color + '">🚌</div>',
                        iconSize: [32, 32], iconAnchor: [16, 16]
                    }), zIndexOffset: 1000
                }).addTo(map);
                var statusText = statusLabels[b.status] || '';
                marker.bindTooltip('<b>' + b.carNo + '</b>' + (statusText ? ' · ' + statusText : ''), {
                    permanent: true, direction: 'top', offset: [0, -20], className: 'bus-label'
                });
                busMarkers.push(marker);
            });

            if (coords.length > 0) map.fitBounds(L.latLngBounds(coords), { padding: [40, 40] });

            var now = new Date();
            var timeStr = now.getHours() + ':' +
                (now.getMinutes() < 10 ? '0' : '') + now.getMinutes() + ':' +
                (now.getSeconds() < 10 ? '0' : '') + now.getSeconds();
            var el = document.getElementById('updateTime');
            if (el) el.textContent = '更新: ' + timeStr;
            var countEl = document.getElementById('busCount');
            if (countEl) countEl.textContent = buses.length + '辆在运营';
        }

        setInterval(function() {
            try { if (typeof BusBridge !== 'undefined') BusBridge.requestRefresh(); } catch(e) {}
        }, 15000);

        var infoDiv = document.createElement('div');
        infoDiv.className = 'info-bar';
        infoDiv.innerHTML =
            '<div class="legend">' +
            '<div class="legend-item"><div class="legend-dot" style="background:#4CAF50"></div>舒适</div>' +
            '<div class="legend-item"><div class="legend-dot" style="background:#FF9800"></div>正常</div>' +
            '<div class="legend-item"><div class="legend-dot" style="background:#F44336"></div>拥挤</div>' +
            '<div class="legend-item"><div class="legend-dot" style="background:#2196F3"></div>站点</div>' +
            '</div>' +
            '<div style="text-align:right"><span id="busCount" style="font-weight:bold;color:#FF6D00"></span><br><span id="updateTime" class="update-time">等待数据...</span></div>';
        document.body.appendChild(infoDiv);
    </script>
</body>
</html>
""".trimIndent()
    }
}
