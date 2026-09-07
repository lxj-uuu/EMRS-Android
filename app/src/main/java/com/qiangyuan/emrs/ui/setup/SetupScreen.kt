package com.qiangyuan.emrs.ui.setup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.qiangyuan.emrs.R
import com.qiangyuan.emrs.core.AppState
import com.qiangyuan.emrs.core.StatusHub
import com.qiangyuan.emrs.data.DataDirManager
import com.qiangyuan.emrs.data.GzcsStore
import com.qiangyuan.emrs.data.ProjectStore
import com.qiangyuan.emrs.data.ResultFiles
import com.qiangyuan.emrs.data.WorkParams
import com.qiangyuan.emrs.serial.SerialPortConfig
import com.qiangyuan.emrs.serial.SerialPortFinder
import com.qiangyuan.emrs.ui.ScreenHost
import com.qiangyuan.emrs.ui.ScreenManager

/**
 * Form3 设置屏（架构 ui/setup/SetupScreen.kt / 规格书 §2.3、§3.6）。
 * 三页：图头设置（Form3 页0 9 项）/ 参数设置（页1）/ 串口设置（T05 安卓新增）。
 * 返回：写 gzcs（19 行）+ 项目登记重建 + 按 prg 计算 ruler → 回主屏。
 */
class SetupScreen(
    private val host: ScreenHost,
    container: ViewGroup
) : ScreenManager.Screen {

    override val key = ScreenHost.KEY_SETUP
    override val statusView: TextView? = null

    override val root: View =
        LayoutInflater.from(container.context).inflate(R.layout.screen_setup, container, false)

    private val ctx = root.context
    private val pageHeader = root.findViewById<View>(R.id.page_header)
    private val pageParams = root.findViewById<View>(R.id.page_params)
    private val pageSerial = root.findViewById<View>(R.id.page_serial)

    // ---- 页0 图头 ----
    private val etProjectName = root.findViewById<EditText>(R.id.et_project_name)
    private val etProjectTag = root.findViewById<EditText>(R.id.et_project_tag)
    private val etWorkArea = root.findViewById<EditText>(R.id.et_work_area)
    private val etForwardFile = root.findViewById<EditText>(R.id.et_forward_file)
    private val etLineNo = root.findViewById<EditText>(R.id.et_line_no)
    private val etPointNo = root.findViewById<EditText>(R.id.et_point_no)
    private val etLoopSide = root.findViewById<EditText>(R.id.et_loop_side)
    private val etCoilArea = root.findViewById<EditText>(R.id.et_coil_area)
    private val etOperatorName = root.findViewById<EditText>(R.id.et_operator_name)
    private val etOperatorCode = root.findViewById<EditText>(R.id.et_operator_code)
    private val tvFilePath = root.findViewById<TextView>(R.id.tv_file_path)

    // ---- 页1 参数 ----
    private val spProgNo = root.findViewById<Spinner>(R.id.sp_prog_no)
    private val spSupplyTimes = root.findViewById<Spinner>(R.id.sp_supply_times)
    private val spRatio = root.findViewById<Spinner>(R.id.sp_ratio)
    private val spReceiveMode = root.findViewById<Spinner>(R.id.sp_receive_mode)
    private val spFilter2 = root.findViewById<Spinner>(R.id.sp_filter2)
    private val spFilter3 = root.findViewById<Spinner>(R.id.sp_filter3)
    private val spFilter4 = root.findViewById<Spinner>(R.id.sp_filter4)
    private val spFilter5 = root.findViewById<Spinner>(R.id.sp_filter5)
    private val spFullCurrent = root.findViewById<Spinner>(R.id.sp_full_current)

    // ---- 页2 串口 ----
    private val spSerialDevice = root.findViewById<Spinner>(R.id.sp_serial_device)
    private val spSerialBaud = root.findViewById<Spinner>(R.id.sp_serial_baud)
    private val tvSerialStatus = root.findViewById<TextView>(R.id.tv_serial_status)

    // ---- 页2 串口（USB增量：连接方式 / USB 设备） ----
    private val spConnType = root.findViewById<Spinner>(R.id.sp_conn_type)
    private val rowUsb = root.findViewById<View>(R.id.row_usb)
    private val spUsbDevice = root.findViewById<Spinner>(R.id.sp_usb_device)
    private val tvUsbStatus = root.findViewById<TextView>(R.id.tv_usb_status)

    /** 串口配置存储（USB增量：原生/USB 共用同一份 SerialPortConfig） */
    private val serialPrefs by lazy {
        ctx.getSharedPreferences(SerialPortConfig.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    private val applying = arrayOf(false)   // 防止程序性 setSelection 触发 onItemSelected 回写

    init {
        root.findViewById<Button>(R.id.tab_header).setOnClickListener { switchPage(0) }
        root.findViewById<Button>(R.id.tab_params).setOnClickListener { switchPage(1) }
        root.findViewById<Button>(R.id.tab_serial).setOnClickListener { switchPage(2) }

        root.findViewById<Button>(R.id.btn_gen_forward).setOnClickListener {
            // Form3.Button2Click：生成 zygs.txt
            readHeaderToParams()
            val f = ResultFiles.writeZygs(ctx)
            StatusHub.post(ctx.getString(R.string.zygs_generated) + f.name)
        }
        root.findViewById<Button>(R.id.btn_return).setOnClickListener { saveAndReturn() }

        setupSpinners()
        setupSerialPage()
    }

    // ================= 页签 =================

    private fun switchPage(index: Int) {
        pageHeader.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageParams.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageSerial.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    // ================= 下拉框 =================

    private fun spinnerOf(sp: Spinner, options: List<String>, current: String, onSelect: ((String) -> Unit)? = null) {
        val ad = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, options)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        val idx = options.indexOf(current)
        applying[0] = true
        sp.setSelection(if (idx >= 0) idx else 0)
        sp.post { applying[0] = false }
        if (onSelect != null) {
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (!applying[0]) onSelect(options[pos])
                }

                override fun onNothingSelected(p: AdapterView<*>?) {
                    // 无动作
                }
            }
        }
    }

    private fun setupSpinners() {
        spinnerOf(spProgNo, WorkParams.PRG_OPTIONS, AppState.work.prgNo) {
            AppState.work.prgNo = it
            AppState.prgNo = it.toIntOrNull() ?: 1
        }
        spinnerOf(spSupplyTimes, WorkParams.SUPPLY_TIMES_OPTIONS, AppState.work.supplyTimes) {
            AppState.work.supplyTimes = it
            AppState.supplyTimes = it.toIntOrNull() ?: 1
        }
        spinnerOf(spRatio, WorkParams.RATIO_OPTIONS, AppState.work.ratio) {
            AppState.work.ratio = it
        }
        spinnerOf(spReceiveMode, WorkParams.RECEIVE_MODE_OPTIONS, AppState.work.receiveMode) {
            AppState.work.receiveMode = it
        }
        spinnerOf(spFilter2, WorkParams.FILTER_OPTIONS, AppState.work.filterNo2) { AppState.work.filterNo2 = it }
        spinnerOf(spFilter3, WorkParams.FILTER_OPTIONS, AppState.work.filterNo3) { AppState.work.filterNo3 = it }
        spinnerOf(spFilter4, WorkParams.FILTER_OPTIONS, AppState.work.filterNo4) { AppState.work.filterNo4 = it }
        spinnerOf(spFilter5, WorkParams.FILTER_OPTIONS, AppState.work.filterNo5) { AppState.work.filterNo5 = it }
        spinnerOf(spFullCurrent, WorkParams.FULL_CURRENT_OPTIONS, AppState.fullCurrent.toString()) {
            AppState.fullCurrent = it.toIntOrNull() ?: 2000
        }
    }

    // ================= 串口页 =================

    private fun setupSerialPage() {
        val prefs = serialPrefs
        val cfg = SerialPortConfig.load(prefs)

        root.findViewById<Button>(R.id.btn_serial_refresh).setOnClickListener { fillSerialSpinners() }
        root.findViewById<Button>(R.id.btn_serial_restore).setOnClickListener {
            SerialPortConfig().save(prefs)
            fillSerialSpinners()
            setupConnTypeSpinner()
            applyConnTypeVisibility()
            fillUsbDevices()
            reopenSerial()
        }

        spSerialDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (applying[0]) return
                // USB增量：USB-OTG 模式下原生设备路径选择不生效
                if (SerialPortConfig.load(serialPrefs).connectionType != SerialPortConfig.ConnectionType.NATIVE) return
                val dev = spSerialDevice.selectedItem as? String ?: return
                SerialPortConfig.load(serialPrefs).copy(devicePath = dev).save(serialPrefs)
                reopenSerial()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {
                // 无动作
            }
        }
        spSerialBaud.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (applying[0]) return
                val baud = (spSerialBaud.selectedItem as? String)?.toIntOrNull() ?: return
                // 波特率为原生/USB 共用配置（USB增量）
                SerialPortConfig.load(serialPrefs).copy(baudRate = baud).save(serialPrefs)
                reopenSerial()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {
                // 无动作
            }
        }
        // USB增量：连接方式切换 + USB 设备列表
        setupConnTypeSpinner()
        applyConnTypeVisibility()
        root.findViewById<Button>(R.id.btn_usb_refresh).setOnClickListener { fillUsbDevices() }
        root.findViewById<Button>(R.id.btn_usb_connect).setOnClickListener { connectUsb() }
        // 初始选中当前配置
        fillSerialSpinners()
    }

    /** 连接方式切换（USB增量）：原生串口 / USB-OTG，切换即保存并重连 */
    private fun setupConnTypeSpinner() {
        val cfg = SerialPortConfig.load(serialPrefs)
        val native = ctx.getString(R.string.conn_native)
        val usb = ctx.getString(R.string.conn_usb)
        spinnerOf(
            spConnType, listOf(native, usb),
            if (cfg.connectionType == SerialPortConfig.ConnectionType.USB) usb else native
        ) { sel ->
            val type = if (sel == usb) SerialPortConfig.ConnectionType.USB
            else SerialPortConfig.ConnectionType.NATIVE
            SerialPortConfig.load(serialPrefs).copy(connectionType = type).save(serialPrefs)
            applyConnTypeVisibility()
            host.reconnectSerial()
        }
    }

    /** USB 区块可见性（USB增量） */
    private fun applyConnTypeVisibility() {
        val usbMode = SerialPortConfig.load(serialPrefs).connectionType == SerialPortConfig.ConnectionType.USB
        rowUsb.visibility = if (usbMode) View.VISIBLE else View.GONE
    }

    /** USB 设备列表（USB增量）：支持芯片显示芯片名/VID:PID，未知设备标记不支持 */
    private fun fillUsbDevices() {
        val cfg = SerialPortConfig.load(serialPrefs)
        val devices = host.usbSerialManager().listDevices(ctx)
        val entries = devices.map { it.displayName }
        val ad = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, entries)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spUsbDevice.adapter = ad
        val cur = devices.indexOfFirst { it.vid == cfg.usbVid && it.pid == cfg.usbPid }
        applying[0] = true
        spUsbDevice.setSelection(if (cur >= 0) cur else 0)
        spUsbDevice.post { applying[0] = false }
        tvUsbStatus.text = if (devices.isEmpty()) ctx.getString(R.string.usb_none_detected) else ""
        spUsbDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (applying[0]) return
                val info = devices.getOrNull(pos) ?: return
                if (info.chipName == null) {
                    tvUsbStatus.text = ctx.getString(R.string.usb_chip_unsupported)
                    return
                }
                SerialPortConfig.load(serialPrefs).copy(usbVid = info.vid, usbPid = info.pid).save(serialPrefs)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {
                // 无动作
            }
        }
    }

    /** “授权并连接”（USB增量）：确保 USB 模式后统一重连，系统授权弹窗由 UsbSerialManager 触发 */
    private fun connectUsb() {
        if (SerialPortConfig.load(serialPrefs).connectionType != SerialPortConfig.ConnectionType.USB) {
            SerialPortConfig.load(serialPrefs)
                .copy(connectionType = SerialPortConfig.ConnectionType.USB).save(serialPrefs)
            setupConnTypeSpinner()
            applyConnTypeVisibility()
        }
        host.reconnectSerial()
    }

    private fun fillSerialSpinners() {
        val prefs = ctx.getSharedPreferences(SerialPortConfig.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val cfg = SerialPortConfig.load(prefs)
        val devices = SerialPortFinder.find()
        val adDev = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, devices)
        adDev.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSerialDevice.adapter = adDev
        val di = devices.indexOf(cfg.devicePath)
        applying[0] = true
        spSerialDevice.setSelection(if (di >= 0) di else 0)

        val bauds = SerialPortConfig.BAUD_OPTIONS.map { it.toString() }
        val adBaud = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, bauds)
        adBaud.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSerialBaud.adapter = adBaud
        val bi = bauds.indexOf(cfg.baudRate.toString())
        spSerialBaud.setSelection(if (bi >= 0) bi else 0)
        spSerialBaud.post { applying[0] = false }
        refreshSerialStatusText()
    }

    private fun reopenSerial() {
        SerialPortConfig.load(serialPrefs).save(serialPrefs)
        host.serialManager().open(ctx, null)
        refreshSerialStatusText()
    }

    private fun refreshSerialStatusText() {
        tvSerialStatus.text = StatusHub.serialText
    }

    // ================= 数据进出 =================

    /** 图头编辑框 → WorkParams（Form5 返回时也回写 xxh/ddh，见 SupplyScreen） */
    private fun readHeaderToParams() {
        val p = AppState.work
        p.projectName = etProjectName.text.toString()
        p.projectTag = etProjectTag.text.toString()
        p.workAreaNo = etWorkArea.text.toString()
        p.forwardFile = etForwardFile.text.toString()
        p.lineNo = etLineNo.text.toString()
        p.pointNo = etPointNo.text.toString()
        p.loopSide = etLoopSide.text.toString()
        p.coilArea = etCoilArea.text.toString()
        p.operatorName = etOperatorName.text.toString()
        p.operatorCode = etOperatorCode.text.toString()
    }

    /** Form3.FormCreate 恢复段：参数 → 编辑框 */
    private fun writeParamsToHeader() {
        val p = AppState.work
        etProjectName.setText(p.projectName)
        etProjectTag.setText(p.projectTag)
        etWorkArea.setText(p.workAreaNo)
        etForwardFile.setText(p.forwardFile)
        etLineNo.setText(p.lineNo)
        etPointNo.setText(p.pointNo)
        etLoopSide.setText(p.loopSide)
        etCoilArea.setText(p.coilArea)
        etOperatorName.setText(p.operatorName)
        etOperatorCode.setText(p.operatorCode)
        tvFilePath.text = DataDirManager.path(ctx)
    }

    /** Form3.Button1Click：校验标识 → 写 gzcs/项目文件 → 算 ruler → 回主屏 */
    private fun saveAndReturn() {
        readHeaderToParams()
        if (!WorkParams.validTag(AppState.work.tagFirstChar())) {
            StatusHub.post(ctx.getString(R.string.setup_tag_invalid))
            StatusHub.beep()
            return
        }
        // 源码：写 gzcs.txt（第 10 行 = form6.edit2 文本）
        GzcsStore.save(ctx, AppState.targetVab)
        // 线号/标识变化 → 重建项目登记文件
        ProjectStore.ensureProjectFile(ctx)
        // LX3.Button1Click 末段：按 ComboBox1 重算 points/points_end1/point_no/ruler_h（trunc）
        AppState.prgNo = AppState.work.prgNoInt()
        AppState.supplyTimes = AppState.work.supplyTimesInt()
        AppState.applyPrg(false)
        StatusHub.post(ctx.getString(R.string.setup_saved))
        host.showScreen(ScreenHost.KEY_MAIN)
    }

    override fun onShow() {
        writeParamsToHeader()
        setupSpinners()
        fillSerialSpinners()
        // USB增量：每次进入设置屏刷新连接方式可见性与 USB 设备列表（插拔后自动更新）
        applyConnTypeVisibility()
        fillUsbDevices()
        switchPage(0)
        StatusHub.postNow(ctx.getString(R.string.status_setup_hint))
    }

    override fun onHide() {
        // 无动作
    }

    override fun onBack(): Boolean {
        // 返回键 = 点“返回”
        saveAndReturn()
        return true
    }
}
