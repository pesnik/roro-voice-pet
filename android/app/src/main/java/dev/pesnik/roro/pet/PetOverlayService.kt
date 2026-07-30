package dev.pesnik.roro.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import dev.pesnik.roro.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Foreground Service that keeps a small pet sprite floating over other apps
 * (chat-head style), driven entirely by PetStateController. A tap opens
 * MainActivity; a drag moves the pet. Requires the user to have already
 * granted SYSTEM_ALERT_WINDOW (see SettingsScreen), checked defensively here
 * since the service can in principle be started without that check.
 */
class PetOverlayService : Service() {

    companion object {
        var isRunning: Boolean = false
            private set

        private const val NOTIF_CHANNEL_ID = "pet_overlay"
        private const val NOTIF_ID = 1001
        private const val TAP_MAX_MS = 250L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayRoot: FrameLayout
    private lateinit var petImageView: ImageView
    private var imageLoader: ImageLoader? = null
    private var collectJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true

        startForegroundCompat()
        setupOverlayView()

        val loader = PetImageLoader.build(this)
        imageLoader = loader
        collectJob = serviceScope.launch {
            PetStateController.state.collect { state ->
                val request = ImageRequest.Builder(this@PetOverlayService)
                    .data(PetAssets.forState(state))
                    .target(petImageView)
                    .build()
                loader.enqueue(request)
            }
        }

        // One-shot baseline, mirroring the sidecar's single startup
        // bridge.post("idle", ...) — everything after this is driven by
        // ChatViewModel's own request() calls.
        PetStateController.request(PetState.Idle)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        collectJob?.cancel()
        serviceScope.cancel()
        if (::windowManager.isInitialized && ::overlayRoot.isInitialized) {
            try {
                windowManager.removeView(overlayRoot)
            } catch (_: IllegalArgumentException) {
                // view was never attached (e.g. overlay permission missing) — fine to ignore
            }
        }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_ID, "RoRo pet", NotificationManager.IMPORTANCE_LOW)
        )

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("RoRo is out")
            .setContentText("Tap to open chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sizePx = (96 * resources.displayMetrics.density).toInt()
        petImageView = ImageView(this)
        overlayRoot = FrameLayout(this).apply {
            addView(petImageView, FrameLayout.LayoutParams(sizePx, sizePx))
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        overlayRoot.setOnTouchListener(buildDragTapListener(layoutParams))
        windowManager.addView(overlayRoot, layoutParams)
    }

    /** ACTION_DOWN/MOVE/UP state machine disambiguating a drag (moves the pet)
     * from a tap (opens chat) — a tap is anything under touchSlop movement
     * and under TAP_MAX_MS duration. */
    private fun buildDragTapListener(layoutParams: WindowManager.LayoutParams): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTimeMs = 0L
        var dragging = false

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTimeMs = SystemClock.uptimeMillis()
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(overlayRoot, layoutParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging && SystemClock.uptimeMillis() - downTimeMs < TAP_MAX_MS) {
                        startActivity(
                            Intent(this@PetOverlayService, MainActivity::class.java)
                                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        )
                    }
                    true
                }

                else -> false
            }
        }
    }
}
