package com.nes.template

import android.app.AlertDialog
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
import com.swordfish.libretrodroid.LibretroDroid
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    private lateinit var game: GLRetroView
    private lateinit var storage: GameStorage
    private lateinit var input: InputRouter
    private lateinit var controller: ControllerInput
    private lateinit var controls: TouchControls
    private lateinit var speedHotkeys: SpeedHotkeys
    private lateinit var fpsText: TextView
    private val fpsCounter = FpsCounter()
    private var cheatBook: CheatBook? = null
    private var bundledCheats: List<CheatEntry> = emptyList()
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
    private var menu: AlertDialog? = null
    private var hasError = false
    private val devices by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val rom = assets.open("game.nes").use { it.readBytes() }
        storage = GameStorage(File(filesDir,"games"),rom,BuildConfig.CORE_ID)
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
        }
        game = GLRetroView(this,data)
        speedHotkeys = SpeedHotkeys({ game.frameSpeed }, {
            game.frameSpeed = it
            notify("游戏速度：${it}×")
        })
        input = InputRouter { port,key,pressed ->
            if(!hasError) game.sendKeyEvent(if(pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,key,port)
        }
        controller = ControllerInput(input,getSharedPreferences("controller",MODE_PRIVATE))
        controls = TouchControls(this,input)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff11151c.toInt()) }
        val bar = LinearLayout(this).apply { gravity = Gravity.END }
        bar.addView(Button(this).apply { text = "菜单"; setOnClickListener { openMenu() } })
        layout.addView(bar,LinearLayout.LayoutParams(-1,dp(44)))
        layout.addView(game,LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(controls,LinearLayout.LayoutParams(-1,dp(154)))
        fpsText = TextView(this).apply {
            setText(R.string.fps_pending)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setShadowLayer(dp(1).toFloat(),0f,0f,Color.BLACK)
            setBackgroundColor(0x66000000)
            setPadding(dp(4),dp(2),dp(4),dp(2))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val root = FrameLayout(this).apply {
            addView(layout,FrameLayout.LayoutParams(-1,-1))
            addView(fpsText,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(6)
                topMargin = dp(6)
            })
        }
        setContentView(root)
        updateTouchControlsVisibility()
        lifecycleScope.launch { game.getGLRetroEvents().collect {
            if(it is GLRetroView.GLRetroEvents.SurfaceCreated) {
                loaded = true
                applyCheats()
            }
            if(it is GLRetroView.GLRetroEvents.FrameRendered && resumed && menu == null && !hasError) {
                fpsCounter.onFrame(SystemClock.elapsedRealtimeNanos())?.let { fps ->
                    fpsText.text = String.format(Locale.ROOT,"FPS %.1f",fps)
                }
            }
        } }
        lifecycleScope.launch { game.getGLRetroErrors().collect { code ->
            hasError = true; loaded = false; input.releaseAll()
            AlertDialog.Builder(this@MainActivity).setTitle("游戏运行失败")
                .setMessage("错误码 $code。请检查 ROM 与当前内核的 Mapper 支持。")
                .setPositiveButton("退出") { _,_ -> finish() }.setCancelable(false).show()
        } }
        session.registry.currentState = Lifecycle.State.CREATED
        session.lifecycle.addObserver(game)
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { openMenu() }
        })
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun notify(text: String) = Toast.makeText(this,text,Toast.LENGTH_SHORT).show()
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
        }
    }
    private fun pauseSession() {
        fpsCounter.reset()
        fpsText.setText(R.string.fps_pending)
        speedHotkeys.clear()
        input.releaseAll(); controls.clearVisuals()
        // GLSurfaceView.onPause waits for rendering to stop before direct native access.
        session.registry.currentState = Lifecycle.State.STARTED
    }
    private fun resumeSession() {
        if(resumed && menu == null && !hasError) session.registry.currentState = Lifecycle.State.RESUMED
    }
    private fun saveSram() {
        if(loaded && !hasError) runCatching { storage.writeSram(game.serializeSRAM(false)) }
            .onFailure { notify("游戏存档写入失败：${it.message}") }
    }
    override fun onResume() {
        super.onResume(); resumed = true; resumeSession()
        devices.registerInputDeviceListener(this,null)
        updateTouchControlsVisibility()
    }
    override fun onPause() {
        resumed = false; pauseSession(); saveSram()
        devices.unregisterInputDeviceListener(this)
        super.onPause()
    }
    override fun onDestroy() {
        menu?.dismiss(); menu = null
        session.registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
    private fun openMenu() {
        if(menu != null || !loaded || hasError) return
        pauseSession(); saveSram()
        menu = AlertDialog.Builder(this).setTitle(getString(com.nes.template.R.string.app_name))
            .setItems(arrayOf("继续游戏","保存即时存档","读取即时存档","声音：${if(game.audioEnabled) "开" else "关"}","速度：${game.frameSpeed}×","金手指","手柄按键映射","重置游戏","退出")) { _,which ->
                when(which) {
                    1 -> runCatching { storage.writeState(game.serializeState(false)) }.fold({ notify("即时存档已保存") },{ notify("保存失败：${it.message}") })
                    2 -> runCatching {
                        val state = storage.readState()
                        if(state == null) notify("没有有效的即时存档，或内核版本已变化")
                        else if(game.unserializeState(state,false)) {
                            applyCheats(false)
                            notify("即时存档已读取")
                        } else notify("内核拒绝此存档")
                    }.onFailure { notify("读取失败：${it.message}") }
                    3 -> game.audioEnabled = !game.audioEnabled
                    4 -> window.decorView.post { speedMenu() }
                    5 -> window.decorView.post { openCheats() }
                    6 -> window.decorView.post { mapMenu() }
                    7 -> { game.reset(false); applyCheats(false) }
                    8 -> finish()
                }
            }.create()
        menu!!.setOnDismissListener { menu = null; resumeSession() }
        menu!!.show()
    }
    private fun applyCheats(onEmulationThread: Boolean = true) {
        if(!loaded || hasError) return
        val codes = cheatBook?.activeCodes() ?: emptyList()
        val action = {
            LibretroDroid.resetCheat()
            codes.forEachIndexed { index,code -> game.setCheat(index,true,code,false) }
        }
        if(onEmulationThread) game.queueEvent {
            runCatching(action).onFailure { error -> runOnUiThread { notify("应用金手指失败：${error.message}") } }
        } else action()
    }
    private fun displayCheatDialog(dialog: AlertDialog) {
        if(!resumed || hasError) return
        pauseSession()
        menu = dialog
        dialog.setOnDismissListener { menu = null; resumeSession() }
        dialog.show()
    }
    private fun openCheats() {
        if(!resumed || !loaded || hasError) return
        val book = cheatBook
        if(book == null) {
            displayCheatDialog(AlertDialog.Builder(this).setTitle("金手指记录无法读取")
                .setMessage("可以重新创建空记录。游戏存档不会改变。")
                .setPositiveButton("重新创建") { _,_ ->
                    runCatching { cheatBook = CheatBook.clear(storage,bundledCheats) }
                        .onSuccess { window.decorView.post { openCheats() } }
                        .onFailure { notify("创建失败：${it.message}") }
                }.setNegativeButton("取消",null).create())
            return
        }
        CheatDialogs(this,book,::displayCheatDialog,{ applyCheats(false) },{
            runCatching { importCheats.launch(arrayOf("*/*")) }
                .onFailure { notify("无法打开文件选择器：${it.message}") }
        }).show()
    }
    private fun speedMenu() {
        if(!resumed || hasError) return
        pauseSession()
        menu = AlertDialog.Builder(this).setTitle("游戏速度")
            .setSingleChoiceItems(arrayOf("1×（正常）","2×","3×","4×"),game.frameSpeed-1) { dialog,which ->
                game.frameSpeed = which+1
                notify("游戏速度：${game.frameSpeed}×")
                dialog.dismiss()
            }.setNegativeButton("取消",null).create()
        menu!!.setOnDismissListener { menu = null; resumeSession() }
        menu!!.show()
    }
    private fun mapMenu() {
        if(!resumed || hasError) return
        pauseSession()
        val keys = intArrayOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)
        menu = AlertDialog.Builder(this).setTitle("选择 FC 按钮")
            .setItems(arrayOf("A","B","START","SELECT","上","下","左","右")) { _,index ->
                window.decorView.post { captureKey(keys[index]) }
            }.setNeutralButton("恢复默认") { _,_ -> controller.resetMapping(); recreate() }.create()
        menu!!.setOnDismissListener { menu = null; resumeSession() }; menu!!.show()
    }
    private fun captureKey(target: Int) {
        if(!resumed || hasError) return
        pauseSession()
        val dialog = AlertDialog.Builder(this).setTitle("按下手柄按钮")
            .setMessage("按下要绑定的实体按钮。方向摇杆默认可用；这里绑定按键。")
            .setNegativeButton("取消",null).create()
        var captured = false
        dialog.setOnKeyListener { _,_,event ->
            if(ControllerInput.isController(event.source) && event.keyCode != KeyEvent.KEYCODE_BACK) {
                if(event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                    if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) notify("L1 / R1 用于调整游戏速度，请选择其他按钮")
                    return@setOnKeyListener true
                }
                if(event.action == KeyEvent.ACTION_DOWN && !captured) {
                    controller.remap(event.keyCode,target); captured = true
                }
                if(event.action == KeyEvent.ACTION_UP && captured) dialog.dismiss()
                true
            } else false
        }
        menu = dialog
        dialog.setOnDismissListener { menu = null; resumeSession() }; dialog.show()
    }
    // Intercept before focused views (including GLRetroView) can bypass remapping.
    // This overrides the public Activity API; androidx.core's inherited restriction is a lint false positive.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(menu == null && loaded && resumed && !hasError) {
            if(ControllerInput.isController(event.source) && speedHotkeys.key(event.deviceId,event.keyCode,event.action,event.repeatCount)) return true
            if(controller.key(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if(menu == null && loaded && resumed && !hasError && controller.motion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
    override fun onInputDeviceAdded(deviceId: Int) { updateTouchControlsVisibility() }
    override fun onInputDeviceChanged(deviceId: Int) {
        speedHotkeys.clear()
        input.releaseAll(); controls.clearVisuals(); updateTouchControlsVisibility()
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        speedHotkeys.clear()
        input.releaseAll(); controls.clearVisuals(); updateTouchControlsVisibility()
    }
}

