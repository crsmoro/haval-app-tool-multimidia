package br.com.redesurftank.havalshisuku.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import br.com.redesurftank.havalshisuku.hud.HudOverlayController
import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry
import br.com.redesurftank.havalshisuku.managers.WazeManeuverIconClassifier
import br.com.redesurftank.havalshisuku.managers.WazeNativeNavigationParser
import br.com.redesurftank.havalshisuku.managers.WazeNavigationStepGate
import br.com.redesurftank.havalshisuku.utils.ShizukuUtils
import java.util.concurrent.Executors

/** Reads only Waze's exposed next-street/distance nodes and samples its icon once per route step. */
class WazeNativeNavigationAccessibilityService : AccessibilityService() {
    private data class Snapshot(
        val distanceText: String?,
        val streetText: String?,
        val iconBounds: Rect?
    )

    private data class PendingCapture(val bounds: Rect, val stepVersion: Long)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stepGate = WazeNavigationStepGate()
    private val captureExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "WazeHudIconCapture").apply { isDaemon = true }
    }
    private val captureLock = Any()

    private var isWazeForeground = false
    private var noRoutePending = false
    private var noRouteSettled = false
    private var lastTreeReadAtMs = 0L
    private var lastDirections: AndroidAutoNavigationTelemetry.Directions? = null
    private var currentTurn = WazeNativeNavigationParser.UNKNOWN_TURN
    private var stepVersion = 0L
    private var lastCaptureRequestedStep = -1L
    private var captureWorkerRunning = false
    private var pendingCapture: PendingCapture? = null

    private val clearNoRoute = Runnable {
        noRoutePending = false
        if (isWazeForeground) clearRoute()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        info.packageNames = arrayOf(WAZE_PACKAGE)
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = NOTIFICATION_TIMEOUT_MS
        serviceInfo = info

        val root = rootInActiveWindow
        val wazeIsOpen = try {
            root?.packageName?.toString() == WAZE_PACKAGE
        } finally {
            root?.recycle()
        }
        if (wazeIsOpen) setWazeForeground(true, processNow = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != WAZE_PACKAGE) return
        setWazeForeground(true, processNow = false)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            processActiveWindow(force = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        }
    }

    override fun onInterrupt() {
        setWazeForeground(false, processNow = false)
        clearRoute()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        setWazeForeground(false, processNow = false)
        clearRoute()
        captureExecutor.shutdownNow()
        synchronized(captureLock) {
            pendingCapture = null
            captureWorkerRunning = false
        }
        super.onDestroy()
    }

    private fun setWazeForeground(foreground: Boolean, processNow: Boolean) {
        if (isWazeForeground == foreground) {
            if (foreground && processNow) processActiveWindow(force = true)
            return
        }
        isWazeForeground = foreground
        lastTreeReadAtMs = 0L
        if (foreground) {
            noRoutePending = false
            noRouteSettled = false
            mainHandler.removeCallbacks(clearNoRoute)
            if (processNow) processActiveWindow(force = true)
        } else {
            clearRoute()
        }
    }

    private fun processActiveWindow(force: Boolean = false) {
        if (!isWazeForeground) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastTreeReadAtMs < MIN_TREE_READ_INTERVAL_MS) return
        lastTreeReadAtMs = now

        val snapshot = try {
            readWazeSnapshot()
        } catch (_: Exception) {
            null
        }
        val basic = WazeNativeNavigationParser.parse(
            snapshot?.distanceText,
            snapshot?.streetText,
            WazeNativeNavigationParser.UNKNOWN_TURN
        )
        if (snapshot == null || basic == null) {
            scheduleNoRouteClear()
            return
        }

        mainHandler.removeCallbacks(clearNoRoute)
        noRoutePending = false
        noRouteSettled = false
        val distanceM = basic.distanceM ?: return
        if (stepGate.shouldCapture(basic.street, distanceM)) {
            currentTurn = WazeNativeNavigationParser.UNKNOWN_TURN
            stepVersion++
        }

        val directions = WazeNativeNavigationParser.parse(
            snapshot.distanceText,
            snapshot.streetText,
            currentTurn
        ) ?: return
        lastDirections = directions
        HudOverlayController.onNativeWazeDirections(directions)

        val iconBounds = snapshot.iconBounds
        if (iconBounds != null && lastCaptureRequestedStep != stepVersion) {
            lastCaptureRequestedStep = stepVersion
            enqueueCapture(iconBounds, stepVersion)
        }
    }

    private fun readWazeSnapshot(): Snapshot? {
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != WAZE_PACKAGE) return null
            return Snapshot(
                distanceText = findWazeViewText(root, DISTANCE_VIEW_ID),
                streetText = findWazeViewText(root, STREET_VIEW_ID),
                iconBounds = findWazeViewBounds(root, DIRECTION_VIEW_ID)
            )
        } finally {
            root.recycle()
        }
    }

    private fun findWazeViewText(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId("$WAZE_PACKAGE:id/$viewId") ?: emptyList()
        try {
            return nodes.firstOrNull { it.isVisibleToUser }?.let(::nodeText)
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun findWazeViewBounds(root: AccessibilityNodeInfo, viewId: String): Rect? {
        val nodes = root.findAccessibilityNodeInfosByViewId("$WAZE_PACKAGE:id/$viewId") ?: emptyList()
        try {
            val node = nodes.firstOrNull { it.isVisibleToUser } ?: return null
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return bounds.takeUnless { it.isEmpty }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text
        return node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }


    private fun scheduleNoRouteClear() {
        if (noRoutePending || noRouteSettled) return
        noRoutePending = true
        mainHandler.postDelayed(clearNoRoute, NO_ROUTE_GRACE_MS)
    }

    private fun clearRoute() {
        mainHandler.removeCallbacks(clearNoRoute)
        noRoutePending = false
        noRouteSettled = true
        lastDirections = null
        currentTurn = WazeNativeNavigationParser.UNKNOWN_TURN
        stepGate.reset()
        stepVersion++
        lastCaptureRequestedStep = -1L
        synchronized(captureLock) { pendingCapture = null }
        HudOverlayController.onNativeWazeDirections(null)
    }

    private fun enqueueCapture(bounds: Rect, version: Long) {
        var startWorker = false
        synchronized(captureLock) {
            pendingCapture = PendingCapture(Rect(bounds), version)
            if (!captureWorkerRunning) {
                captureWorkerRunning = true
                startWorker = true
            }
        }
        if (!startWorker) return
        try {
            captureExecutor.execute(::drainCaptureQueue)
        } catch (_: Exception) {
            synchronized(captureLock) {
                pendingCapture = null
                captureWorkerRunning = false
            }
        }
    }

    private fun drainCaptureQueue() {
        while (true) {
            val request = synchronized(captureLock) {
                val next = pendingCapture
                pendingCapture = null
                if (next == null) captureWorkerRunning = false
                next
            } ?: return

            val turn = try {
                captureTurn(request.bounds)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (!isWazeForeground || request.stepVersion != stepVersion) return@post
                currentTurn = turn ?: WazeNativeNavigationParser.UNKNOWN_TURN
                val latest = lastDirections ?: return@post
                val updated = latest.copy(turn = currentTurn)
                lastDirections = updated
                HudOverlayController.onNativeWazeDirections(updated)
            }
        }
    }

    /** One event-triggered screen stream; pixels are decoded and classified locally, never saved. */
    private fun captureTurn(bounds: Rect): String? {
        if (!ShizukuUtils.isShizukuAvailable()) return null
        val script = "screencap -p 2>/dev/null | base64 2>/dev/null || true; exit 0"
        val encoded = ShizukuUtils.runCommandAndGetOutput(arrayOf("sh", "-c", script))
        if (encoded.isBlank()) return null

        val png = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!hasPngSignature(png)) return null

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, options)
        val screenWidth = options.outWidth
        val screenHeight = options.outHeight
        if (screenWidth <= 0 || screenHeight <= 0 ||
            screenWidth.toLong() * screenHeight > MAX_SCREEN_PIXELS) return null

        var screen: Bitmap? = null
        var crop: Bitmap? = null
        return try {
            screen = BitmapFactory.decodeByteArray(
                png, 0, png.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: return null
            val paddingX = (bounds.width() / 20).coerceAtLeast(2)
            val paddingY = (bounds.height() / 20).coerceAtLeast(2)
            val left = (bounds.left - paddingX).coerceAtLeast(0)
            val top = (bounds.top - paddingY).coerceAtLeast(0)
            val right = (bounds.right + paddingX).coerceAtMost(screen.width)
            val bottom = (bounds.bottom + paddingY).coerceAtMost(screen.height)
            val width = right - left
            val height = bottom - top
            if (width !in MIN_ICON_SIDE..MAX_ICON_SIDE || height !in MIN_ICON_SIDE..MAX_ICON_SIDE) return null

            crop = Bitmap.createBitmap(screen, left, top, width, height)
            val pixels = IntArray(width * height)
            crop.getPixels(pixels, 0, width, 0, 0, width, height)
            WazeManeuverIconClassifier.classify(pixels, width, height)
        } catch (_: Exception) {
            null
        } finally {
            crop?.recycle()
            screen?.recycle()
        }
    }

    private fun hasPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        return PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
    }

    companion object {
        private const val WAZE_PACKAGE = "com.waze"
        private const val APP_PACKAGE = "br.com.redesurftank.havalshisuku"
        private const val DISTANCE_VIEW_ID = "navBarDistance"
        private const val STREET_VIEW_ID = "navBarStreetLine"
        private const val DIRECTION_VIEW_ID = "navBarDirection"
        private const val NOTIFICATION_TIMEOUT_MS = 500L
        private const val MIN_TREE_READ_INTERVAL_MS = 250L
        private const val NO_ROUTE_GRACE_MS = 1_800L

        private const val MIN_ICON_SIDE = 20
        private const val MAX_ICON_SIDE = 512
        private const val MAX_SCREEN_PIXELS = 8_000_000L
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        @Volatile private var instance: WazeNativeNavigationAccessibilityService? = null

        /** Called by the existing metadata-only accessibility service when the foreground app changes. */
        @JvmStatic
        fun onForegroundPackageChanged(packageName: String) {
            val service = instance ?: return
            service.mainHandler.post {
                when (packageName) {
                    WAZE_PACKAGE -> service.setWazeForeground(true, processNow = true)
                    APP_PACKAGE, "com.android.systemui" -> Unit
                    else -> service.setWazeForeground(false, processNow = false)
                }
            }
        }
    }
}
