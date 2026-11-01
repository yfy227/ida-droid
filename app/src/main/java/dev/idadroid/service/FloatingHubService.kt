package dev.idadroid.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.idadroid.agent.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * V6.2: FloatingHub — DSHA 式悬浮窗生态中枢。
 *
 * 形态: 悬浮球(可拖动+边缘吸附+位置记忆) → 点击展开浮窗面板容器
 * 面板(可扩展): ①状态 HUD ②工具快捷栏(插件工具一键调, 插件可声明挂载)
 * 最小化: 面板关闭收进球, 球点击恢复
 *
 * 权限: SYSTEM_ALERT_WINDOW(调用方需先 canDrawOverlays 检查)
 */
class FloatingHubService : android.app.Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store
    private val savedStateRegistryOwner = FloatingSavedStateOwner()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryOwner.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var ballView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var panelOpen = false

    // 球位置(持久化记忆)
    private val prefs by lazy { getSharedPreferences("floating_hub", Context.MODE_PRIVATE) }
    private var ballX = 0
    private var ballY = 0

    // 数据源
    private var hud: HubHud = HubHud()
    private var pluginManager: PluginManager? = null

    data class HubHud(
        val model: String = "",
        val phase: String = "",
        val isWorking: Boolean = false,
        val toolCalls: Int = 0,
        val tokensIn: Long = 0,
        val tokensOut: Long = 0
    )

    class FloatingSavedStateOwner : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val lifecycle: Lifecycle = LifecycleRegistry(this)
        init { controller.performRestore(null) }
    }

    companion object {
        const val ACTION_START = "dev.idadroid.action.FLOATING_HUB_START"
        const val ACTION_STOP = "dev.idadroid.action.FLOATING_HUB_STOP"

        /** 权限检查(悬浮窗可用性) */
        fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** 授权引导 */
        fun overlayPermissionIntent(): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:dev.idadroid")
        )

        fun start(context: Context) {
            context.startService(Intent(context, FloatingHubService::class.java).apply { action = ACTION_START })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingHubService::class.java).apply { action = ACTION_STOP })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ballX = prefs.getInt("ball_x", -1)
        ballY = prefs.getInt("ball_y", -1)
        if (ballX < 0) { // 默认: 右侧中部
            val dm = resources.displayMetrics
            ballX = dm.widthPixels - 160
            ballY = dm.heightPixels / 2
        }
        pluginManager = PluginManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf() }
            else -> if (ballView == null) attachBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching { ballView?.let { windowManager.removeView(it) } }
        runCatching { panelView?.let { windowManager.removeView(it) } }
        ballView = null
        panelView = null
        panelOpen = false
    }

    // ════════════════════════════════════════════════════════════
    // 悬浮球
    // ════════════════════════════════════════════════════════════
    @SuppressLint("ClickableViewAccessibility")
    private fun attachBall() {
        val view = ComposeView(this).apply {
            setViewTreeViewModelStoreOwner(this@FloatingHubService)
            setViewTreeLifecycleOwner(this@FloatingHubService)
            setViewTreeSavedStateRegistryOwner(this@FloatingHubService)
            setContent { FloatingBallContent(working = hud.isWorking, onClick = { togglePanel() }) }
        }
        val size = dp(56f)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }

        // 手势分离: 8px 阈值区分点击/拖动 + 边缘吸附 + 位置记忆
        var downX = 0f; var downY = 0f; var moved = false
        var lastX = 0; var lastY = 0
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                        params.x = lastX + dx.toInt()
                        params.y = lastY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 边缘吸附(取近侧) + 持久化
                        val dm = resources.displayMetrics
                        params.x = if (params.x + size / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - size
                        params.x = params.x.coerceIn(0, dm.widthPixels - size)
                        params.y = params.y.coerceIn(0, dm.heightPixels - size * 3)
                        ballX = params.x; ballY = params.y
                        prefs.edit().putInt("ball_x", ballX).putInt("ball_y", ballY).apply()
                        runCatching { windowManager.updateViewLayout(view, params) }
                        lastX = params.x; lastY = params.y
                    } else {
                        lastX = params.x; lastY = params.y
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }
        ballView = view
        runCatching { windowManager.addView(view, params) }
    }

    // ════════════════════════════════════════════════════════════
    // 浮窗面板容器(DSHA shell.overlay: 可挂载, 收起=球)
    // ════════════════════════════════════════════════════════════
    private fun togglePanel() {
        if (panelOpen) {
            runCatching { panelView?.let { windowManager.removeView(it) } }
            panelView = null
            panelOpen = false
            return
        }
        val view = ComposeView(this).apply {
            setViewTreeViewModelStoreOwner(this@FloatingHubService)
            setViewTreeLifecycleOwner(this@FloatingHubService)
            setViewTreeSavedStateRegistryOwner(this@FloatingHubService)
            setContent {
                FloatingPanelContent(
                    hud = hud,
                    pluginTools = pluginManager?.stateSnapshotForHub() ?: emptyList(),
                    onClose = { togglePanel() }
                )
            }
        }
        val w = dp(300f); val h = dp(400f)
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,  // 面板可交互, 面板外传给下层
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX.coerceAtMost(resources.displayMetrics.widthPixels - w)
            y = (ballY + dp(60f)).coerceAtMost(resources.displayMetrics.heightPixels - h)
        }
        panelView = view
        panelOpen = true
        runCatching { windowManager.addView(view, params) }
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /** HUD 数据推送(KeepAliveService/AgentManager 可调) */
    fun pushHud(update: HubHud) {
        hud = update
        ballView?.setContent { FloatingBallContent(working = hud.isWorking, onClick = { togglePanel() }) }
        if (panelOpen) panelView?.setContent {
            FloatingPanelContent(hud = hud, pluginTools = pluginManager?.stateSnapshotForHub() ?: emptyList(), onClose = { togglePanel() })
        }
    }
}

/** 插件工具快照(浮窗快捷栏数据) */
data class HubPluginTool(val pluginId: String, val pluginName: String, val toolName: String, val description: String)

/** PluginManager 扩展: 浮窗快照(只取已启用插件) */
fun PluginManager.stateSnapshotForHub(): List<HubPluginTool> = runCatching {
    state.value.installed.filter { it.enabled }.flatMap { p ->
        p.manifest.tools.map { t -> HubPluginTool(p.manifest.id, p.manifest.name, t.name, t.description) }
    }
}.getOrDefault(emptyList())

// ══════════════════════════════════════════════════════════════
// Compose UI: 球 + 面板
// ══════════════════════════════════════════════════════════════

@Composable
private fun FloatingBallContent(working: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(if (working) Color(0xCC6750A4) else Color(0xCC424242), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (working) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun FloatingPanelContent(
    hud: FloatingHubService.HubHud,
    pluginTools: List<HubPluginTool>,
    onClose: () -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            Modifier.fillMaxSize(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xF21C1B1F),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 头部
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color(0xFFD0BCFF), modifier = Modifier.size(18.dp))
                    Text("IDAdroid 悬浮中枢", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "收起", tint = Color(0xB3FFFFFF), modifier = Modifier.size(16.dp))
                    }
                }
                HorizontalDivider(color = Color(0x1FFFFFFF))

                // 面板①: 状态 HUD
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("状态", color = Color(0xFFD0BCFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(hud.model.ifBlank { "未配置模型" }, color = Color(0xE6FFFFFF), fontSize = 11.sp)
                        Text(if (hud.isWorking) "● ${hud.phase.ifBlank { "工作中" }}" else "○ 空闲", color = if (hud.isWorking) Color(0xFFCE93D8) else Color(0x99FFFFFF), fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("工具调用 ${hud.toolCalls}", color = Color(0x99FFFFFF), fontSize = 10.sp)
                        Text("tokens ${hud.tokensIn}/${hud.tokensOut}", color = Color(0x99FFFFFF), fontSize = 10.sp)
                    }
                }

                HorizontalDivider(color = Color(0x1FFFFFFF))

                // 面板②: 插件工具快捷栏(DSHA 式可挂载 — 启用的插件工具全部出现在这里)
                Text("快捷工具 · ${pluginTools.size}", color = Color(0xFFD0BCFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                if (pluginTools.isEmpty()) {
                    Text("能力市场中启用插件后, 其工具会出现在这里", color = Color(0x66FFFFFF), fontSize = 10.sp)
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(pluginTools) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x14FFFFFF), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Build, contentDescription = null, tint = Color(0x80D0BCFF), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.toolName, color = Color(0xE6FFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(t.description.take(48), color = Color(0x66FFFFFF), fontSize = 9.sp, maxLines = 1)
                                }
                                Text(t.pluginName, color = Color(0x4DD0BCFF), fontSize = 9.sp)
                            }
                        }
                    }
                }

                // 底部提示(最小化到球)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.ExpandLess, contentDescription = null, tint = Color(0x4DFFFFFF), modifier = Modifier.size(12.dp))
                    Text("点击 × 收进悬浮球, 球再点即恢复", color = Color(0x4DFFFFFF), fontSize = 9.sp)
                }
            }
        }
    }
}
