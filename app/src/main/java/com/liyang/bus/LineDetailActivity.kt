package com.liyang.bus

import android.app.AlertDialog
import android.graphics.Typeface
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.liyang.bus.api.BusApi
import com.liyang.bus.util.AppLogger
import com.liyang.bus.util.DialogUtils
import com.liyang.bus.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

class LineDetailActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var btnDirection: TextView
    private lateinit var btnFavorite: TextView
    private lateinit var tvDirection: TextView
    private lateinit var tvFirstTime: TextView
    private lateinit var tvLastTime: TextView
    private lateinit var tvPrice: TextView
    private lateinit var cardNearestBus: LinearLayout
    private lateinit var tvNearestDistance: TextView
    private lateinit var tvNearestStations: TextView
    private lateinit var tvComfort: TextView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutStations: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var btnJumpUp: TextView
    private lateinit var btnJumpDown: TextView
    private lateinit var btnReminder: TextView
    private lateinit var btnShare: TextView
    private lateinit var tvVehicleAmount: TextView
    private lateinit var progressLoading: ProgressBar
    private lateinit var tvLoadingText: TextView
    private lateinit var btnDetailRetry: TextView

    private var lineId: String = ""
    private var siteId: String = ""
    private var siteName: String = ""
    private var stream: String = "0"
    private var lineName: String = ""

    private var lineInfo: BusApi.LineInfo? = null
    private var stations: List<BusApi.LineStation> = emptyList()
    private var busPositions: List<BusApi.BusPosition> = emptyList()
    private var nearestBus: BusApi.NearestBus? = null

    private var currentBusIndex: Int = -1
    private val stationRowMap = mutableMapOf<String, View>()

    // Arrival reminder state
    private var reminderActive: Boolean = false
    private var reminderNotified: Boolean = false
    private var reminderThreshold: Int = 1  // stations away threshold

    // Scroll position preservation
    private var savedScrollY: Int = 0

    // Build stream name for matching bus positions
    // API returns stream like "汽车客运站--吴都文化园", we need to match against "0"/"1"
    private var currentStreamName: String = ""

    companion object {
        private const val PREFS_REMINDER = "reminder_prefs"
        private const val KEY_THRESHOLD = "threshold"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_detail)

        lineId = intent.getStringExtra("lineId") ?: ""
        siteId = intent.getStringExtra("siteId") ?: ""
        siteName = intent.getStringExtra("siteName") ?: ""
        stream = intent.getStringExtra("stream") ?: "0"
        lineName = intent.getStringExtra("lineName") ?: ""

        // Restore reminder threshold
        reminderThreshold = getSharedPreferences(PREFS_REMINDER, MODE_PRIVATE)
            .getInt(KEY_THRESHOLD, 1)

        // Restore scroll position
        if (savedInstanceState != null) {
            savedScrollY = savedInstanceState.getInt("scrollY", 0)
        }

        AppLogger.log("Detail", "onCreate lineId=$lineId siteId=$siteId stream=$stream lineName=$lineName")

        initViews()
        loadData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::scrollView.isInitialized) {
            outState.putInt("scrollY", scrollView.scrollY)
        }
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        btnDirection = findViewById(R.id.btnDirection)
        btnFavorite = findViewById(R.id.btnFavorite)
        tvDirection = findViewById(R.id.tvDirection)
        tvFirstTime = findViewById(R.id.tvFirstTime)
        tvLastTime = findViewById(R.id.tvLastTime)
        tvPrice = findViewById(R.id.tvPrice)
        cardNearestBus = findViewById(R.id.cardNearestBus)
        tvNearestDistance = findViewById(R.id.tvNearestDistance)
        tvNearestStations = findViewById(R.id.tvNearestStations)
        tvComfort = findViewById(R.id.tvComfort)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutStations = findViewById(R.id.layoutStations)
        scrollView = findViewById(R.id.scrollView)
        btnJumpUp = findViewById(R.id.btnJumpUp)
        btnJumpDown = findViewById(R.id.btnJumpDown)
        btnReminder = findViewById(R.id.btnReminder)
        btnShare = findViewById(R.id.btnShare)
        tvVehicleAmount = findViewById(R.id.tvVehicleAmount)
        findViewById<TextView>(R.id.btnSchedule).setOnClickListener { showScheduleDialog() }
        progressLoading = findViewById(R.id.progressLoading)
        tvLoadingText = findViewById(R.id.tvLoadingText)
        btnDetailRetry = findViewById(R.id.btnDetailRetry)

        tvTitle.text = lineName.ifEmpty { "线路详情" }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        btnDirection.setOnClickListener {
            stream = if (stream == "0") "1" else "0"
            AppLogger.log("Detail", "Direction switched to stream=$stream")
            refreshData()
        }

        // Favorite toggle
        btnFavorite.setOnClickListener {
            val nowFav = FavoritesManager.toggleFavorite(lineId, lineName)
            btnFavorite.text = if (nowFav) "⭐" else "☆"
        }
        updateFavoriteButton()

        // Map button
        findViewById<View>(R.id.btnMap).setOnClickListener {
            val intent = Intent(this, MapActivity::class.java).apply {
                putExtra("lineId", lineId)
                putExtra("lineName", lineName)
                putExtra("stream", stream)
            }
            startActivity(intent)
        }

        // Jump buttons
        btnJumpDown.setOnClickListener {
            scrollToNextBus()
        }

        btnJumpUp.setOnClickListener {
            scrollToPrevBus()
        }

        // Arrival reminder toggle
        btnReminder.setOnClickListener {
            reminderActive = !reminderActive
            reminderNotified = false
            updateReminderButton()
            if (reminderActive) {
                Toast.makeText(this, "到站提醒已开启（${reminderThreshold}站内提醒，支持悬浮窗+震动）", Toast.LENGTH_SHORT).show()
                // Request notification permission on Android 13+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        1002
                    )
                }
                // Request overlay permission for floating window
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请允许悬浮窗权限以开启到站提醒", Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, 1003)
                }
            } else {
                Toast.makeText(this, "到站提醒已关闭", Toast.LENGTH_SHORT).show()
                NotificationHelper.cancelNotification(this)
            }
        }

        // Long press to adjust threshold
        btnReminder.setOnLongClickListener {
            showReminderThresholdDialog()
            true
        }

        // Share button
        btnShare.setOnClickListener {
            shareLineInfo()
        }

        // Retry button
        btnDetailRetry.setOnClickListener {
            loadData()
        }
    }

    private fun showReminderThresholdDialog() {
        val options = arrayOf("1站内提醒", "2站内提醒", "3站内提醒")
        val remDialog = AlertDialog.Builder(this)
            .setTitle("到站提醒距离")
            .setSingleChoiceItems(options, reminderThreshold - 1) { dialog, which ->
                reminderThreshold = which + 1
                getSharedPreferences(PREFS_REMINDER, MODE_PRIVATE)
                    .edit().putInt(KEY_THRESHOLD, reminderThreshold).apply()
                Toast.makeText(this, "已设置为${reminderThreshold}站内提醒", Toast.LENGTH_SHORT).show()
                reminderNotified = false
                dialog.dismiss()
            }
            .setNegativeButton("关闭", null)
            .create()
        com.liyang.bus.util.DialogUtils.applyAlertDialogStyle(remDialog)
        com.liyang.bus.util.DialogUtils.stripAlertDialogButtonBorders(remDialog)
        remDialog.show()
    }

    private fun showScheduleDialog() {
        val info = lineInfo ?: return

        val dialog = android.app.Dialog(this)
        DialogUtils.applyRoundedDialogStyle(dialog)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20))
            setBackgroundColor(ContextCompat.getColor(this@LineDetailActivity, R.color.mi_card))
        }

        // Title
        root.addView(TextView(this).apply {
            text = "🕐 时刻表"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(16))
        })

        // Line name
        root.addView(TextView(this).apply {
            text = info.lineName
            setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(12))
        })

        // Direction switcher
        var currentDir = if (stream == "0") 0 else 1 // default to current direction
        val dirLabel = TextView(this).apply {
            text = "▶ ${info.startSiteName} → ${info.endSiteName}"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(4))
        }
        val subLabel = TextView(this).apply {
            text = "首班 ${info.firstTime1.ifEmpty { "--" }}  末班 ${info.lastTime1.ifEmpty { "--" }}"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(8))
        }
        val switchBtn = TextView(this).apply {
            text = "🔄 切换方向"
            setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
        }

        root.addView(dirLabel)
        root.addView(subLabel)
        root.addView(switchBtn)

        // Container for timetable grid
        val gridContainer = ScrollView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(280)
            )
            layoutParams = lp
        }
        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        gridContainer.addView(gridLayout)
        root.addView(gridContainer)

        // Loading indicator
        val loadingText = TextView(this).apply {
            text = "加载中..."
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(20), 0, 0)
        }
        gridLayout.addView(loadingText)

        // Close button
        root.addView(TextView(this).apply {
            text = "关闭"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dpToPx(24), dpToPx(14), dpToPx(24), dpToPx(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(16); gravity = Gravity.CENTER }
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        // Fetch timetable data
        fun formatTime(busTime: String): String {
            // "2026-06-22 05:40:00" -> "05:40"
            return try {
                val parts = busTime.split(" ")
                if (parts.size >= 2) {
                    val time = parts[1]
                    val hm = time.split(":")
                    if (hm.size >= 2) "${hm[0]}:${hm[1]}" else busTime
                } else busTime
            } catch (_: Exception) { busTime }
        }

        fun isPast(busTime: String): Boolean {
            return try {
                val parts = busTime.split(" ")
                if (parts.size >= 2) {
                    val hm = parts[1].split(":")
                    val h = hm[0].toInt()
                    val m = hm[1].toInt()
                    val now = java.util.Calendar.getInstance()
                    val curH = now.get(java.util.Calendar.HOUR_OF_DAY)
                    val curM = now.get(java.util.Calendar.MINUTE)
                    h < curH || (h == curH && m < curM)
                } else false
            } catch (_: Exception) { false }
        }

        fun renderTimes(times: List<BusApi.BusTimeEntry>) {
            gridLayout.removeAllViews()
            if (times.isEmpty()) {
                gridLayout.addView(TextView(this).apply {
                    text = "暂无时刻表数据"
                    setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dpToPx(20), 0, 0)
                })
                return
            }

            // Render as grid: 4 columns
            val colCount = 4
            var row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }
            var count = 0
            for (entry in times) {
                val past = isPast(entry.busTime)
                val tv = TextView(this).apply {
                    text = formatTime(entry.busTime)
                    setTextColor(ContextCompat.getColor(context,
                        if (past) R.color.mi_text_secondary else R.color.mi_orange))
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                    // Long-press to show license plate
                    if (entry.carNo.isNotEmpty()) {
                        setOnLongClickListener {
                            Toast.makeText(this@LineDetailActivity, "🚌 ${entry.carNo}", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                }
                row.addView(tv)
                count++
                if (count % colCount == 0) {
                    gridLayout.addView(row)
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        layoutParams = lp
                    }
                }
            }
            if (count % colCount != 0) {
                // Fill remaining cells
                for (i in 0 until (colCount - count % colCount)) {
                    row.addView(TextView(this).apply {
                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = lp
                    })
                }
                gridLayout.addView(row)
            }

            // Add summary
            gridLayout.addView(TextView(this).apply {
                text = "共 ${times.size} 班"
                setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(8), 0, 0)
            })

            // Auto-scroll to next departure
            val nextIdx = times.indexOfFirst { !isPast(it.busTime) }
            if (nextIdx > 0) {
                val rowIdx = nextIdx / colCount
                gridContainer.post {
                    gridContainer.smoothScrollTo(0, rowIdx * dpToPx(36))
                }
            }
        }

        // Cache for loaded timetable data
        var times0: List<BusApi.BusTimeEntry>? = null
        var times1: List<BusApi.BusTimeEntry>? = null

        fun updateDirectionUI() {
            val isForward = currentDir == 0
            dirLabel.text = if (isForward) "▶ ${info.startSiteName} → ${info.endSiteName}"
                           else "◀ ${info.startSiteName1} → ${info.endSiteName1}"
            subLabel.text = if (isForward) "首班 ${info.firstTime1.ifEmpty { "--" }}  末班 ${info.lastTime1.ifEmpty { "--" }}"
                           else "首班 ${info.firstTime2.ifEmpty { "--" }}  末班 ${info.lastTime2.ifEmpty { "--" }}"
            val cached = if (isForward) times0 else times1
            if (cached != null) {
                renderTimes(cached)
            } else {
                gridLayout.removeAllViews()
                gridLayout.addView(TextView(this@LineDetailActivity).apply {
                    text = "加载中..."
                    setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dpToPx(20), 0, 0)
                })
                // Fetch in background
                lifecycleScope.launch {
                    try {
                        val times = withContext(Dispatchers.IO) {
                            BusApi.getBusTimeList(lineId, if (isForward) "0" else "1")
                        }
                        if (isForward) times0 = times else times1 = times
                        if ((isForward && currentDir == 0) || (!isForward && currentDir == 1)) {
                            renderTimes(times)
                        }
                    } catch (e: Exception) {
                        gridLayout.removeAllViews()
                        gridLayout.addView(TextView(this@LineDetailActivity).apply {
                            text = "加载失败: ${e.message}"
                            setTextColor(ContextCompat.getColor(context, R.color.mi_danger))
                            textSize = 13f
                            gravity = Gravity.CENTER
                        })
                    }
                }
            }
        }

        switchBtn.setOnClickListener {
            currentDir = if (currentDir == 0) 1 else 0
            updateDirectionUI()
        }

        // Initial load
        updateDirectionUI()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun updateFavoriteButton() {
        val isFav = FavoritesManager.isFavorite(lineId)
        btnFavorite.text = if (isFav) "⭐" else "☆"
    }

    private fun shareLineInfo() {
        val info = lineInfo
        if (info == null) {
            Toast.makeText(this, "线路信息加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        val isForward = stream == "0"
        val direction = if (isForward) {
            "▶ ${info.startSiteName} → ${info.endSiteName}"
        } else {
            "◀ ${info.startSiteName1} → ${info.endSiteName1}"
        }
        val firstTime = if (isForward) info.firstTime1.ifEmpty { "--" } else info.firstTime2.ifEmpty { "--" }
        val lastTime = if (isForward) info.lastTime1.ifEmpty { "--" } else info.lastTime2.ifEmpty { "--" }

        val filteredStations = stations.filter { it.stream.toString() == stream }.sortedBy { it.orderNo }
        val stationList = if (filteredStations.isNotEmpty()) {
            filteredStations.mapIndexed { idx, s -> "${idx + 1}. ${s.siteName}" }.joinToString("\n")
        } else {
            stations.sortedBy { it.orderNo }.mapIndexed { idx, s -> "${idx + 1}. ${s.siteName}" }.joinToString("\n")
        }

        val shareText = buildString {
            appendLine("🚌 溧阳公交 · ${info.lineName}")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("📍 方向：$direction")
            appendLine("🕐 首班：$firstTime　末班：$lastTime")
            appendLine("💰 票价：¥${info.price}")
            appendLine("")
            appendLine("📋 站点列表（共${filteredStations.size.coerceAtLeast(stations.size)}站）：")
            appendLine(stationList)
            appendLine("")
            appendLine("—— 来自「溧阳公交」APP")
        }

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_SUBJECT, "溧阳公交 · ${info.lineName}")
            }
            startActivity(Intent.createChooser(intent, "分享线路信息"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateReminderButton() {
        btnReminder.text = if (reminderActive) "🔔" else "🔕"
    }

    private fun scrollToNextBus() {
        val matchingBuses = busPositions.filter {
            it.stream == currentStreamName || it.stream == stream
        }
        if (matchingBuses.isEmpty()) {
            AppLogger.log("Detail", "No buses to jump to (down)")
            return
        }

        currentBusIndex++
        if (currentBusIndex >= matchingBuses.size) {
            currentBusIndex = 0 // Wrap around
        }

        val bus = matchingBuses[currentBusIndex]
        val targetView = stationRowMap[bus.siteId]
        if (targetView != null) {
            AppLogger.log("Detail", "Jump down to bus ${bus.carNo} at siteId=${bus.siteId}, index=$currentBusIndex")
            scrollView.post {
                scrollView.smoothScrollTo(0, targetView.top)
            }
        } else {
            AppLogger.log("Detail", "Jump down: could not find view for siteId=${bus.siteId}")
        }
    }

    private fun scrollToPrevBus() {
        val matchingBuses = busPositions.filter {
            it.stream == currentStreamName || it.stream == stream
        }
        if (matchingBuses.isEmpty()) {
            AppLogger.log("Detail", "No buses to jump to (up)")
            return
        }

        currentBusIndex--
        if (currentBusIndex < 0) {
            currentBusIndex = matchingBuses.size - 1 // Wrap around
        }

        val bus = matchingBuses[currentBusIndex]
        val targetView = stationRowMap[bus.siteId]
        if (targetView != null) {
            AppLogger.log("Detail", "Jump up to bus ${bus.carNo} at siteId=${bus.siteId}, index=$currentBusIndex")
            scrollView.post {
                scrollView.smoothScrollTo(0, targetView.top)
            }
        } else {
            AppLogger.log("Detail", "Jump up: could not find view for siteId=${bus.siteId}")
        }
    }

    private fun loadData() {
        layoutLoading.visibility = View.VISIBLE
        progressLoading.visibility = View.VISIBLE
        tvLoadingText.text = "加载中..."
        tvLoadingText.setTextColor(ContextCompat.getColor(this, R.color.mi_text_secondary))
        btnDetailRetry.visibility = View.GONE
        layoutStations.visibility = View.GONE

        lifecycleScope.launch {
            try {
                AppLogger.log("Detail", "Loading line info for lineId=$lineId")
                val info = try {
                    withContext(Dispatchers.IO) { BusApi.getLineInfo(lineId) }
                } catch (e: Exception) {
                    AppLogger.log("Detail", "getLineInfo FAILED: ${e.message}")
                    throw e
                }
                lineInfo = info
                lineName = info.lineName
                tvTitle.text = lineName
                updateFavoriteButton()
                AppLogger.log("Detail", "Line info loaded: ${info.lineName} price=${info.price} start0=${info.startSiteName} end0=${info.endSiteName}")

                AppLogger.log("Detail", "Loading stations for lineId=$lineId")
                val stList = try {
                    withContext(Dispatchers.IO) { BusApi.getLineStations(lineId) }
                } catch (e: Exception) {
                    AppLogger.log("Detail", "getLineStations FAILED: ${e.message}")
                    throw e
                }
                stations = stList
                AppLogger.log("Detail", "Got ${stList.size} stations")

                refreshData()

                // Restore scroll position
                if (savedScrollY > 0) {
                    scrollView.post { scrollView.scrollTo(0, savedScrollY) }
                    savedScrollY = 0
                }

                // Lifecycle-aware periodic refresh
                launch {
                    repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (isActive) {
                            delay(5000)
                            if (isActive) refreshData()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("Detail", "loadData ERROR: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                progressLoading.visibility = View.GONE
                tvLoadingText.text = "加载失败: ${e.message}"
                tvLoadingText.setTextColor(ContextCompat.getColor(this@LineDetailActivity, R.color.mi_danger))
                btnDetailRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun refreshData() {
        lifecycleScope.launch {
            try {
                AppLogger.log("Detail", "Refreshing bus status for lineId=$lineId stream=$stream")
                val positions = try {
                    withContext(Dispatchers.IO) { BusApi.getBusStatus(lineId) }
                } catch (e: Exception) {
                    AppLogger.log("Detail", "getBusStatus FAILED: ${e.message}")
                    emptyList()
                }
                busPositions = positions
                AppLogger.log("Detail", "Got ${positions.size} bus positions, streams: ${positions.map { "${it.carNo}=${it.stream}" }.joinToString(", ")}")

                val nearest = try {
                    if (siteId.isNotEmpty()) {
                        AppLogger.log("Detail", "Getting nearest bus: lineId=$lineId siteId=$siteId stream=$stream")
                        withContext(Dispatchers.IO) { BusApi.getNearestBus(lineId, siteId, stream) }
                    } else null
                } catch (e: Exception) {
                    AppLogger.log("Detail", "getNearestBus FAILED: ${e.message}")
                    null
                }
                nearestBus = nearest
                if (nearest != null) {
                    AppLogger.log("Detail", "Nearest bus: ${nearest.distance}m, ${nearest.stationsAway}站")
                } else {
                    AppLogger.log("Detail", "No nearest bus found")
                }

                // Reset bus index on refresh
                currentBusIndex = -1

                updateUI()
            } catch (e: Exception) {
                AppLogger.log("Detail", "refreshData ERROR: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun formatNearestDistance(distance: Int): String {
        return if (distance >= 1000) {
            String.format("%.1fkm", distance / 1000.0)
        } else {
            "${distance}m"
        }
    }

    private fun updateUI() {
        layoutLoading.visibility = View.GONE
        layoutStations.visibility = View.VISIBLE

        val info = lineInfo
        if (info != null) {
            if (stream == "0") {
                tvDirection.text = "▶ ${info.startSiteName} → ${info.endSiteName}"
                tvFirstTime.text = info.firstTime1.ifEmpty { "--" }
                tvLastTime.text = info.lastTime1.ifEmpty { "--" }
                currentStreamName = "${info.startSiteName}--${info.endSiteName}"
            } else {
                tvDirection.text = "◀ ${info.startSiteName1} → ${info.endSiteName1}"
                tvFirstTime.text = info.firstTime2.ifEmpty { "--" }
                tvLastTime.text = info.lastTime2.ifEmpty { "--" }
                currentStreamName = "${info.startSiteName1}--${info.endSiteName1}"
            }
            tvPrice.text = "¥${info.price}"
            tvVehicleAmount.text = "配车 ${info.vehicleAmount} 辆"
            tvVehicleAmount.visibility = if (info.vehicleAmount > 0) View.VISIBLE else View.GONE
        }

        AppLogger.log("Detail", "updateUI stream=$stream streamName=$currentStreamName busPositions=${busPositions.size}")

        val nearest = nearestBus
        if (nearest != null) {
            cardNearestBus.visibility = View.VISIBLE
            tvNearestDistance.text = formatNearestDistance(nearest.distance)
            tvNearestStations.text = "距 ${nearest.stationsAway} 站"

            // Arrival reminder check
            if (reminderActive && !reminderNotified && nearest.stationsAway <= reminderThreshold) {
                reminderNotified = true
                NotificationHelper.showArrivalNotification(
                    this, lineName, nearest.stationsAway,
                    formatNearestDistance(nearest.distance)
                )
                AppLogger.log("Detail", "Arrival notification sent: ${nearest.stationsAway}站")
            }

            val matchedBus = busPositions.find {
                it.siteId == siteId && (it.stream == currentStreamName || it.stream == stream)
            }
            if (matchedBus != null) {
                tvComfort.text = when (matchedBus.status) {
                    0 -> "🟢舒适"
                    1 -> "🟡正常"
                    2 -> "🔴拥挤"
                    else -> ""
                }
            } else {
                tvComfort.text = ""
            }
        } else {
            cardNearestBus.visibility = View.GONE
        }

        layoutStations.removeAllViews()
        stationRowMap.clear()

        val filteredStations = stations.filter { it.stream.toString() == stream }
            .sortedBy { it.orderNo }

        AppLogger.log("Detail", "Filtered stations: ${filteredStations.size} for stream=$stream")

        if (filteredStations.isEmpty()) {
            val allStations = stations.sortedBy { it.orderNo }
            for (station in allStations) {
                addStationRow(station)
            }
        } else {
            for (station in filteredStations) {
                addStationRow(station)
            }
        }
    }

    private fun addStationRow(station: BusApi.LineStation) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_station, layoutStations, false)

        val viewCircle = row.findViewById<View>(R.id.viewCircle)
        val tvStationName = row.findViewById<TextView>(R.id.tvStationName)
        val layoutBusInfo = row.findViewById<LinearLayout>(R.id.layoutBusInfo)
        val tvCarNo = row.findViewById<TextView>(R.id.tvCarNo)
        val tvComfortIcon = row.findViewById<TextView>(R.id.tvComfortIcon)

        tvStationName.text = station.siteName

        val isCurrentStation = station.siteId == siteId
        if (isCurrentStation) {
            tvStationName.setTextColor(ContextCompat.getColor(this, R.color.mi_orange))
            tvStationName.setTypeface(null, Typeface.BOLD)
            viewCircle.setBackgroundResource(R.drawable.circle_indicator_active)
        } else {
            tvStationName.setTextColor(ContextCompat.getColor(this, R.color.mi_text_primary))
            tvStationName.setTypeface(null, Typeface.NORMAL)
            viewCircle.setBackgroundResource(R.drawable.circle_indicator)
        }

        val busAtStation = busPositions.find {
            it.siteId == station.siteId && (it.stream == currentStreamName || it.stream == stream)
        }

        if (busAtStation != null) {
            layoutBusInfo.visibility = View.VISIBLE
            tvCarNo.text = "🚌${busAtStation.carNo}"
            tvComfortIcon.text = when (busAtStation.status) {
                0 -> "🟢"
                1 -> "🟡"
                2 -> "🔴"
                else -> ""
            }
            if (!isCurrentStation) {
                viewCircle.setBackgroundResource(R.drawable.circle_indicator_bus)
            }
        } else {
            layoutBusInfo.visibility = View.GONE
        }

        row.setOnClickListener {
            if (siteId != station.siteId) {
                siteId = station.siteId
                siteName = station.siteName
                refreshData()
            }
        }

        // Long press: show lines passing through this station
        row.setOnLongClickListener {
            showStationLinesDialog(station.siteId, station.siteName)
            true
        }

        // Store reference for jump navigation
        stationRowMap[station.siteId] = row

        layoutStations.addView(row)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = 56; marginEnd = 16 }
            setBackgroundColor(ContextCompat.getColor(this@LineDetailActivity, R.color.mi_divider))
        }
        layoutStations.addView(divider)
    }

    private fun showStationLinesDialog(siteId: String, siteName: String) {
        val dialog = android.app.Dialog(this)
        DialogUtils.applyRoundedDialogStyle(dialog)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20))
            setBackgroundColor(ContextCompat.getColor(this@LineDetailActivity, R.color.mi_card))
        }

        root.addView(TextView(this).apply {
            text = "📍 $siteName"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(12))
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(240)
            )
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listLayout)
        root.addView(scroll)

        root.addView(TextView(this).apply {
            text = "关闭"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dpToPx(24), dpToPx(14), dpToPx(24), dpToPx(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8); gravity = Gravity.CENTER }
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        // Loading indicator
        listLayout.addView(TextView(this).apply {
            text = "加载中..."
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(16), 0, 0)
        })

        lifecycleScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) {
                    BusApi.getLinesByStation(siteName, siteId)
                }
                listLayout.removeAllViews()
                if (lines.isEmpty()) {
                    listLayout.addView(TextView(this@LineDetailActivity).apply {
                        text = "暂无线路信息"
                        setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setPadding(0, dpToPx(16), 0, 0)
                    })
                } else {
                    for (line in lines) {
                        listLayout.addView(TextView(this@LineDetailActivity).apply {
                            text = "🚌 ${line.lineName}"
                            setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                            textSize = 15f
                            setPadding(dpToPx(8), dpToPx(12), dpToPx(8), dpToPx(12))
                            setOnClickListener {
                                dialog.dismiss()
                                val intent = Intent(this@LineDetailActivity, LineDetailActivity::class.java)
                                intent.putExtra("lineId", line.lineId)
                                intent.putExtra("lineName", line.lineName)
                                intent.putExtra("siteId", siteId)
                                intent.putExtra("siteName", siteName)
                                startActivity(intent)
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                listLayout.removeAllViews()
                listLayout.addView(TextView(this@LineDetailActivity).apply {
                    text = "加载失败: ${e.message}"
                    setTextColor(ContextCompat.getColor(context, R.color.mi_danger))
                    textSize = 13f
                    gravity = Gravity.CENTER
                })
            }
        }
    }
}
