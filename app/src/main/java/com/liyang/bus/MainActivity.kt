package com.liyang.bus

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.liyang.bus.api.BusApi
import com.liyang.bus.util.AppLogger
import com.liyang.bus.util.DialogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: View
    private lateinit var btnClear: TextView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var tvFavoritesTitle: TextView
    private lateinit var rvFavorites: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvNearbyTitle: TextView
    private lateinit var layoutNearby: LinearLayout
    private lateinit var layoutResults: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var layoutError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetry: TextView
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var nearbyLoaded: Boolean = false
    private var lastSearchQuery: String = ""
    private var savedSearchResults: List<BusApi.StationResult>? = null
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.log("Main", "onCreate")
        setContentView(R.layout.activity_main)
        
        initViews()
        loadFavorites()
        requestLocationAndLoad()

        // Auto-refresh favorites ETA every 10 seconds
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(10_000)
                    if (isActive) refreshFavoritesEta()
                }
            }
        }

        AppLogger.log("Main", "onCreate done")
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun initViews() {
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnClear = findViewById(R.id.btnClear)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvFavoritesTitle = findViewById(R.id.tvFavoritesTitle)
        rvFavorites = findViewById(R.id.rvFavorites)
        rvFavorites.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        rvFavorites.itemAnimator = null
        tvNearbyTitle = findViewById(R.id.tvNearbyTitle)
        layoutNearby = findViewById(R.id.layoutNearby)
        layoutResults = findViewById(R.id.layoutResults)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutError = findViewById(R.id.layoutError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetry = findViewById(R.id.btnRetry)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        // SwipeRefreshLayout
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.mi_orange))
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.mi_card)
        swipeRefresh.setOnRefreshListener {
            loadNearby()
            if (lastSearchQuery.isNotEmpty()) {
                performSearch(lastSearchQuery, hideKeyboard = false)
            }
            swipeRefresh.isRefreshing = false
        }

        // Retry button
        btnRetry.setOnClickListener {
            layoutError.visibility = View.GONE
            if (lastSearchQuery.isNotEmpty()) {
                performSearch(lastSearchQuery)
            } else {
                loadNearby()
            }
        }

        AppLogger.log("Main", "btnSearch type=${btnSearch.javaClass.simpleName}")
        AppLogger.log("Main", "btnSearch isClickable=${btnSearch.isClickable}")

        btnSearch.setOnClickListener {
            AppLogger.log("Main", ">>> btnSearch CLICKED <<<")
            try {
                performSearch()
            } catch (e: Exception) {
                AppLogger.log("Main", "performSearch CRASH: ${e.message}")
                AppLogger.log("Main", e.stackTraceToString())
            }
        }

        // Clear button
        btnClear.setOnClickListener {
            etSearch.text.clear()
            hideKeyboard()
        }

        // Toggle clear button visibility + search debounce via TextWatcher
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                // Debounce search: cancel previous pending search, schedule new one
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (!s.isNullOrEmpty()) {
                    searchRunnable = Runnable { performSearch(hideKeyboard = false) }
                    searchHandler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
                }
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                AppLogger.log("Main", "Keyboard search action")
                performSearch()
                true
            } else false
        }

        // Long press title to show log dialog
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        tvTitle?.setOnLongClickListener {
            showLogDialog()
            true
        }

        // More menu (⋮)
        findViewById<View>(R.id.btnAbout).setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "🔄 换乘查询")
            popup.menu.add(0, 2, 0, "ℹ️ 关于")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showTransferDialog()
                    2 -> startActivity(Intent(this, AboutActivity::class.java))
                }
                true
            }
            popup.show()
        }
    }

    // ETA data: "lineId_0" / "lineId_1" -> display text
    private val etaData = mutableMapOf<String, String>()
    private var favAdapter: FavAdapter? = null

    private fun loadFavorites() {
        val favorites = FavoritesManager.getFavorites()
        if (favorites.isEmpty()) {
            tvFavoritesTitle.visibility = View.GONE
            rvFavorites.visibility = View.GONE
            return
        }
        tvFavoritesTitle.visibility = View.VISIBLE
        rvFavorites.visibility = View.VISIBLE

        if (favAdapter == null) {
            favAdapter = FavAdapter(favorites)
            rvFavorites.adapter = favAdapter
        } else {
            favAdapter!!.updateData(favorites)
        }

        refreshFavoritesEta()
    }

    private fun refreshFavoritesEta() {
        val favorites = FavoritesManager.getFavorites()
        if (favorites.isEmpty()) return
        lifecycleScope.launch {
            for (fav in favorites) {
                try {
                    val info = withContext(Dispatchers.IO) { BusApi.getLineInfo(fav.lineId) }
                    val dirName0 = "${info.startSiteName}--${info.endSiteName}"
                    val dirName1 = "${info.startSiteName1}--${info.endSiteName1}"

                    val positions = withContext(Dispatchers.IO) { BusApi.getBusStatus(fav.lineId) }
                    val buses0 = positions.filter { it.stream == dirName0 }
                    val buses1 = positions.filter { it.stream == dirName1 }

                    val dirLabel0 = info.endSiteName
                    val dirLabel1 = info.endSiteName1

                    etaData["${fav.lineId}_0"] = if (buses0.isNotEmpty()) {
                        val sites = buses0.map { it.siteName }.distinct().joinToString("、")
                        "→${dirLabel0} $sites"
                    } else "→${dirLabel0} 暂无"

                    etaData["${fav.lineId}_1"] = if (buses1.isNotEmpty()) {
                        val sites = buses1.map { it.siteName }.distinct().joinToString("、")
                        "→${dirLabel1} $sites"
                    } else "→${dirLabel1} 暂无"

                    val idx = favorites.indexOfFirst { it.lineId == fav.lineId }
                    if (idx >= 0) favAdapter?.notifyItemChanged(idx)
                } catch (e: Exception) {
                    AppLogger.log("Main", "ETA refresh failed for ${fav.lineId}: ${e.message}")
                }
            }
        }
    }

    private inner class FavAdapter(private var items: List<FavLine>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<FavAdapter.VH>() {

        inner class VH(val root: LinearLayout, val tvLine: TextView, val tvEta0: TextView, val tvEta1: TextView) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(root)

        fun updateData(newItems: List<FavLine>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val density = resources.displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.mi_card))
                    cornerRadius = 12 * density
                    setStroke(dp(1), ContextCompat.getColor(this@MainActivity, R.color.mi_divider))
                }
                background = bg
            }

            val tvLine = TextView(this@MainActivity).apply {
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            card.addView(tvLine)

            val tvEta0 = TextView(this@MainActivity).apply {
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }
            card.addView(tvEta0)

            val tvEta1 = TextView(this@MainActivity).apply {
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            card.addView(tvEta1)

            return VH(card, tvLine, tvEta0, tvEta1)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val fav = items[position]
            holder.tvLine.text = fav.lineName.replace("路", "").replace("线", "")

            val text0 = etaData["${fav.lineId}_0"] ?: ""
            val text1 = etaData["${fav.lineId}_1"] ?: ""
            holder.tvEta0.text = text0
            holder.tvEta1.text = text1
            holder.tvEta0.setTextColor(ContextCompat.getColor(this@MainActivity,
                if (text0.contains("暂无")) R.color.mi_text_hint else R.color.mi_orange))
            holder.tvEta1.setTextColor(ContextCompat.getColor(this@MainActivity,
                if (text1.contains("暂无")) R.color.mi_text_hint else R.color.mi_orange))

            holder.root.setOnClickListener {
                AppLogger.log("Main", "Favorite click: ${fav.lineId} ${fav.lineName} defaultStream=${fav.defaultStream}")
                if (fav.defaultStream >= 0) {
                    navigateToDetail(fav.lineId, "", "", fav.defaultStream.toString(), fav.lineName)
                } else {
                    showDirectionDialog(fav.lineId, fav.lineName, emptyList())
                }
            }

            holder.root.setOnLongClickListener {
                val idx = items.indexOf(fav)
                val menuItems = mutableListOf("取消收藏")
                if (idx > 0) menuItems.add("上移")
                if (idx < items.size - 1) menuItems.add("下移")
                if (fav.defaultStream >= 0) menuItems.add("当前默认方向: 方向${fav.defaultStream + 1}")
                menuItems.add("设为默认方向")
                val favDialog = android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(fav.lineName)
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        when (menuItems[which]) {
                            "取消收藏" -> {
                                FavoritesManager.toggleFavorite(fav.lineId, fav.lineName)
                                loadFavorites()
                                Toast.makeText(this@MainActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                            }
                            "上移" -> { FavoritesManager.moveUp(fav.lineId); loadFavorites() }
                            "下移" -> { FavoritesManager.moveDown(fav.lineId); loadFavorites() }
                            "设为默认方向" -> showDefaultDirectionDialog(fav.lineId, fav.lineName)
                        }
                    }
                    .setNegativeButton("关闭", null)
                    .create()
                DialogUtils.applyAlertDialogStyle(favDialog)
                DialogUtils.stripAlertDialogButtonBorders(favDialog)
                favDialog.show()
                true
            }
        }

        override fun getItemCount() = items.size
    }

    private fun showDefaultDirectionDialog(lineId: String, lineName: String) {
        val dirDialog = android.app.AlertDialog.Builder(this)
            .setTitle("选择默认方向")
            .setItems(arrayOf("方向 1 (正向)", "方向 2 (反向)")) { _, which ->
                FavoritesManager.setDefaultStream(lineId, which)
                loadFavorites()
                Toast.makeText(this, "已设置默认方向: 方向${which + 1}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .create()
        DialogUtils.applyAlertDialogStyle(dirDialog)
        DialogUtils.stripAlertDialogButtonBorders(dirDialog)
        dirDialog.show()
    }

    private fun performSearch(queryOverride: String? = null, hideKeyboard: Boolean = true) {
        val query = queryOverride ?: etSearch.text.toString().trim()
        AppLogger.log("Search", "performSearch query=[$query]")
        if (query.isEmpty()) {
            AppLogger.log("Search", "Empty query, skip")
            return
        }
        lastSearchQuery = query
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "无网络连接", Toast.LENGTH_SHORT).show()
            return
        }

        if (hideKeyboard) hideKeyboard()
        layoutEmpty.visibility = View.GONE
        layoutError.visibility = View.GONE
        layoutResults.removeAllViews()
        layoutLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                AppLogger.log("Search", "Calling API...")
                val results = withContext(Dispatchers.IO) {
                    AppLogger.log("Search", "IO thread: calling BusApi.search($query)")
                    val r = BusApi.search(query)
                    AppLogger.log("Search", "API returned ${r.size} results")
                    r
                }
                layoutLoading.visibility = View.GONE
                if (results.isEmpty()) {
                    AppLogger.log("Search", "No results")
                    showEmptyResult("未找到相关结果")
                } else {
                    AppLogger.log("Search", "Showing ${results.size} results")
                    savedSearchResults = results
                    showSearchResults(results)
                }
            } catch (e: Exception) {
                AppLogger.log("Search", "ERROR: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                layoutLoading.visibility = View.GONE
                showError("查询失败: ${e.message}")
            }
        }
    }

    private fun showSearchResults(results: List<BusApi.StationResult>) {
        layoutResults.removeAllViews()
        // Group by lineId to avoid duplicates, then sort by line name
        val grouped = results.groupBy { it.lineId }
            .toSortedMap(compareBy { key ->
                val name = results.first { it.lineId == key }.lineName
                // Extract numeric part for proper numeric sorting
                val num = name.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 9999
                num
            })
        for ((lineId, stations) in grouped) {
            val first = stations.first()
            val distinctStations = stations.distinctBy { it.siteName }
            val hubNames = distinctStations.filter { it.siteType == "2" }.map { it.siteName }.toSet()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.card_bg)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                layoutParams = lp
                setPadding(20, 16, 20, 16)
                isClickable = true
                isFocusable = true
                gravity = Gravity.CENTER_VERTICAL
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }

            textLayout.addView(TextView(this).apply {
                text = "🚏 ${first.lineName}"
                setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            })

            val distText = if (first.distance != null) formatDistance(first.distance) else ""
            // Build info text with orange hub station names
            val infoBuilder = SpannableStringBuilder("经过: ")
            distinctStations.forEachIndexed { idx, s ->
                if (idx > 0) infoBuilder.append("、")
                if (s.siteName in hubNames) {
                    val span = SpannableString(s.siteName)
                    span.setSpan(ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.mi_orange)), 0, s.siteName.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    infoBuilder.append(span)
                } else {
                    infoBuilder.append(s.siteName)
                }
            }
            if (distText.isNotEmpty()) infoBuilder.append(" · $distText")
            textLayout.addView(TextView(this).apply {
                text = infoBuilder
                setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                textSize = 13f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                layoutParams = lp
            })

            card.addView(textLayout)

            // Favorite star
            val isFav = FavoritesManager.isFavorite(lineId)
            val starTv = TextView(this).apply {
                text = if (isFav) "⭐" else "☆"
                textSize = 20f
                setPadding(12, 0, 0, 0)
                gravity = Gravity.CENTER
            }
            starTv.setOnClickListener {
                val nowFav = FavoritesManager.toggleFavorite(lineId, first.lineName)
                starTv.text = if (nowFav) "⭐" else "☆"
                loadFavorites()
            }
            card.addView(starTv)

            card.setOnClickListener {
                AppLogger.log("Main", "Click line: ${first.lineName} lineId=$lineId")
                showDirectionDialog(lineId, first.lineName, stations)
            }
            layoutResults.addView(card)
        }
    }

    private fun formatDistance(distance: Int): String {
        return if (distance >= 1000) {
            String.format("%.1fkm", distance / 1000.0)
        } else {
            "${distance}m"
        }
    }

    private fun showDirectionDialog(lineId: String, lineName: String, stations: List<BusApi.StationResult>) {
        AppLogger.log("Main", "showDirectionDialog lineId=$lineId")

        if (!isNetworkAvailable()) {
            Toast.makeText(this, "无网络连接", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading dialog first
        val loadingDialog = Dialog(this)
        DialogUtils.applyRoundedDialogStyle(loadingDialog)
        val loadingLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.mi_card))
            gravity = Gravity.CENTER
        }
        loadingLayout.addView(ProgressBar(this@MainActivity).apply {
            isIndeterminate = true
        })
        loadingLayout.addView(TextView(this@MainActivity).apply {
            text = "正在获取线路信息..."
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        })
        loadingDialog.setContentView(loadingLayout)
        loadingDialog.show()

        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { BusApi.getLineInfo(lineId) }
                loadingDialog.dismiss()
                AppLogger.log("Main", "Line info: ${info.lineName} start0=${info.startSiteName} end0=${info.endSiteName}")

                // Show direction selection dialog
                val dialog = Dialog(this@MainActivity)
                DialogUtils.applyRoundedDialogStyle(dialog)

                val layout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.mi_card))
                }

                // Title
                layout.addView(TextView(this@MainActivity).apply {
                    text = "选择乘车方向"
                    setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, dpToPx(16))
                    gravity = Gravity.CENTER
                })

                // Direction 0
                val dir0Text = "▶ ${info.startSiteName} → ${info.endSiteName}"
                val dir0First = if (info.firstTime1.isNotEmpty()) "首班 ${info.firstTime1}" else ""
                val dir0Last = if (info.lastTime1.isNotEmpty()) "末班 ${info.lastTime1}" else ""
                val dir0Running = isLineRunning(info.firstTime1, info.lastTime1)

                val btn0 = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.card_bg)
                    setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpToPx(12) }
                    layoutParams = lp
                    isClickable = true
                    isFocusable = true
                    foreground = getRippleDrawable()
                    setOnClickListener {
                        dialog.dismiss()
                        navigateToDetail(lineId, stations.firstOrNull()?.siteId ?: "", stations.firstOrNull()?.siteName ?: "", "0", lineName)
                    }
                }

                val dir0Header = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                dir0Header.addView(TextView(this@MainActivity).apply {
                    text = dir0Text
                    setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                })
                if (dir0Running != null) {
                    dir0Header.addView(TextView(this@MainActivity).apply {
                        text = if (dir0Running) "🟢 运营中" else "🔴 已停运"
                        setTextColor(ContextCompat.getColor(context, if (dir0Running) R.color.mi_success else R.color.mi_danger))
                        textSize = 12f
                    })
                }
                btn0.addView(dir0Header)

                // First/last time and price in horizontal row
                val dir0InfoRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 6 }
                    layoutParams = lp
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (dir0First.isNotEmpty()) {
                    dir0InfoRow.addView(TextView(this@MainActivity).apply {
                        text = dir0First
                        setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                        textSize = 12f
                    })
                }
                if (dir0Last.isNotEmpty()) {
                    dir0InfoRow.addView(TextView(this@MainActivity).apply {
                        text = if (dir0First.isNotEmpty()) "  ·  ${dir0Last}" else dir0Last
                        setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                        textSize = 12f
                    })
                }
                dir0InfoRow.addView(TextView(this@MainActivity).apply {
                    text = if (dir0First.isNotEmpty() || dir0Last.isNotEmpty()) "  ·  ¥${info.price}" else "¥${info.price}"
                    setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                })
                btn0.addView(dir0InfoRow)
                layout.addView(btn0)

                // Direction 1 (if exists)
                if (info.startSiteName1.isNotEmpty() && info.endSiteName1.isNotEmpty()) {
                    val dir1Text = "◀ ${info.startSiteName1} → ${info.endSiteName1}"
                    val dir1First = if (info.firstTime2.isNotEmpty()) "首班 ${info.firstTime2}" else ""
                    val dir1Last = if (info.lastTime2.isNotEmpty()) "末班 ${info.lastTime2}" else ""
                    val dir1Running = isLineRunning(info.firstTime2, info.lastTime2)

                    val btn1 = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.card_bg)
                        setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dpToPx(12) }
                        layoutParams = lp
                        isClickable = true
                        isFocusable = true
                        foreground = getRippleDrawable()
                        setOnClickListener {
                            dialog.dismiss()
                            navigateToDetail(lineId, stations.firstOrNull()?.siteId ?: "", stations.firstOrNull()?.siteName ?: "", "1", lineName)
                        }
                    }

                    val dir1Header = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    dir1Header.addView(TextView(this@MainActivity).apply {
                        text = dir1Text
                        setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = lp
                    })
                    if (dir1Running != null) {
                        dir1Header.addView(TextView(this@MainActivity).apply {
                            text = if (dir1Running) "🟢 运营中" else "🔴 已停运"
                            setTextColor(ContextCompat.getColor(context, if (dir1Running) R.color.mi_success else R.color.mi_danger))
                            textSize = 12f
                        })
                    }
                    btn1.addView(dir1Header)

                    val dir1InfoRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 6 }
                        layoutParams = lp
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    if (dir1First.isNotEmpty()) {
                        dir1InfoRow.addView(TextView(this@MainActivity).apply {
                            text = dir1First
                            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                            textSize = 12f
                        })
                    }
                    if (dir1Last.isNotEmpty()) {
                        dir1InfoRow.addView(TextView(this@MainActivity).apply {
                            text = if (dir1First.isNotEmpty()) "  ·  ${dir1Last}" else dir1Last
                            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                            textSize = 12f
                        })
                    }
                    dir1InfoRow.addView(TextView(this@MainActivity).apply {
                        text = if (dir1First.isNotEmpty() || dir1Last.isNotEmpty()) "  ·  ¥${info.price}" else "¥${info.price}"
                        setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                    })
                    btn1.addView(dir1InfoRow)
                    layout.addView(btn1)
                }

                // Close text
                layout.addView(TextView(this@MainActivity).apply {
                    text = "关闭"
                    setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                    textSize = 14f
                    gravity = Gravity.CENTER
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(48)
                    ).apply { topMargin = dpToPx(8) }
                    layoutParams = lp
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss() }
                })

                dialog.setContentView(layout)
                dialog.show()

                // Set dialog width to 90% of screen
                dialog.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.9).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )

            } catch (e: Exception) {
                loadingDialog.dismiss()
                AppLogger.log("Main", "showDirectionDialog ERROR: ${e.message}")
                // Fallback: go directly with stream=0
                if (stations.isNotEmpty()) {
                    navigateToDetail(lineId, stations.first().siteId, stations.first().siteName, "0", lineName)
                }
            }
        }
    }

    private fun getRippleDrawable(): android.graphics.drawable.RippleDrawable {
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        val typedArray = obtainStyledAttributes(attrs)
        val drawable = typedArray.getDrawable(0)
        typedArray.recycle()
        return drawable as android.graphics.drawable.RippleDrawable
    }

    private fun navigateToDetail(lineId: String, siteId: String, siteName: String, stream: String, lineName: String) {
        AppLogger.log("Main", "navigateToDetail lineId=$lineId stream=$stream")
        val intent = Intent(this, LineDetailActivity::class.java).apply {
            putExtra("lineId", lineId)
            putExtra("siteId", siteId)
            putExtra("siteName", siteName)
            putExtra("stream", stream)
            putExtra("lineName", lineName)
        }
        startActivity(intent)
    }

    private fun showEmptyResult(message: String) {
        layoutResults.removeAllViews()
        layoutError.visibility = View.GONE
        val tv = TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_hint))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        layoutResults.addView(tv)
    }

    private fun showError(message: String) {
        layoutResults.removeAllViews()
        layoutEmpty.visibility = View.GONE
        tvErrorMessage.text = message
        layoutError.visibility = View.VISIBLE
    }
    private fun isLineRunning(firstTime: String, lastTime: String): Boolean? {
        if (firstTime.isEmpty() || lastTime.isEmpty()) return null
        try {
            val now = java.util.Calendar.getInstance()
            val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            val firstParts = firstTime.split(":")
            val lastParts = lastTime.split(":")
            if (firstParts.size < 2 || lastParts.size < 2) return null
            val firstMinutes = firstParts[0].toInt() * 60 + firstParts[1].toInt()
            val lastMinutes = lastParts[0].toInt() * 60 + lastParts[1].toInt()
            return if (firstMinutes <= lastMinutes) {
                currentMinutes in firstMinutes..lastMinutes
            } else {
                // Cross midnight (e.g. 22:00 - 05:00)
                currentMinutes >= firstMinutes || currentMinutes <= lastMinutes
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun requestLocationAndLoad() {
        AppLogger.log("Location", "requestLocationAndLoad")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.log("Location", "No permission, requesting")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
            return
        }
        loadNearby()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            AppLogger.log("Location", "Permission result: granted=${grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED}")
            loadNearby()
        }
    }

    private fun loadNearby() {
        AppLogger.log("Location", "loadNearby")
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            var location: Location? = null
            for (provider in providers) {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        location = lm.getLastKnownLocation(provider)
                        AppLogger.log("Location", "Provider=$provider location=${location != null}")
                        if (location != null) break
                    }
                } catch (e: Exception) {
                    AppLogger.log("Location", "Provider=$provider error: ${e.message}")
                }
            }

            if (location != null) {
                currentLat = location.latitude
                currentLon = location.longitude
            } else {
                currentLat = 31.4169
                currentLon = 119.4845
                AppLogger.log("Location", "Using default Liyang center")
            }
            AppLogger.log("Location", "lat=$currentLat lon=$currentLon")
            fetchNearby(currentLat, currentLon)
        } catch (e: Exception) {
            AppLogger.log("Location", "ERROR: ${e.message}")
            currentLat = 31.4169
            currentLon = 119.4845
            fetchNearby(currentLat, currentLon)
        }
    }

    private fun fetchNearby(lat: Double, lon: Double) {
        tvNearbyTitle.visibility = View.VISIBLE
        layoutNearby.removeAllViews()

        val loadingTv = TextView(this).apply {
            text = "正在获取附近站点..."
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_hint))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        layoutNearby.addView(loadingTv)

        lifecycleScope.launch {
            try {
                AppLogger.log("Nearby", "Fetching nearby lat=$lat lon=$lon")
                val stations = withContext(Dispatchers.IO) {
                    val r = BusApi.getNearby(lat, lon)
                    AppLogger.log("Nearby", "Got ${r.size} stations")
                    r
                }
                layoutNearby.removeAllViews()
                if (stations.isEmpty()) {
                    val tv = TextView(this@MainActivity).apply {
                        text = "附近没有找到公交站点"
                        setTextColor(ContextCompat.getColor(context, R.color.mi_text_hint))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, 16, 0, 16)
                    }
                    layoutNearby.addView(tv)
                } else {
                    nearbyLoaded = true
                    // Group by siteId to aggregate lines per station
                    val grouped = stations.groupBy { it.siteId }
                    for ((siteId, stationLines) in grouped) {
                        val first = stationLines.first()
                        val lineCount = stationLines.size
                        val card = createStationCard(first.siteName, lineCount, first.distance)
                        var expandedContainer: LinearLayout? = null
                        card.setOnClickListener {
                            if (expandedContainer != null) {
                                // Collapse
                                card.removeView(expandedContainer)
                                expandedContainer = null
                            } else {
                                // Expand: show lines inline
                                expandedContainer = LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(dpToPx(16), dpToPx(8), 0, 0)
                                }
                                for (line in stationLines) {
                                    val lineRow = LinearLayout(this@MainActivity).apply {
                                        orientation = LinearLayout.HORIZONTAL
                                        gravity = Gravity.CENTER_VERTICAL
                                        setPadding(0, dpToPx(6), 0, dpToPx(6))
                                        isClickable = true
                                        isFocusable = true
                                    }
                                    lineRow.addView(TextView(this@MainActivity).apply {
                                        text = "🚌 ${line.lineName}"
                                        setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                                        textSize = 14f
                                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                        layoutParams = lp
                                    })
                                    if (line.distance != null) {
                                        lineRow.addView(TextView(this@MainActivity).apply {
                                            text = formatDistance(line.distance)
                                            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                                            textSize = 12f
                                        })
                                    }
                                    lineRow.setOnClickListener {
                                        showDirectionDialog(line.lineId, line.lineName, stationLines)
                                    }
                                    expandedContainer!!.addView(lineRow)
                                    // Divider
                                    expandedContainer!!.addView(View(this@MainActivity).apply {
                                        layoutParams = LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                                        )
                                        setBackgroundColor(ContextCompat.getColor(context, R.color.mi_divider))
                                    })
                                }
                                card.addView(expandedContainer)
                            }
                        }
                        layoutNearby.addView(card)
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("Nearby", "ERROR: ${e.message}")
                layoutNearby.removeAllViews()
                val errLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 16)
                }
                errLayout.addView(TextView(this@MainActivity).apply {
                    text = "获取附近站点失败"
                    setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
                    textSize = 14f
                    gravity = Gravity.CENTER
                })
                errLayout.addView(TextView(this@MainActivity).apply {
                    text = "点击重试"
                    setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, 8, 0, 8)
                    setOnClickListener {
                        loadNearby()
                    }
                })
                layoutNearby.addView(errLayout)
            }
        }
    }

    private fun showLinesForStation(station: BusApi.StationResult) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "无网络连接", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) {
                    BusApi.getLinesByStation(station.siteName, station.siteId)
                }
                if (lines.isEmpty()) {
                    Toast.makeText(this@MainActivity, "该站点没有公交线路", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showLinesDialog(station.siteName, station.siteId, lines)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "获取线路失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLinesDialog(siteName: String, siteId: String, lines: List<BusApi.LineResult>) {
        val dialog = Dialog(this)
        DialogUtils.applyRoundedDialogStyle(dialog)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.mi_card))
        }

        val title = TextView(this).apply {
            text = "📍 $siteName - 经过线路"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        val scroll = ScrollView(this)
        val lineLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (line in lines) {
            val btn = TextView(this).apply {
                text = "🚌 ${line.lineName}"
                setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
                textSize = 15f
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    dialog.dismiss()
                    val intent = Intent(this@MainActivity, LineDetailActivity::class.java).apply {
                        putExtra("lineId", line.lineId)
                        putExtra("siteId", siteId)
                        putExtra("siteName", siteName)
                        putExtra("stream", "0")
                        putExtra("lineName", line.lineName)
                    }
                    startActivity(intent)
                }
            }
            lineLayout.addView(btn)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(16, 0, 16, 0) }
                setBackgroundColor(ContextCompat.getColor(context, R.color.mi_divider))
            }
            lineLayout.addView(divider)
        }

        scroll.addView(lineLayout)
        layout.addView(scroll)

        val closeBtn = TextView(this).apply {
            text = "关闭"
            setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(closeBtn)

        dialog.setContentView(layout)
        dialog.show()
    }

    private fun createStationCard(siteName: String, lineCount: Int, distance: Int?): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            layoutParams = lp
            setPadding(20, 16, 20, 16)
            isClickable = true
            isFocusable = true
        }

        val nameTv = TextView(this).apply {
            text = "🚏 $siteName"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        card.addView(nameTv)

        val distText = if (distance != null) " · ${formatDistance(distance)}" else ""
        val infoTv = TextView(this).apply {
            text = "经过 $lineCount 条线路$distText"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_secondary))
            textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
            layoutParams = lp
        }
        card.addView(infoTv)

        return card
    }


    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun showLogDialog() {
        AppLogger.log("Main", "Log dialog opened")
        val tv = TextView(this).apply {
            text = AppLogger.getAll()
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            setTextIsSelectable(true)
            isLongClickable = true
            setPadding(32, 24, 32, 24)
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制全部") { _, _ ->
                AppLogger.copyToClipboard(this)
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("清除") { _, _ ->
                AppLogger.clear()
            }
            .create()
        DialogUtils.applyAlertDialogStyle(dialog)
        DialogUtils.stripAlertDialogButtonBorders(dialog)
        dialog.show()
    }

    private fun showTransferDialog() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val etOrigin = EditText(this).apply {
            hint = "起点站名"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.mi_text_hint))
            textSize = 15f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(etOrigin)

        val tvArrow = TextView(this).apply {
            text = "↓"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.mi_orange))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        layout.addView(tvArrow)

        val etDest = EditText(this).apply {
            hint = "终点站名"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.mi_text_hint))
            textSize = 15f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(etDest)

        val tvStatus = TextView(this).apply {
            text = "输入站点名称后点击查询"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_hint))
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        layout.addView(tvStatus)

        val dialog = AlertDialog.Builder(this)
            .setTitle("🔄 换乘查询")
            .setView(layout)
            .setNegativeButton("关闭", null)
            .setPositiveButton("查询", null)
            .create()
        DialogUtils.applyAlertDialogStyle(dialog)
        DialogUtils.stripAlertDialogButtonBorders(dialog)

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val origin = etOrigin.text.toString().trim()
            val dest = etDest.text.toString().trim()
            if (origin.isEmpty() || dest.isEmpty()) {
                tvStatus.text = "请输入起点和终点站名"
                return@setOnClickListener
            }
            tvStatus.text = "正在查询..."
            performTransferSearch(origin, dest, tvStatus, dialog)
        }
    }

    private fun performTransferSearch(origin: String, dest: String, tvStatus: TextView, dialog: AlertDialog) {
        lifecycleScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                AppLogger.log("Transfer", "=== 开始换乘查询: $origin → $dest ===")

                // Step 1: Search for origin and destination stations
                var originName = origin
                var destName = dest

                val t1 = System.currentTimeMillis()
                var originResults = withContext(Dispatchers.IO) { BusApi.search(origin) }
                AppLogger.log("Transfer", "Step1 搜索起点: ${System.currentTimeMillis() - t1}ms, ${originResults.size}条")

                // Fuzzy match origin if not found
                if (originResults.isEmpty()) {
                    tvStatus.text = "正在模糊搜索起点..."
                    val fuzzy = fuzzyStationSearch(origin)
                    if (fuzzy.isNotEmpty()) {
                        val picked = showStationPickDialog("起点", origin, fuzzy)
                        if (picked != null) {
                            originName = picked
                            originResults = withContext(Dispatchers.IO) { BusApi.search(picked) }
                        }
                    }
                }

                val t2 = System.currentTimeMillis()
                var destResults = withContext(Dispatchers.IO) { BusApi.search(dest) }
                AppLogger.log("Transfer", "Step1 搜索终点: ${System.currentTimeMillis() - t2}ms, ${destResults.size}条")

                // Fuzzy match dest if not found
                if (destResults.isEmpty()) {
                    tvStatus.text = "正在模糊搜索终点..."
                    val fuzzy = fuzzyStationSearch(dest)
                    if (fuzzy.isNotEmpty()) {
                        val picked = showStationPickDialog("终点", dest, fuzzy)
                        if (picked != null) {
                            destName = picked
                            destResults = withContext(Dispatchers.IO) { BusApi.search(picked) }
                        }
                    }
                }

                if (originResults.isEmpty()) {
                    tvStatus.text = "未找到起点站「$origin」"
                    return@launch
                }
                if (destResults.isEmpty()) {
                    tvStatus.text = "未找到终点站「$dest」"
                    return@launch
                }

                // Get unique lines for origin and destination
                val originLines = originResults.groupBy { it.lineId }
                val destLines = destResults.groupBy { it.lineId }

                val results = mutableListOf<String>()

                // Step 2: Find direct routes
                val tStep2 = System.currentTimeMillis()
                val directLineIds = originLines.keys.intersect(destLines.keys)
                for (lineId in directLineIds) {
                    val lineName = originLines[lineId]!!.first().lineName
                    val originStation = originLines[lineId]!!.first().siteName
                    val destStation = destLines[lineId]!!.first().siteName

                    // Count stations between origin and dest
                    val allStations = try {
                        withContext(Dispatchers.IO) { BusApi.getLineStations(lineId) }
                    } catch (_: Exception) { emptyList() }
                    val stream0 = allStations.filter { it.stream == 0 }.sortedBy { it.orderNo }
                    val oIdx = stream0.indexOfFirst { it.siteName == originStation }
                    val dIdx = stream0.indexOfFirst { it.siteName == destStation }
                    val stationCount = if (oIdx >= 0 && dIdx >= 0) Math.abs(dIdx - oIdx) else -1
                    val estMin = if (stationCount > 0) stationCount * 3 + 5 else -1

                    val stationText = if (stationCount > 0) " · ${stationCount}站" else ""
                    val timeText = if (estMin > 0) " · 约${estMin}分钟" else ""
                    results.add("🚌 直达 $lineName\n   $originStation → $destStation${stationText}${timeText}")
                }

                AppLogger.log("Transfer", "Step2 直达: ${System.currentTimeMillis() - tStep2}ms, ${results.size}条")

                // Shared cache for all line stations (used by Step 3 & 4)
                val lineStationsCache = mutableMapOf<String, List<BusApi.LineStation>>()
                suspend fun cachedLineStations(lineId: String): List<BusApi.LineStation> {
                    return lineStationsCache.getOrPut(lineId) {
                        try { withContext(Dispatchers.IO) { BusApi.getLineStations(lineId) } }
                        catch (_: Exception) { emptyList() }
                    }
                }

                // Step 3: Find one-transfer routes
                val tStep3 = System.currentTimeMillis()
                var step3ApiCalls = 0
                if (results.isEmpty() || results.size < 3) {
                    // Pre-cache all origin and dest line stations
                    for ((oLineId, _) in originLines) { cachedLineStations(oLineId) }
                    for ((dLineId, _) in destLines) { cachedLineStations(dLineId) }
                    step3ApiCalls = lineStationsCache.size

                    for ((oLineId, oStations) in originLines) {
                        val oLineName = oStations.first().lineName
                        val oLineAllStations = lineStationsCache[oLineId] ?: continue

                        for (station in oLineAllStations.distinctBy { it.siteName }) {
                            for ((dLineId, dStations) in destLines) {
                                if (dLineId == oLineId) continue
                                val dLineName = dStations.first().lineName
                                val dLineAllStations = lineStationsCache[dLineId] ?: continue

                                val transferStation = dLineAllStations.find { ds ->
                                    ds.siteName == station.siteName
                                }
                                if (transferStation != null) {
                                    val originSite = oStations.first().siteName
                                    val destSite = dStations.first().siteName

                                    val oLine0 = oLineAllStations.filter { it.stream == 0 }.sortedBy { it.orderNo }
                                    val dLine0 = dLineAllStations.filter { it.stream == 0 }.sortedBy { it.orderNo }

                                    val oi = oLine0.indexOfFirst { it.siteName == originSite }
                                    val ti = oLine0.indexOfFirst { it.siteName == station.siteName }
                                    val oStops = if (oi >= 0 && ti >= 0) Math.abs(ti - oi) else -1

                                    val ti2 = dLine0.indexOfFirst { it.siteName == station.siteName }
                                    val di = dLine0.indexOfFirst { it.siteName == destSite }
                                    val dStops = if (ti2 >= 0 && di >= 0) Math.abs(di - ti2) else -1

                                    val totalStops = if (oStops > 0 && dStops > 0) oStops + dStops else -1
                                    val estMin = if (totalStops > 0) totalStops * 3 + 10 else -1
                                    val stopsText = if (totalStops > 0) " · 共${totalStops}站" else ""
                                    val timeText = if (estMin > 0) " · 约${estMin}分钟" else ""

                                    val desc = "🔀 换乘 $oLineName → $dLineName\n" +
                                            "   $originSite →【${station.siteName}】换乘 → $destSite${stopsText}${timeText}"
                                    if (results.none { it.contains(station.siteName) && it.contains(oLineName) && it.contains(dLineName) }) {
                                        results.add(desc)
                                    }
                                }
                            }
                        }
                        if (results.size >= 5) break
                    }
                }
                AppLogger.log("Transfer", "Step3 一次换乘: ${System.currentTimeMillis() - tStep3}ms, 缓存${step3ApiCalls}条线路, ${results.size}条")

                // Step 4: Find two-transfer routes (origin line → mid line → dest line)
                if (results.isEmpty()) {
                    val tStep4 = System.currentTimeMillis()
                    var step4SearchCalls = 0
                    var step4LineCalls = 0
                    tvStatus.text = "正在查找多次换乘方案..."
                    // lineStationsCache and cachedLineStations already available from Step 3

                    val checkedMidLines = mutableSetOf<String>()
                    val searchedStations = mutableSetOf<String>()

                    // Build priority station list: terminals first, then middle stations
                    data class StationPriority(val name: String, val oLineId: String, val priority: Int)
                    val stationQueue = mutableListOf<StationPriority>()
                    for ((oLineId, oStations) in originLines) {
                        val oLineStations = cachedLineStations(oLineId)
                        val stream0 = oLineStations.filter { it.stream == 0 }.sortedBy { it.orderNo }
                        if (stream0.isEmpty()) continue
                        // Priority 0: first & last stations (most likely hubs)
                        stationQueue.add(StationPriority(stream0.first().siteName, oLineId, 0))
                        stationQueue.add(StationPriority(stream0.last().siteName, oLineId, 0))
                        // Priority 1: middle stations (every 3rd)
                        for (i in 2 until stream0.size step 3) {
                            stationQueue.add(StationPriority(stream0[i].siteName, oLineId, 1))
                        }
                    }
                    stationQueue.sortBy { it.priority }

                    checked@ for (sp in stationQueue) {
                        if (sp.name in searchedStations) continue
                        searchedStations.add(sp.name)
                        val oLineId = sp.oLineId
                        val oLineName = originLines[oLineId]!!.first().lineName
                        val originSite = originLines[oLineId]!!.first().siteName

                        val midResults = try {
                            step4SearchCalls++
                            withContext(Dispatchers.IO) { BusApi.search(sp.name) }
                        }
                        catch (_: Exception) { continue }
                        val midLines = midResults.groupBy { it.lineId }

                        for ((midLineId, _) in midLines) {
                            if (originLines.containsKey(midLineId)) continue
                            if (midLineId in checkedMidLines) continue
                            checkedMidLines.add(midLineId)

                            val midStations = cachedLineStations(midLineId).also { step4LineCalls++ }
                            val midStationNames = midStations.filter { it.stream == 0 }.map { it.siteName }.toSet()

                            for (dLineId in destLines.keys) {
                                if (midLineId == dLineId) continue
                                val dStations = cachedLineStations(dLineId)
                                val dStationNames = dStations.filter { it.stream == 0 }.map { it.siteName }.toSet()
                                val transfer2 = midStationNames.intersect(dStationNames)
                                if (transfer2.isNotEmpty()) {
                                    val midLineName = midResults.firstOrNull { it.lineId == midLineId }?.lineName ?: midLineId
                                    val dLineName = destLines[dLineId]!!.first().lineName
                                    val destSite = destLines[dLineId]!!.first().siteName
                                    val tStation = transfer2.first()

                                    val oLine0 = lineStationsCache[oLineId]?.filter { it.stream == 0 }?.sortedBy { it.orderNo } ?: emptyList()
                                    val mLine0 = midStations.filter { it.stream == 0 }.sortedBy { it.orderNo }
                                    val dLine0 = dStations.filter { it.stream == 0 }.sortedBy { it.orderNo }

                                    val s1 = oLine0.indexOfFirst { it.siteName == originSite }
                                    val s2 = oLine0.indexOfFirst { it.siteName == sp.name }
                                    val s3 = mLine0.indexOfFirst { it.siteName == sp.name }
                                    val s4 = mLine0.indexOfFirst { it.siteName == tStation }
                                    val s5 = dLine0.indexOfFirst { it.siteName == tStation }
                                    val s6 = dLine0.indexOfFirst { it.siteName == destSite }

                                    val leg1 = if (s1 >= 0 && s2 >= 0) Math.abs(s2 - s1) else -1
                                    val leg2 = if (s3 >= 0 && s4 >= 0) Math.abs(s4 - s3) else -1
                                    val leg3 = if (s5 >= 0 && s6 >= 0) Math.abs(s6 - s5) else -1
                                    val totalStops = if (leg1 > 0 && leg2 > 0 && leg3 > 0) leg1 + leg2 + leg3 else -1
                                    val estMin = if (totalStops > 0) totalStops * 3 + 15 else -1
                                    val stopsText = if (totalStops > 0) " · 共${totalStops}站" else ""
                                    val timeText = if (estMin > 0) " · 约${estMin}分钟" else ""

                                    val desc = "🔀 换乘 $oLineName → $midLineName → $dLineName\n" +
                                            "   $originSite →【${sp.name}】→【$tStation】→ $destSite${stopsText}${timeText}"
                                    if (results.none { it.contains(sp.name) && it.contains(tStation) && it.contains(midLineName) }) {
                                        results.add(desc)
                                    }
                                    if (results.size >= 3) break@checked
                                }
                            }
                        }
                    }
                    AppLogger.log("Transfer", "Step4 两次换乘: ${System.currentTimeMillis() - tStep4}ms, search${step4SearchCalls}次, line${step4LineCalls}次, ${results.size}条")
                }

                AppLogger.log("Transfer", "=== 总耗时: ${System.currentTimeMillis() - t0}ms ===")

                // Show results
                if (results.isEmpty()) {
                    tvStatus.text = "未找到换乘方案"
                } else {
                    // Show results in the main layout
                    showTransferResults(results, originName, destName)
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                tvStatus.text = "查询失败: ${e.message}"
                AppLogger.log("Transfer", "Error: ${e.message}")
            }
        }
    }

    private fun showTransferResults(results: List<String>, origin: String, dest: String) {
        layoutResults.removeAllViews()
        savedSearchResults = null

        // Title
        layoutResults.addView(TextView(this).apply {
            text = "🔄 $origin → $dest"
            setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 12)
        })

        for (result in results) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                setPadding(20, 16, 20, 16)
            }
            card.addView(TextView(this).apply {
                text = result
                setTextColor(ContextCompat.getColor(context, R.color.mi_text_primary))
                textSize = 14f
            })
            layoutResults.addView(card)
        }
    }

    /**
     * Fuzzy station search: progressively shorten keyword until we get results.
     * Returns unique station names.
     */
    private suspend fun fuzzyStationSearch(keyword: String): List<String> {
        var key = keyword
        while (key.length >= 2) {
            val results = withContext(Dispatchers.IO) { BusApi.search(key) }
            if (results.isNotEmpty()) {
                return results.map { it.siteName }.distinct().sorted()
            }
            key = key.dropLast(1)
        }
        return emptyList()
    }

    /**
     * Show a suspend dialog for user to pick a station. Returns selected name or null.
     */
    private suspend fun showStationPickDialog(
        title: String, keyword: String, stations: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        runOnUiThread {
            val items = stations.toTypedArray()
            val pickDialog = AlertDialog.Builder(this)
                .setTitle("未找到「$keyword」，请选择$title")
                .setItems(items) { _, which ->
                    if (cont.isActive) cont.resumeWith(Result.success(stations[which]))
                }
                .setNegativeButton("关闭") { _, _ ->
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
                .create()
            DialogUtils.applyAlertDialogStyle(pickDialog)
            DialogUtils.stripAlertDialogButtonBorders(pickDialog)
            pickDialog.show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore search results if we have saved state
        if (savedSearchResults != null && layoutResults.childCount == 0 && lastSearchQuery.isNotEmpty()) {
            etSearch.setText(lastSearchQuery)
            showSearchResults(savedSearchResults!!)
        }
        // Only fetch nearby if we don't already have results
        if (currentLat != 0.0 && currentLon != 0.0 && !nearbyLoaded) {
            fetchNearby(currentLat, currentLon)
        }
        // Always refresh favorites
        loadFavorites()
    }
}
