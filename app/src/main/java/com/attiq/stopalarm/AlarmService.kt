package com.attiq.stopalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*

class AlarmService : Service() {
    companion object { const val STOP = "STOP" }
    private lateinit var fused: FusedLocationProviderClient
    private var cb: LocationCallback? = null
    private var player: MediaPlayer? = null
    private var wl: PowerManager.WakeLock? = null
    private var ringing = false
    private var radius = 1000
    private var dLat = 0.0
    private var dLon = 0.0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        dLat = i?.getDoubleExtra("lat", 0.0) ?: 0.0
        dLon = i?.getDoubleExtra("lon", 0.0) ?: 0.0
        radius = i?.getIntExtra("r", 1000) ?: 1000
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("track", "Trip tracking", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("alarm", "Arrival alarm", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        ServiceCompat.startForeground(this, 1, notif("Tracking your trip…", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stopalarm:track").apply { acquire(6 * 60 * 60 * 1000L) }
        fused = LocationServices.getFusedLocationProviderClient(this)
        cb?.let { fused.removeLocationUpdates(it) }
        cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                val l = r.lastLocation ?: return
                val out = FloatArray(1)
                Location.distanceBetween(l.latitude, l.longitude, dLat, dLon, out)
                val d = out[0]
                if (d <= radius) { if (!ringing) ring() }
                else if (!ringing) getSystemService(NotificationManager::class.java).notify(1, notif("Distance left: " + (if (d >= 1000) "%.1f km".format(d / 1000) else "${d.toInt()} m"), false))
            }
        }
        try {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).setMinUpdateIntervalMillis(2000).build(), cb!!, mainLooper)
        } catch (_: SecurityException) { stopSelf() }
        return START_REDELIVER_INTENT
    }

    private fun notif(text: String, alarm: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AlarmService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, if (alarm) "alarm" else "track")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(if (alarm) "⏰ Your stop is near!" else "Stop Alarm")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "STOP", stop).build())
        if (alarm) b.setFullScreenIntent(open, true).setCategory(Notification.CATEGORY_ALARM)
        return b.build()
    }

    private fun ring() {
        ringing = true
        getSystemService(NotificationManager::class.java).notify(1, notif("Wake up! Get ready to get off.", true))
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(this@AlarmService, uri); isLooping = true; prepare(); start()
        }
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
    }

    override fun onDestroy() {
        cb?.let { fused.removeLocationUpdates(it) }
        player?.run { try { stop() } catch (_: Exception) {}; release() }
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        wl?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
