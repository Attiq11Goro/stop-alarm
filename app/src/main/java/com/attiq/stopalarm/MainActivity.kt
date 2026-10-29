package com.attiq.stopalarm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var spin: Spinner
    private var dest: GeoPoint? = null
    private var marker: Marker? = null
    private var meOverlay: MyLocationNewOverlay? = null
    private var pendingStart = false
    private val radii = intArrayOf(500, 1000, 2000)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osm")
            osmdroidTileCache = File(cacheDir, "osm/tiles")
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info = TextView(this).apply { text = "Tap the map to set your destination"; textSize = 16f; setPadding(24, 40, 24, 24) }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            controller.setZoom(13.0); controller.setCenter(GeoPoint(25.3463, 55.4209))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { setDest(p); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { setDest(p); return true }
            }))
        }
        spin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Alarm when within 500 m", "Alarm when within 1 km", "Alarm when within 2 km"))
            setSelection(1)
        }
        val row = LinearLayout(this)
        val start = Button(this).apply { text = "START TRIP"; setOnClickListener { onStartClicked() } }
        val stop = Button(this).apply { text = "STOP"; setOnClickListener { startService(Intent(this@MainActivity, AlarmService::class.java).setAction(AlarmService.STOP)); info.text = "Stopped" } }
        row.addView(start, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(info); root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(spin); root.addView(row)
        setContentView(root)
        if (hasLoc()) enableMe() else requestPerms()
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
            o.runOnFirstFix { runOnUiThread { o.myLocation?.let { map.controller.animateTo(it) } } }
            map.overlays.add(o)
        }
    }

    private fun setDest(p: GeoPoint) {
        dest = p
        marker?.let { map.overlays.remove(it) }
        marker = Marker(map).apply { position = p; title = "Destination" }
        map.overlays.add(marker); map.invalidate()
        info.text = "Destination set. Tap START TRIP."
    }

    private fun onStartClicked() {
        if (dest == null) { Toast.makeText(this, "Tap the map to choose a destination first", Toast.LENGTH_LONG).show(); return }
        if (!hasLoc()) { pendingStart = true; requestPerms(); return }
        begin()
    }

    private fun begin() {
        val d = dest ?: return
        val i = Intent(this, AlarmService::class.java).putExtra("lat", d.latitude).putExtra("lon", d.longitude).putExtra("r", radii[spin.selectedItemPosition])
        startForegroundService(i)
        info.text = "Trip started. You can lock the phone and sleep 😴"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
