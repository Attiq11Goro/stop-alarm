package com.attiq.stopalarm

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var mainBtn: Button
    private lateinit var search: EditText
    private val chips = mutableListOf<Button>()
    private var dest: GeoPoint? = null
    private var destText = ""
    private var marker: Marker? = null
    private var circle: Polygon? = null
    private var meOverlay: MyLocationNewOverlay? = null
    private var pendingStart = false
    private var wasRunning = false
    private var sel = 1
    private val radii = intArrayOf(500, 1000, 2000, 3000)
    private val labels = listOf("500 m", "1 km", "2 km", "3 km")
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1500) } }
    private val BLUE = 0xFF1565C0.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osm")
            osmdroidTileCache = File(cacheDir, "osm/tiles")
        }
        val root = FrameLayout(this)

        // English-labelled map tiles (CARTO Voyager, based on OpenStreetMap)
        val bases = arrayOf("a", "b", "c", "d").map { "https://$it.basemaps.cartocdn.com/rastertiles/voyager/" }.toTypedArray()
        map = MapView(this).apply {
            setTileSource(XYTileSource("CartoVoyager", 0, 19, 256, ".png", bases, "© OpenStreetMap contributors © CARTO"))
            setMultiTouchControls(true)
            controller.setZoom(14.0); controller.setCenter(GeoPoint(25.3463, 55.4209))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { setDest(p, null); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { setDest(p, null); return true }
            }))
        }
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // Top search bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg(Color.WHITE, 28); elevation = dp(6).toFloat(); setPadding(dp(18), dp(2), dp(6), dp(2))
        }
        search = EditText(this).apply {
            hint = "Search your stop (e.g. Al Nahda bus station)"; setSingleLine(); background = null; textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false }
        }
        val sBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_search); background = null; setOnClickListener { doSearch() } }
        top.addView(search, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(sBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(12), dp(12), 0) })

        // Bottom area
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mid = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(dp(12), 0, dp(12), dp(8)) }
        val attr = TextView(this).apply { text = "© OpenStreetMap · © CARTO"; textSize = 10f; setTextColor(0xFF444444.toInt()); setBackgroundColor(0xAAFFFFFF.toInt()) }
        val fab = Button(this).apply {
            text = "◎"; textSize = 24f; isAllCaps = false; setTextColor(BLUE); background = bg(Color.WHITE, 28)
            elevation = dp(6).toFloat(); setOnClickListener { goMe() }
        }
        mid.addView(attr, LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.BOTTOM })
        mid.addView(fab, LinearLayout.LayoutParams(dp(56), dp(56)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 24); elevation = dp(10).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val title = TextView(this).apply { text = "Where do you get off?"; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF111111.toInt()) }
        info = TextView(this).apply { text = "Search above or tap the map to set your stop"; textSize = 14f; setTextColor(0xFF667085.toInt()); setPadding(0, dp(4), 0, dp(12)) }
        val lbl = TextView(this).apply { text = "Wake me up when I'm within:"; textSize = 13f; setTextColor(0xFF444444.toInt()); setPadding(0, 0, 0, dp(6)) }
        val row = LinearLayout(this)
        labels.forEachIndexed { i, t ->
            val c = Button(this).apply {
                text = t; isAllCaps = false; textSize = 14f; minWidth = 0; minHeight = 0; stateListAnimator = null
                setOnClickListener { sel = i; styleChips(); drawDest() }
            }
            chips.add(c); row.addView(c, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        styleChips()
        mainBtn = Button(this).apply {
            textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); isAllCaps = false; stateListAnimator = null
            setOnClickListener { onMain() }
        }
        card.addView(title); card.addView(info); card.addView(lbl); card.addView(row)
        card.addView(mainBtn, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(14) })
        bottom.addView(mid)
        bottom.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        refresh()
        if (hasLoc()) enableMe() else requestPerms()
    }

    private fun styleChips() = chips.forEachIndexed { i, b ->
        val on = i == sel
        b.background = bg(if (on) BLUE else 0xFFF1F3F5.toInt(), 12)
        b.setTextColor(if (on) Color.WHITE else 0xFF333333.toInt())
    }

    @Suppress("DEPRECATION")
    private fun running() = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(50).any { it.service.className == AlarmService::class.java.name }

    private fun refresh() {
        val r = running()
        if (r) {
            mainBtn.text = "■  STOP ALARM"; mainBtn.background = bg(0xFFD92D20.toInt(), 16)
            info.text = "🔔 Alarm is ON. You can lock your phone and sleep."
        } else {
            mainBtn.text = "▶  START TRIP"; mainBtn.background = bg(0xFF12B76A.toInt(), 16)
            if (wasRunning) info.text = if (dest != null) "$destText\nReady. Tap START TRIP." else "Search above or tap the map to set your stop"
        }
        wasRunning = r
    }

    private fun onMain() {
        if (running()) startService(Intent(this, AlarmService::class.java).setAction(AlarmService.STOP))
        else onStartClicked()
        h.postDelayed({ refresh() }, 700)
    }

    private fun doSearch() {
        val q = search.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        info.text = "Searching…"
        val bb = map.boundingBox
        Thread {
            val res = try {
                val u = "https://nominatim.openstreetmap.org/search?format=json&limit=6&accept-language=en&q=" +
                    URLEncoder.encode(q, "UTF-8") + "&viewbox=${bb.lonWest},${bb.latNorth},${bb.lonEast},${bb.latSouth}"
                val c = URL(u).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "StopAlarm/1.0 (personal app)"); c.connectTimeout = 10000; c.readTimeout = 10000
                JSONArray(c.inputStream.bufferedReader().readText())
            } catch (e: Exception) { null }
            runOnUiThread {
                if (res == null) { info.text = "Search failed. Check your internet."; return@runOnUiThread }
                if (res.length() == 0) { info.text = "No results. Try another name or tap the map."; return@runOnUiThread }
                fun pick(i: Int) {
                    val o = res.getJSONObject(i)
                    val p = GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble())
                    setDest(p, o.getString("display_name")); map.controller.setZoom(16.0); map.controller.animateTo(p)
                }
                if (res.length() == 1) pick(0)
                else {
                    val names = Array(res.length()) { res.getJSONObject(it).getString("display_name") }
                    AlertDialog.Builder(this).setTitle("Choose your stop").setItems(names) { _, i -> pick(i) }.show()
                }
            }
        }.start()
    }

    private fun goMe() {
        if (!hasLoc()) { requestPerms(); return }
        val me = meOverlay?.myLocation
        if (me != null) { map.controller.setZoom(16.0); map.controller.animateTo(me) }
        else Toast.makeText(this, "Waiting for GPS…", Toast.LENGTH_SHORT).show()
    }

    private fun hasLoc() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (hasLoc()) { enableMe(); if (pendingStart) begin() }
        else Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
        pendingStart = false
    }

    private fun enableMe() {
        if (meOverlay != null) return
        meOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { o ->
            o.enableMyLocation()
            o.runOnFirstFix { runOnUiThread { if (dest == null) o.myLocation?.let { map.controller.setZoom(15.0); map.controller.animateTo(it) } } }
            map.overlays.add(o)
        }
    }

    private fun setDest(p: GeoPoint, label: String?) {
        dest = p
        val name = label?.split(",")?.take(3)?.joinToString(",")?.trim() ?: "%.5f, %.5f".format(p.latitude, p.longitude)
        destText = "📍 $name"
        info.text = "$destText\nReady. Tap START TRIP."
        drawDest()
    }

    private fun drawDest() {
        val p = dest ?: return
        marker?.let { map.overlays.remove(it) }; circle?.let { map.overlays.remove(it) }
        circle = object : Polygon(map) {
            override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean = false
        }.apply {
            points = Polygon.pointsAsCircle(p, radii[sel].toDouble())
            fillPaint.color = 0x332196F3; outlinePaint.color = BLUE; outlinePaint.strokeWidth = 3f
        }
        marker = object : Marker(map) {
            override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean = false
        }.apply { position = p }
        map.overlays.add(circle); map.overlays.add(marker); map.invalidate()
    }

    private fun onStartClicked() {
        if (dest == null) { Toast.makeText(this, "Search or tap the map to choose your stop first", Toast.LENGTH_LONG).show(); return }
        if (!hasLoc()) { pendingStart = true; requestPerms(); return }
        begin()
    }

    private fun begin() {
        val d = dest ?: return
        startForegroundService(Intent(this, AlarmService::class.java).putExtra("lat", d.latitude).putExtra("lon", d.longitude).putExtra("r", radii[sel]))
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        h.postDelayed({ refresh() }, 800)
    }

    override fun onResume() { super.onResume(); map.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); map.onPause(); h.removeCallbacks(tick) }
}
