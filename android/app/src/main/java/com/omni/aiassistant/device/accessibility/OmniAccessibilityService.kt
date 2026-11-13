package com.omni.aiassistant.device.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Last package/window Omni saw. Deliberately contains no screen text. */
data class ActiveApp(
    val packageName: String,
    val windowTitle: String? = null,
)

/** One visible accessibility node, already stripped of sensitive content. */
data class ScreenNode(
    val text: String,
    val description: String,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val focused: Boolean,
    val checked: Boolean,
)

/** Bounded, plain-data view of the screen handed to the model. */
data class ScreenSnapshot(
    val packageName: String,
    val windowTitle: String?,
    val timestamp: Long,
    val nodes: List<ScreenNode>,
    val truncated: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        put("window_title", windowTitle.orEmpty())
        put("timestamp", timestamp)
        put("node_count", nodes.size)
        put("truncated", truncated)
        put("nodes", buildJsonArray {
            nodes.forEach { node ->
                addJsonObject {
                    if (node.text.isNotEmpty()) put("text", node.text)
                    if (node.description.isNotEmpty()) put("desc", node.description)
                    if (node.className.isNotEmpty()) put("class", node.className)
                    put("bounds", node.bounds)
                    if (node.clickable) put("clickable", true)
                    if (node.editable) put("editable", true)
                    if (node.scrollable) put("scrollable", true)
                    if (node.focused) put("focused", true)
                    if (node.checked) put("checked", true)
                }
            }
        })
    }
}

/**
 * Accessibility driver for Omni's explicit screen tools.
 *
 * The service only reads a bounded snapshot (400 nodes / 4 000 text characters)
 * and performs the action it is asked to perform. Password nodes are never
 * read, keystrokes are never observed (the event subscription in
 * `accessibility_service_config.xml` contains no text events), and no
 * screen tree is retained after a call.
 */
class OmniAccessibilityService : AccessibilityService() {

    private val actionLock = Any()
    private var lastActionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val current = _activeApp.value
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { currentWindowTitle() }.getOrNull()
            } else {
                null
            }
            _activeApp.value = ActiveApp(packageName, title)
        } else if (current?.packageName != packageName) {
            _activeApp.value = ActiveApp(packageName, null)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance?.get() === this) {
            instance = null
            _connected.value = false
            _activeApp.value = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance?.get() === this) {
            instance = null
            _connected.value = false
            _activeApp.value = null
        }
        super.onDestroy()
    }

    /**
     * Read the active window plus any other visible windows (dialogs, split
     * screen, notification shade). Text is capped per node and globally so a
     * chatty WebView cannot flood the model context.
     */
    fun readScreen(): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()

        val packageName = activeRoot?.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: _activeApp.value?.packageName.orEmpty()
        var windowTitle = _activeApp.value?.windowTitle
        if (windowTitle.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            windowTitle = runCatching { currentWindowTitle() }.getOrNull()
        }

        val activeWindowId = activeRoot?.let { root ->
            runCatching { root.window?.id }.getOrNull()
        }

        val roots = ArrayList<AccessibilityNodeInfo>(4)
        if (activeRoot != null) roots += activeRoot
        val visibleWindows = runCatching { windows }.getOrNull().orEmpty()
        for (window in visibleWindows) {
            if (window.id == activeWindowId) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            roots += root
        }

        val nodes = ArrayList<ScreenNode>(128)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (index in roots.indices.reversed()) stack.addLast(roots[index])

        var visited = 0
        var usedChars = 0
        var truncated = false

        while (stack.isNotEmpty() && nodes.size < MAX_NODES) {
            val node = stack.removeLast()
            if (++visited > MAX_VISITED_NODES) {
                truncated = true
                break
            }
            val isPassword = runCatching { node.isPassword }.getOrDefault(false)
            if (isPassword) continue
            val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
            if (!visible) continue

            val text = cleanText(node.text, MAX_NODE_TEXT)
            val description = cleanText(node.contentDescription, MAX_NODE_TEXT)
            val clickable = runCatching { node.isClickable }.getOrDefault(false)
            val editable = runCatching { node.isEditable }.getOrDefault(false)
            val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
            val focused = runCatching { node.isFocused }.getOrDefault(false)
            val checked = runCatching { node.isCheckable && node.isChecked }.getOrDefault(false)

            if (text.isNotEmpty() || description.isNotEmpty() || clickable || editable || scrollable) {
                var keptText = text
                var keptDescription = description

                val textRoom = (MAX_TEXT_CHARS - usedChars).coerceAtLeast(0)
                if (keptText.length > textRoom) {
                    keptText = keptText.take(textRoom)
                    truncated = true
                }
                usedChars += keptText.length

                val descriptionRoom = (MAX_TEXT_CHARS - usedChars).coerceAtLeast(0)
                if (keptDescription.length > descriptionRoom) {
                    keptDescription = keptDescription.take(descriptionRoom)
                    truncated = true
                }
                usedChars += keptDescription.length

                if (keptText.isNotEmpty() || keptDescription.isNotEmpty() ||
                    clickable || editable || scrollable
                ) {
                    val bounds = runCatching {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
                    }.getOrDefault("")

                    nodes += ScreenNode(
                        text = keptText,
                        description = keptDescription,
                        className = node.className?.toString()?.substringAfterLast('.')?.take(80)
                            .orEmpty(),
                        bounds = bounds,
                        clickable = clickable,
                        editable = editable,
                        scrollable = scrollable,
                        focused = focused,
                        checked = checked,
                    )
                }
            }

            val childCount = runCatching { node.childCount }.getOrDefault(0)
                .coerceAtMost(MAX_CHILDREN_PER_NODE)
            for (index in childCount - 1 downTo 0) {
                runCatching { node.getChild(index) }.getOrNull()?.let(stack::addLast)
            }
        }

        if (stack.isNotEmpty()) truncated = true

        return ScreenSnapshot(
            packageName = packageName,
            windowTitle = windowTitle,
            timestamp = timestamp,
            nodes = nodes,
            truncated = truncated,
        )
    }

    /**
     * Tap a control by visible text/content description. Exact matches win over
     * substring matches regardless of tree order; case is ignored.
     */
    fun tapText(text: String, exact: Boolean = false): Boolean {
        val query = text.trim()
        if (query.isEmpty()) return false
        return synchronized(actionLock) {
            waitForNextAction()
            val node = runCatching { findMatchingNode(query, exact) }.getOrNull()
                ?: return@synchronized false
            if (clickNodeOrAncestor(node)) return@synchronized true

            val rect = runCatching {
                Rect().also { node.getBoundsInScreen(it) }
            }.getOrNull()
            if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
                return@synchronized false
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@synchronized false
            dispatchTap(rect.centerX(), rect.centerY())
        }
    }

    /** Dispatch a single tap at absolute screen coordinates. */
    fun tapXY(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        // Gesture dispatch exists from Android 7.0; older devices can still
        // use text taps through ACTION_CLICK.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return synchronized(actionLock) {
            waitForNextAction()
            dispatchTap(x, y)
        }
    }

    /** Replace text only in the focused editable field in the active window. */
    fun typeText(text: String): Boolean {
        if (text.length > MAX_TYPE_CHARS) return false
        return synchronized(actionLock) {
            waitForNextAction()
            val node = runCatching { findEditableNode() }.getOrNull()
                ?: return@synchronized false
            // Never touch a password field, even if the model asked us to.
            if (isPasswordNode(node)) return@synchronized false

            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            if (!runCatching { node.isFocused }.getOrDefault(false)) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            }
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
        }
    }

    /** Scroll the first scrollable node that accepts the requested direction. */
    fun scroll(direction: String): Boolean {
        val action = when (direction.trim().lowercase(Locale.ROOT)) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> return false
        }
        return synchronized(actionLock) {
            waitForNextAction()
            val candidates = runCatching { findScrollableNodes() }.getOrNull().orEmpty()
            candidates.any { node ->
                runCatching { node.performAction(action) }.getOrDefault(false)
            }
        }
    }

    /** Back, home, recents, notification shade or quick settings. */
    fun performGlobal(action: String): Boolean {
        val globalAction = when (action.trim().lowercase(Locale.ROOT)) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return synchronized(actionLock) {
            waitForNextAction()
            runCatching { performGlobalAction(globalAction) }.getOrDefault(false)
        }
    }

    /**
     * Capture the default display, downscale to at most 1080 px wide and store
     * a PNG in the app cache. Returns null on Android < 11 and whenever the
     * system refuses (secure windows, timeout).
     */
    fun captureScreenshot(): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return synchronized(actionLock) {
            waitForNextAction()
            runCatching { captureScreenshotR() }.getOrElse { error ->
                if (error is SecurityException) throw error
                null
            }
        }
    }

    // ---------------------------------------------------------------- search

    private fun currentRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null) roots += activeRoot
        val activeWindowId = activeRoot?.let { root ->
            runCatching { root.window?.id }.getOrNull()
        }
        for (window in runCatching { windows }.getOrNull().orEmpty()) {
            if (window.id == activeWindowId) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            roots += root
        }
        return roots
    }

    private fun findMatchingNode(query: String, exactOnly: Boolean): AccessibilityNodeInfo? {
        var containsMatch: AccessibilityNodeInfo? = null
        for (root in currentRoots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < MAX_SEARCH_NODES) {
                val node = stack.removeLast()
                visited++
                if (runCatching { node.isPassword }.getOrDefault(false)) continue
                if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) continue

                val text = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                val exact = text.equals(query, ignoreCase = true) ||
                    description.equals(query, ignoreCase = true)
                if (exact) return node
                if (!exactOnly && containsMatch == null) {
                    val contains = text.contains(query, ignoreCase = true) ||
                        description.contains(query, ignoreCase = true)
                    if (contains) containsMatch = node
                }

                val childCount = runCatching { node.childCount }.getOrDefault(0)
                    .coerceAtMost(MAX_CHILDREN_PER_NODE)
                for (index in childCount - 1 downTo 0) {
                    runCatching { node.getChild(index) }.getOrNull()?.let(stack::addLast)
                }
            }
        }
        return if (exactOnly) null else containsMatch
    }

    private fun findEditableNode(): AccessibilityNodeInfo? {
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        for (root in listOf(activeRoot)) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < MAX_SEARCH_NODES) {
                val node = stack.removeLast()
                visited++
                if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) continue

                val editable = runCatching { node.isEditable }.getOrDefault(false)
                if (editable) {
                    // Return a focused password field as well: the caller must
                    // refuse it instead of silently typing into another field.
                    if (runCatching { node.isFocused }.getOrDefault(false)) return node
                }

                val childCount = runCatching { node.childCount }.getOrDefault(0)
                    .coerceAtMost(MAX_CHILDREN_PER_NODE)
                for (index in childCount - 1 downTo 0) {
                    runCatching { node.getChild(index) }.getOrNull()?.let(stack::addLast)
                }
            }
        }
        return null
    }

    private fun findScrollableNodes(): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>(8)
        loop@ for (root in currentRoots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < MAX_SEARCH_NODES) {
                val node = stack.removeLast()
                visited++
                if (runCatching { node.isPassword }.getOrDefault(false)) continue
                if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) continue

                if (runCatching { node.isScrollable }.getOrDefault(false)) {
                    found += node
                    if (found.size >= MAX_SCROLL_CANDIDATES) break@loop
                }

                val childCount = runCatching { node.childCount }.getOrDefault(0)
                    .coerceAtMost(MAX_CHILDREN_PER_NODE)
                for (index in childCount - 1 downTo 0) {
                    runCatching { node.getChild(index) }.getOrNull()?.let(stack::addLast)
                }
            }
        }
        return found.sortedByDescending { node ->
            runCatching { node.isFocused }.getOrDefault(false)
        }
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= MAX_CLICK_ANCESTOR_DEPTH) {
            val candidate = current
            val clickable = runCatching { candidate.isClickable && candidate.isEnabled }
                .getOrDefault(false)
            if (clickable) {
                return runCatching {
                    candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
            }
            current = runCatching { candidate.parent }.getOrNull()
            depth++
        }
        return false
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= MAX_PASSWORD_ANCESTOR_DEPTH) {
            if (runCatching { current.isPassword }.getOrDefault(false)) return true
            current = runCatching { current.parent }.getOrNull()
            depth++
        }
        return false
    }

    // --------------------------------------------------------------- actions

    private fun waitForNextAction() {
        val elapsed = SystemClock.uptimeMillis() - lastActionAt
        val remaining = MIN_ACTION_INTERVAL_MS - elapsed
        if (remaining > 0) SystemClock.sleep(remaining)
        lastActionAt = SystemClock.uptimeMillis()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchTap(x: Int, y: Int): Boolean = runCatching {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS),
            )
            .build()
        dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.N)
    private fun currentWindowTitle(): String? = runCatching {
        windows.firstOrNull { it.isActive }?.title?.toString()?.trim()?.take(120)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshotR(): File? {
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val screenshotRef = AtomicReference<ScreenshotResult?>(null)
        return try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        screenshotRef.set(screenshot)
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            val screenshot = screenshotRef.get() ?: return null
            val buffer = screenshot.hardwareBuffer

            val bitmap = try {
                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    ?: return null
                try {
                    hardware.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hardware.recycle()
                }
            } finally {
                buffer.close()
            } ?: return null

            val scaled = if (bitmap.width > MAX_SCREENSHOT_WIDTH) {
                val height = (bitmap.height.toLong() * MAX_SCREENSHOT_WIDTH / bitmap.width)
                    .toInt()
                    .coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, MAX_SCREENSHOT_WIDTH, height, true)
            } else {
                bitmap
            }

            try {
                val directory = File(cacheDir, SCREENSHOT_DIR)
                if (!directory.exists() && !directory.mkdirs()) return null
                val file = File(directory, "shot_${System.currentTimeMillis()}.png")
                val saved = FileOutputStream(file).use { output ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                if (!saved) {
                    file.delete()
                    return null
                }
                pruneScreenshots(directory)
                file
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (security: SecurityException) {
            throw security
        } catch (error: Exception) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun pruneScreenshots(directory: File) {
        runCatching {
            val files = directory.listFiles { file ->
                file.isFile && file.name.startsWith("shot_")
            } ?: return@runCatching
            if (files.size <= MAX_SCREENSHOTS) return@runCatching
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_SCREENSHOTS)
                .forEach { it.delete() }
        }
    }

    private fun cleanText(value: CharSequence?, max: Int): String {
        val text = value?.toString()?.trim().orEmpty()
        if (text.length <= max) return text
        return text.take(max).trimEnd()
    }

    companion object {
        private var instance: WeakReference<OmniAccessibilityService>? = null
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _activeApp = MutableStateFlow<ActiveApp?>(null)
        val activeApp: StateFlow<ActiveApp?> = _activeApp.asStateFlow()

        fun current(): OmniAccessibilityService? = instance?.get()
    }
}

private const val MAX_TEXT_CHARS = 4_000
private const val MAX_NODES = 400
private const val MAX_VISITED_NODES = 2_000
private const val MAX_SEARCH_NODES = 3_000
private const val MAX_CHILDREN_PER_NODE = 200
private const val MAX_NODE_TEXT = 300
private const val MAX_TYPE_CHARS = 4_000
private const val MAX_SCROLL_CANDIDATES = 16
private const val MAX_CLICK_ANCESTOR_DEPTH = 12
private const val MAX_PASSWORD_ANCESTOR_DEPTH = 8
private const val MIN_ACTION_INTERVAL_MS = 300L
private const val TAP_DURATION_MS = 60L
private const val SCREENSHOT_TIMEOUT_MS = 5_000L
private const val MAX_SCREENSHOT_WIDTH = 1_080
private const val SCREENSHOT_DIR = "screenshots"
private const val MAX_SCREENSHOTS = 8

