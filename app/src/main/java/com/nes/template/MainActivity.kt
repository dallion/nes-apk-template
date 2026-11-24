package com.nes.template

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.LibretroDroid
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    private lateinit var game: GLRetroView
    private lateinit var storage: GameStorage
    private lateinit var input: InputRouter
    private lateinit var controller: ControllerInput
    private lateinit var controls: TouchControls
    private lateinit var playArea: FrameLayout
    private lateinit var speedHotkeys: SpeedHotkeys
    private lateinit var fpsText: TextView
    private lateinit var speedText: TextView
    private lateinit var diagnostics: DiagnosticLog
    private val fpsCounter = FpsCounter()
    private val autoSaveTimer = AutoSaveTimer()
    private var autoSavePending = false
    private var autoSaveFailureShown = false
    private var cheatBook: CheatBook? = null
    private var bundledCheats: List<CheatEntry> = emptyList()
    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("diagnostics-", ".zip", cacheDir)
                    try {
                        archive.outputStream().use { diagnostics.export(it) }
                        val output = contentResolver.openOutputStream(uri) ?: error("无法打开目标文件")
                        output.use { target -> archive.inputStream().use { it.copyTo(target) } }
                    } finally {
                        archive.delete()
                    }
                }
            }.onSuccess { notify("诊断日志已导出") }
                .onFailure { notify("导出日志失败：${it.message}") }
        }
    }
    private val importCheats = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(CheatParser.MAX_FILE_BYTES+1)
                    var count = 0
                    while(count < buffer.size) {
                        val n = stream.read(buffer,count,buffer.size-count)
                        if(n < 0) break
                        count += n
                    }
                    require(count <= CheatParser.MAX_FILE_BYTES) { "文件不能超过 128 KiB" }
                    buffer.copyOf(count)
                } ?: error("无法打开文件")
                val entries = CheatParser.parse(CheatParser.decode(bytes))
                val book = cheatBook ?: error("金手指记录未就绪")
                book.replace(book.entries+entries)
                notify("已导入 ${entries.size} 条，默认停用")
            }.onFailure { notify("导入失败：${it.message}") }
            lifecycleScope.launch { lifecycle.withResumed { openCheats() } }
        }
    }
    private val session = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var loaded = false
    private var resumed = false
    private lateinit var menu: GameMenu
    private var hasError = false
    private val devices by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = DiagnosticLog.get(this)
        diagnostics.start(applicationContext)
        diagnostics.record("activity create")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val rom = assets.open("game.nes").use { it.readBytes() }
        storage = GameStorage(File(filesDir,"games"),rom,BuildConfig.CORE_ID)
        diagnostics.record("rom sha256=${storage.directory.name}")
        runCatching {
            bundledCheats = if(assets.list("")?.contains("game.cht") == true)
                assets.open("game.cht").use { CheatParser.parseBundled(CheatParser.decode(it.readBytes())) }
            else emptyList()
            cheatBook = CheatBook(storage,bundledCheats)
        }
            .onFailure { notify("读取金手指记录失败，游戏仍可运行") }
        val data = GLRetroViewData(this).apply {
            coreFilePath = File(applicationInfo.nativeLibraryDir,"libretro.so").absolutePath
            gameFilePath = File(storage.directory,"game.nes").apply { writeBytes(rom) }.absolutePath
            savesDirectory = storage.directory.absolutePath
            saveRAMState = storage.readSram()
            variables = arrayOf(Variable("fceumm_turbo_enable", "Player 1"))
        }
        game = diagnosticOperation("game view init") { GLRetroView(this,data) }
        game.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                diagnostics.record("surface holder created hwui=${game.isHardwareAccelerated}")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                diagnostics.record("surface holder changed width=$width height=$height format=$format")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                diagnostics.record("surface holder destroyed")
            }
        })
        speedHotkeys = SpeedHotkeys({ game.frameSpeed }, ::setSpeed)
        input = InputRouter { port,key,pressed ->
            if(!hasError) game.sendKeyEvent(if(pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,key,port)
        }
        controller = ControllerInput(input,getSharedPreferences("controller",MODE_PRIVATE))
        controls = TouchControls(this,input)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff30342d.toInt()) }
        fpsText = statusLabel().apply {
            setText(R.string.fps_pending)
            minWidth = dp(82)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        speedText = statusLabel().apply {
            text = getString(R.string.speed_status,game.frameSpeed)
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6),0,0,0)
            addView(fpsText)
            addView(speedText,LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(6) })
            addView(Space(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
            addView(GameMenu.button(this@MainActivity,"菜单",action=::openMenu),LinearLayout.LayoutParams(-2,-1))
        }
        layout.addView(bar,LinearLayout.LayoutParams(-1,dp(48)))
        playArea = FrameLayout(this).apply {
            setBackgroundColor(0xff20231e.toInt())
            addView(game, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
        }
        layout.addView(playArea, LinearLayout.LayoutParams(-1, 0, 1f))
        playArea.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) updatePlayLayout()
        }
        val root = FrameLayout(this).apply { addView(layout,FrameLayout.LayoutParams(-1,-1)) }
        menu = GameMenu(this,root) {
            autoSavePageGeneration++
            diagnostics.record("menu closed")
            game.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            layout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            game.requestFocus()
            resumeSession()
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            layout.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            menu.setInsets(safe, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        setContentView(root)
        updateTouchControlsVisibility()
        lifecycleScope.launch { game.getGLRetroEvents().collect {
            if(it is GLRetroView.GLRetroEvents.SurfaceCreated) {
                diagnostics.record("game GL surface ready")
                loaded = true
                applyCheats()
            }
            if(it is GLRetroView.GLRetroEvents.FrameRendered && resumed && !menu.isShowing && !hasError) {
                if(autoSaveTimer.onFrame(SystemClock.elapsedRealtime())) saveAutomaticState()
                fpsCounter.onFrame(SystemClock.elapsedRealtimeNanos())?.let { fps ->
                    fpsText.text = String.format(Locale.ROOT,"FPS %.1f",fps)
                }
            }
        } }
        lifecycleScope.launch { game.getGLRetroErrors().collect { code ->
            diagnostics.record("game error code=$code")
            hasError = true; loaded = false; input.releaseAll()
            menu.show("游戏运行失败",onBack={}) {
                text("错误码 $code。请检查 ROM 与当前内核的 Mapper 支持。")
                action("导出诊断日志") { exportDiagnostics.launch("diagnostics.zip") }
                action("退出",primary=true) { finish() }
            }
        } }
        session.registry.currentState = Lifecycle.State.CREATED
        diagnosticOperation("game lifecycle attach") { session.lifecycle.addObserver(game) }
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(menu.isShowing) menu.back() else openMenu() }
        })
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun statusLabel() = TextView(this).apply {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.WHITE)
        setBackgroundColor(0x66000000)
        setPadding(dp(4),dp(2),dp(4),dp(2))
    }
    private fun setSpeed(speed: Int) {
        diagnostics.record("speed=$speed")
        game.frameSpeed = speed
        speedText.text = getString(R.string.speed_status,game.frameSpeed)
    }
    private fun notify(text: String) = Toast.makeText(this,text,Toast.LENGTH_SHORT).show()
    private inline fun <T> diagnosticOperation(name: String, action: () -> T): T {
        diagnostics.record("$name begin")
        try {
            return action().also { diagnostics.record("$name end") }
        } catch (error: Exception) {
            diagnostics.record("$name failed", error)
            throw error
        }
    }
    private fun updateTouchControlsVisibility() {
        val hasController = devices.inputDeviceIds.any { id ->
            devices.getInputDevice(id)?.let {
                ControllerInput.isPhysicalController(it.sources,it.isVirtual)
            } == true
        }
        val visibility = if(hasController) View.GONE else View.VISIBLE
        if(controls.visibility != visibility) {
            input.releaseAll()
            controls.clearVisuals()
            controls.visibility = visibility
            updatePlayLayout()
        }
    }
    private fun updatePlayLayout() {
        if (playArea.width == 0 || playArea.height == 0) return
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val touch = controls.visibility == View.VISIBLE
        val side = if (touch && landscape) minOf(dp(152), playArea.width / 5) else 0
        val bottom = if (touch && !landscape) minOf(dp(184), playArea.height / 2) else 0
        controls.setSideWidth(side)
        controls.layoutParams = FrameLayout.LayoutParams(
            -1, if (landscape) -1 else bottom, Gravity.BOTTOM
        )
        game.layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = side
            rightMargin = side
            bottomMargin = bottom
        }
    }

    private fun pauseSession() {
        autoSaveTimer.pause()
        fpsCounter.reset()
        fpsText.setText(R.string.fps_pending)
        speedHotkeys.clear()
        input.releaseAll(); controls.clearVisuals()
        // GLSurfaceView.onPause waits for rendering to stop before direct native access.
        diagnosticOperation("game pause") { session.registry.currentState = Lifecycle.State.STARTED }
    }
    private fun resumeSession() {
        if(resumed && !menu.isShowing && !hasError) {
            diagnosticOperation("game resume") { session.registry.currentState = Lifecycle.State.RESUMED }
        }
    }
    private fun saveSram() {
        if(loaded && !hasError) runCatching { diagnosticOperation("SaveRAM write") { storage.writeSram(game.serializeSRAM(false)) } }
            .onFailure { notify("游戏存档写入失败：${it.message}") }
    }
    private fun saveAutomaticState() {
        if(autoSavePending || !loaded || hasError) return
        autoSavePending = true
        game.queueEvent {
            val timestamp = System.currentTimeMillis()
            val snapshot = runCatching { diagnosticOperation("auto state capture") { game.serializeState(false) } }
            runOnUiThread {
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { storage.writeAutoSave(snapshot.getOrThrow(),timestamp) }
                        diagnostics.record("auto state saved timestamp=$timestamp")
                        autoSaveFailureShown = false
                    } catch(error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch(error: Exception) {
                        diagnostics.record("auto state save failed",error)
                        if(!autoSaveFailureShown) notify("自动存档失败：${error.message}")
                        autoSaveFailureShown = true
                    } finally {
                        autoSavePending = false
                    }
                }
            }
        }
    }
    override fun onResume() {
        diagnostics.record("activity resume")
        super.onResume(); resumed = true; resumeSession()
        devices.registerInputDeviceListener(this,null)
        updateTouchControlsVisibility()
    }
    override fun onPause() {
        diagnostics.record("activity pause")
        resumed = false; pauseSession(); saveSram()
        devices.unregisterInputDeviceListener(this)
        super.onPause()
    }
    override fun onDestroy() {
        diagnostics.record("activity destroy")
        menu.dismiss()
        diagnosticOperation("game destroy") { session.registry.currentState = Lifecycle.State.DESTROYED }
        super.onDestroy()
    }
    private fun showPanel(title: String, subtitle: String = "", onBack: () -> Unit = ::openMenu, build: GameMenu.Page.() -> Unit) {
        if(!resumed || hasError) return
        autoSavePageGeneration++
        if(!menu.isShowing) {
            diagnostics.record("menu open")
            pauseSession(); saveSram()
            (playArea.parent as View).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        menu.show(title,subtitle,onBack,build)
    }
    private fun openMenu() {
        if(!loaded || hasError) return
        showPanel(getString(R.string.app_name),"游戏已暂停",onBack=menu::dismiss) {
            action("继续游戏",primary=true) { menu.dismiss() }
            section("存档")
            pair("保存即时存档" to {
                runCatching { diagnosticOperation("state save") { storage.writeState(game.serializeState(false)) } }
                    .fold({ notify("即时存档已保存") },{ notify("保存失败：${it.message}") })
            },"读取即时存档" to {
                runCatching {
                    val state = storage.readState()
                    if(state == null) notify("没有有效的即时存档，或内核版本已变化")
                    else if(diagnosticOperation("state restore") { game.unserializeState(state,false) }) {
                        applyCheats(false); notify("即时存档已读取"); menu.dismiss()
                    } else notify("内核拒绝此存档")
                }.onFailure { notify("读取失败：${it.message}") }
            })
            action("读取自动存档") { openAutoSaves() }
            section("游戏设置")
            pair("声音：${if(game.audioEnabled) "开" else "关"}" to {
                game.audioEnabled = !game.audioEnabled; openMenu()
            },"速度：${game.frameSpeed}×" to { speedMenu() })
            pair("金手指" to { openCheats() },"手柄设置" to { mapMenu() })
            action("更多设置") { moreMenu() }
        }
    }
    private fun moreMenu() {
        showPanel("更多设置") {
            action("导出诊断日志") {
                exportDiagnostics.launch("diagnostics.zip")
            }
            action("重置游戏") {
                showPanel("重置游戏","当前未保存的进度将丢失。",onBack=::moreMenu) {
                    action("确认重置",primary=true) {
                        diagnosticOperation("game reset") { game.reset(false) }
                        applyCheats(false); menu.dismiss()
                    }
                    action("返回") { moreMenu() }
                }
            }
            action("退出游戏") { finish() }
            action("返回") { openMenu() }
        }
    }
    private fun openAutoSaves() {
        showPanel("读取自动存档","正在读取……") { action("返回") { openMenu() } }
        val generation = autoSavePageGeneration
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { storage.listAutoSaves() } }
                .onSuccess { entries ->
                    if(!menu.isShowing || generation != autoSavePageGeneration) return@onSuccess
                    showPanel("读取自动存档","每 30 秒保存，保留最近 10 分钟内的最多 20 份。") {
                        if(entries.isEmpty()) text("没有有效的自动存档，或内核版本已变化")
                        val format = SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault())
                        entries.forEachIndexed { index,entry ->
                            action(format.format(Date(entry.timestamp)) + if(index == 0) "  最新" else "") {
                                restoreAutoSave(entry)
                            }
                        }
                        action("返回") { openMenu() }
                    }
                }.onFailure { error ->
                    if(menu.isShowing && generation == autoSavePageGeneration) {
                        showPanel("读取自动存档","读取失败：${error.message}") { action("返回") { openMenu() } }
                    }
                }
        }
    }
    private var autoSavePageGeneration = 0
    private fun restoreAutoSave(entry: GameStorage.AutoSave) {
        showPanel("读取自动存档","正在恢复……") { action("返回") { openMenu() } }
        val generation = autoSavePageGeneration
        lifecycleScope.launch {
            runCatching {
                val state = withContext(Dispatchers.IO) { storage.readAutoSave(entry) }
                if(!menu.isShowing || !resumed || hasError || generation != autoSavePageGeneration) return@runCatching
                if(state == null) { notify("自动存档已失效"); openAutoSaves() }
                else if(diagnosticOperation("auto state restore") { game.unserializeState(state,false) }) {
                    applyCheats(false); notify("自动存档已读取"); menu.dismiss()
                } else { notify("内核拒绝此存档"); openAutoSaves() }
            }.onFailure { notify("读取失败：${it.message}"); if(menu.isShowing) openAutoSaves() }
        }
    }
    private fun applyCheats(onEmulationThread: Boolean = true) {
        if(!loaded || hasError) return
        val codes = cheatBook?.activeCodes() ?: emptyList()
        val action = {
            diagnosticOperation("cheats apply count=${codes.size}") {
                LibretroDroid.resetCheat()
                codes.forEachIndexed { index,code -> game.setCheat(index,true,code,false) }
            }
        }
        if(onEmulationThread) game.queueEvent {
            runCatching(action).onFailure { error -> runOnUiThread { notify("应用金手指失败：${error.message}") } }
        } else action()
    }
    private fun openCheats() {
        if(!resumed || !loaded || hasError) return
        autoSavePageGeneration++
        val book = cheatBook
        if(book == null) {
            showPanel("金手指记录无法读取","可以重新创建空记录。游戏存档不会改变。") {
                action("重新创建",primary=true) {
                    runCatching { cheatBook = CheatBook.clear(storage,bundledCheats) }
                        .onSuccess { openCheats() }.onFailure { notify("创建失败：${it.message}") }
                }
                action("返回") { openMenu() }
            }
            return
        }
        CheatDialogs(this,book,menu,{ applyCheats(false) },{
            runCatching { importCheats.launch(arrayOf("*/*")) }
                .onFailure { notify("无法打开文件选择器：${it.message}") }
        },::openMenu).show()
    }
    private fun speedMenu() {
        showPanel("游戏速度") {
            for(speed in 1..4) action("${speed}×" + if(speed == game.frameSpeed) "  当前" else "") {
                setSpeed(speed); openMenu()
            }
            action("返回") { openMenu() }
        }
    }
    private fun mapMenu() {
        val keys = intArrayOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)
        val labels = listOf("A","B","X（连发 A）","Y（连发 B）","START","SELECT","上","下","左","右")
        showPanel("手柄设置","选择 FC 按钮，再按下要绑定的手柄按键。") {
            keys.forEachIndexed { index,key -> action(labels[index]) { captureKey(key,labels[index]) } }
            action("恢复默认") {
                controller.resetMapping()
                controller = ControllerInput(input,getSharedPreferences("controller",MODE_PRIVATE))
                notify("已恢复默认映射"); mapMenu()
            }
            action("返回") { openMenu() }
        }
    }
    private fun captureKey(target: Int, label: String) {
        showPanel("绑定按键：$label","按下要绑定的实体按钮。方向摇杆默认可用；L1 / R1 保留用于调整速度。",onBack=::mapMenu) {
            action("取消") { mapMenu() }
        }
        var captured = false
        menu.onKey { event ->
            if(ControllerInput.isController(event.source) && event.keyCode != KeyEvent.KEYCODE_BACK) {
                if(event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                    if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) notify("L1 / R1 用于调整游戏速度，请选择其他按钮")
                } else {
                    if(event.action == KeyEvent.ACTION_DOWN && !captured) {
                        controller.remap(event.keyCode,target); captured = true
                    }
                    if(event.action == KeyEvent.ACTION_UP && captured) mapMenu()
                }
                true
            } else false
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePlayLayout()
        window.decorView.post { menu.resize() }
    }
    // Intercept before focused views (including GLRetroView) can bypass remapping.
    // This overrides the public Activity API; androidx.core's inherited restriction is a lint false positive.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(menu.isShowing && menu.key(event)) return true
        if(!menu.isShowing && loaded && resumed && !hasError) {
            if(ControllerInput.isController(event.source) && speedHotkeys.key(event.deviceId,event.keyCode,event.action,event.repeatCount)) return true
            if(controller.key(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if(menu.isShowing && menu.motion(event)) return true
        if(!menu.isShowing && loaded && resumed && !hasError && controller.motion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
    override fun onInputDeviceAdded(deviceId: Int) { diagnostics.record("input device added id=$deviceId"); updateTouchControlsVisibility() }
    override fun onInputDeviceChanged(deviceId: Int) {
        diagnostics.record("input device changed id=$deviceId")
        speedHotkeys.clear()
        input.releaseAll(); controls.clearVisuals(); updateTouchControlsVisibility()
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        diagnostics.record("input device removed id=$deviceId")
        speedHotkeys.clear()
        input.releaseAll(); controls.clearVisuals(); updateTouchControlsVisibility()
    }
}
