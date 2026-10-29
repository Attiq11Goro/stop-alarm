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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var mainBtn: Button
    private lateinit var search: EditText
    private lateinit var esri: OnlineTileSourceBase
    private lateinit var otherLink: TextView
    private lateinit var saveLink: TextView
    private val chips = mutableListOf<Button>()
    private var results: JSONArray? = null
    private var english = true
    private var dest: GeoPoint? = null
    private var destName = ""
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
    private val prefs by lazy { getSharedPreferences("sa", MODE_PRIVATE) }

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

        esri = object : OnlineTileSourceBase("EsriStreet", 0, 19, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"), "Tiles © Esri") {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
        }
        map = MapView(this).apply {
            setTileSource(esri)
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
        val attr = TextView(this).apply { text = "v4 · Tiles © Esri · Search © OSM"; textSize = 10f; setTextColor(0xFF444444.toInt()); setBackgroundColor(0xAAFFFFFF.toInt()) }
        val layer = Button(this).apply {
            text = "🗺"; textSize = 20f; isAllCaps = false; background = bg(Color.WHITE, 28)
            elevation = dp(6).toFloat(); setOnClickListener { toggleMap() }
        }
        val fab = Button(this).apply {
            text = "◎"; textSize = 24f; isAllCaps = false; setTextColor(BLUE); background = bg(Color.WHITE, 28)
            elevation = dp(6).toFloat(); setOnClickListener { goMe() }
        }
        mid.addView(attr, LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.BOTTOM })
        mid.addView(layer, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(8) })
        mid.addView(fab, LinearLayout.LayoutParams(dp(56), dp(56)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 24); elevation = dp(10).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val title = TextView(this).apply { text = "Where do you get off?"; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF111111.toInt()) }
        info = TextView(this).apply { text = "Search above, tap the map, or share a place from Google Maps"; textSize = 14f; setTextColor(0xFF667085.toInt()); setPadding(0, dp(4), 0, dp(8)) }
        otherLink = TextView(this).apply {
            text = "Not the right place? See other matches"; textSize = 13f; setTextColor(BLUE); visibility = View.GONE
            setPadding(0, 0, 0, dp(8)); setOnClickListener { showResults() }
        }
        saveLink = TextView(this).apply {
            text = "☆ Save this stop"; textSize = 13f; setTextColor(BLUE); visibility = View.GONE
            setPadding(0, 0, dp(24), dp(10)); setOnClickListener { saveFav() }
        }
        val favLink = TextView(this).apply {
            text = "★ Saved stops"; textSize = 13f; setTextColor(BLUE); setPadding(0, 0, 0, dp(10)); setOnClickListener { showFavs() }
        }
        val linkRow = LinearLayout(this).apply { addView(saveLink); addView(favLink) }
        val lbl = TextView(this).apply { text = "Wake me up when I'm within:"; textSize = 13f; setTextColor(0xFF444444.toInt()); setPadding(0, 0, 0, dp(6)) }
        val row = LinearLayout(this)
        labels.forEachIndexed { i, t ->
            val c = Button(this).apply {
                text = t; isAllCaps = false; textSize = 14f; minWidth = 0; minHeight = 0; stateListAnimator = null
                setOnClickListener { sel = i; prefs.edit().putInt("sel", i).apply(); styleChips(); drawDest() }
            }
            chips.add(c); row.addView(c, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        mainBtn = Button(this).apply {
            textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); isAllCaps = false; stateListAnimator = null
            setOnClickListener { onMain() }
        }
        card.addView(title); card.addView(info); card.addView(otherLink); card.addView(linkRow); card.addView(lbl); card.addView(row)
        card.addView(mainBtn, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(14) })
        bottom.addView(mid)
        bottom.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        sel = prefs.getInt("sel", 1).coerceIn(0, radii.size - 1)
        styleChips()
        restoreLast()
        refresh()
        if (hasLoc()) enableMe() else requestPerms()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------- Google Maps share / geo links ----------
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            i.action = null
            fromShared(text)
        } else if (i.action == Intent.ACTION_VIEW && i.data?.scheme == "geo") {
            val s = try { URLDecoder.decode(i.dataString ?: return, "UTF-8") } catch (e: Exception) { i.dataString ?: return }
            i.action = null
            val p = parseCoords(s)
            if (p != null) { setDest(p, null); map.controller.setZoom(16.0); map.controller.animateTo(p) }
            else {
                val q = Regex("[?&]q=([^&]*)").find(s)?.groupValues?.get(1)
                if (!q.isNullOrBlank()) { search.setText(q); searchFor(q) }
            }
        }
    }

    private fun fromShared(text: String) {
        info.text = "Reading place from Google Maps…"
        val url = Regex("https?://\\S+").find(text)?.value
        val name = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("http") }
        Thread {
            var p = parseCoords(text)
            if (p == null && url != null) p = parseCoords(resolveUrl(url) ?: "")
            val found = p
            runOnUiThread {
                if (found != null) { setDest(found, name); map.controller.setZoom(16.0); map.controller.animateTo(found) }
                else if (name != null) { search.setText(name); searchFor(name) }
                else info.text = "Couldn't read that place. Try searching by name."
            }
        }.start()
    }

    private fun resolveUrl(u: String): String? {
        return try {
            var cur = u
            for (n in 0 until 6) {
                val c = URL(cur).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537.36")
                c.connectTimeout = 10000; c.readTimeout = 10000
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (code in 300..399 && loc != null) {
                    cur = URL(URL(cur), loc).toString()
                    if (parseCoords(cur) != null) return cur
                } else break
            }
            cur
        } catch (e: Exception) { null }
    }

    private fun parseCoords(raw: String): GeoPoint? {
        val s = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
        val pats = listOf(
            "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)",
            "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)",
            "[?&](?:q|ll|query|center|destination)=(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)",
            "(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)"
        )
        for (p in pats) {
            val m = Regex(p).find(s) ?: continue
            val la = m.groupValues[1].toDoubleOrNull() ?: continue
            val lo = m.groupValues[2].toDoubleOrNull() ?: continue
            if (la in -90.0..90.0 && lo in -180.0..180.0) return GeoPoint(la, lo)
        }
        return null
    }

    // ---------- Saved stops ----------
    private fun favs(): JSONArray = try { JSONArray(prefs.getString("fav", "[]")) } catch (e: Exception) { JSONArray() }

    private fun saveFav() {
        val p = dest ?: return
        val a = favs()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("n") == destName) {
            Toast.makeText(this, "Already saved", Toast.LENGTH_SHORT).show(); return
        }
        a.put(JSONObject().put("n", destName).put("lat", p.latitude).put("lon", p.longitude))
        prefs.edit().putString("fav", a.toString()).apply()
        Toast.makeText(this, "Saved ★", Toast.LENGTH_SHORT).show()
    }

    private fun showFavs() {
        val a = favs()
        if (a.length() == 0) {
            Toast.makeText(this, "No saved stops yet. Pick a stop, then tap ☆ Save this stop", Toast.LENGTH_LONG).show(); return
        }
        val names = Array(a.length()) { a.getJSONObject(it).getString("n") }
        val dlg = AlertDialog.Builder(this).setTitle("Saved stops (hold to delete)").setItems(names) { _, i ->
            val o = a.getJSONObject(i)
            val p = GeoPoint(o.getDouble("lat"), o.getDouble("lon"))
            setDest(p, o.getString("n")); map.controller.setZoom(16.0); map.controller.animateTo(p)
        }.create()
        dlg.setOnShowListener {
            dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
                a.remove(i); prefs.edit().putString("fav", a.toString()).apply(); dlg.dismiss()
                Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show(); true
            }
        }
        dlg.show()
    }

    private fun restoreLast() {
        val la = prefs.getString("lat", null)?.toDoubleOrNull() ?: return
        val lo = prefs.getString("lon", null)?.toDoubleOrNull() ?: return
        val p = GeoPoint(la, lo)
        setDest(p, prefs.getString("name", null))
        map.controller.setZoom(15.0); map.controller.setCenter(p)
    }

    // ---------- UI ----------
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
            val d = prefs.getFloat("dist", -1f)
            info.text = when {
                d < 0 -> "🔔 Alarm is ON. Waiting for GPS…"
                d <= radii[sel] -> "⏰ ARRIVING! Tap STOP ALARM to silence it."
                else -> "🔔 Alarm is ON · " + (if (d >= 1000) String.format(Locale.US, "%.1f km", d / 1000) else "${d.toInt()} m") + " to go\nLock your phone and sleep."
            }
        } else {
            mainBtn.text = "▶  START TRIP"; mainBtn.background = bg(0xFF12B76A.toInt(), 16)
            if (wasRunning) info.text = if (dest != null) "$destText\nReady. Tap START TRIP." else "Search above, tap the map, or share a place from Google Maps"
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
        if (q.isNotEmpty()) searchFor(q)
    }

    private fun searchFor(q: String) {
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
                results = res
                pickResult(0)
                otherLink.visibility = if (res.length() > 1) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun pickResult(i: Int) {
        val o = results?.getJSONObject(i) ?: return
        val p = GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble())
        setDest(p, o.getString("display_name")); map.controller.setZoom(16.0); map.controller.animateTo(p)
    }

    private fun showResults() {
        val res = results ?: return
        val names = Array(res.length()) { res.getJSONObject(it).getString("display_name") }
        val adapter = object : ArrayAdapter<String>(this, 0, names) {
            override fun getView(i: Int, v: View?, p: ViewGroup): View {
                val parts = getItem(i)!!.split(",").map { it.trim() }
                val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
                l.addView(TextView(context).apply { text = parts[0]; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF111111.toInt()) })
                l.addView(TextView(context).apply { text = parts.drop(1).take(3).joinToString(", "); textSize = 13f; setTextColor(0xFF667085.toInt()) })
                return l
            }
        }
        AlertDialog.Builder(this).setTitle("Other matches").setAdapter(adapter) { _, i -> pickResult(i) }.show()
    }

    private fun toggleMap() {
        english = !english
        map.setTileSource(if (english) esri else TileSourceFactory.MAPNIK)
        Toast.makeText(this, if (english) "Map: English (Esri)" else "Map: Standard (OpenStreetMap)", Toast.LENGTH_SHORT).show()
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
        super.onRequestPermissionsResult(
