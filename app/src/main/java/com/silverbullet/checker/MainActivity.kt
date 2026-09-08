package com.silverbullet.checker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.silverbullet.checker.core.CheckerService
import com.silverbullet.checker.models.*
import com.silverbullet.checker.ui.AccountAdapter
import com.silverbullet.checker.utils.FileUtils

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerModule: Spinner
    private lateinit var customPanel: LinearLayout
    private lateinit var etCustomUrl: EditText
    private lateinit var spCustomMethod: Spinner
    private lateinit var etCustomBody: EditText
    private lateinit var etCustomContentType: EditText
    private lateinit var etCustomSuccess: EditText
    private lateinit var etCustomFail: EditText
    private lateinit var etComboFile: EditText
    private lateinit var etProxyFile: EditText
    private lateinit var etThreads: EditText
    private lateinit var etTimeout: EditText
    private lateinit var etRetries: EditText
    private lateinit var etCaptchaKey: EditText
    private lateinit var switchProxy: Switch
    private lateinit var btnLoadCombo: Button
    private lateinit var btnLoadProxy: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnExportHits: Button
    private lateinit var tvStats: TextView
    private lateinit var recyclerView: RecyclerView

    private lateinit var adapter: AccountAdapter
    private var config = CheckConfig()
    private var comboUri: Uri? = null
    private var proxyUri: Uri? = null
    private var isRunning = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val email = intent?.getStringExtra("email") ?: return
            val status = intent.getStringExtra("status") ?: return
            val details = intent.getStringExtra("details") ?: return

            val account = Account(
                email = email,
                password = "",
                status = AccountStatus.valueOf(status),
                details = details
            )

            val currentList = adapter.currentList.toMutableList()
            currentList.add(0, account)
            adapter.submitList(currentList)

            val hits = currentList.count { it.status == AccountStatus.HIT }
            val checked = currentList.size
            tvStats.text = "Checked: $checked | Hits: $hits | Last: ${AccountStatus.valueOf(status).label}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSpinner()
        setupListeners()
    }

    private fun initViews() {
        spinnerModule = findViewById(R.id.spinnerModule)
        customPanel = findViewById(R.id.customPanel)
        etCustomUrl = findViewById(R.id.etCustomUrl)
        spCustomMethod = findViewById(R.id.spCustomMethod)
        etCustomBody = findViewById(R.id.etCustomBody)
        etCustomContentType = findViewById(R.id.etCustomContentType)
        etCustomSuccess = findViewById(R.id.etCustomSuccess)
        etCustomFail = findViewById(R.id.etCustomFail)
        etComboFile = findViewById(R.id.etComboFile)
        etProxyFile = findViewById(R.id.etProxyFile)
        etThreads = findViewById(R.id.etThreads)
        etTimeout = findViewById(R.id.etTimeout)
        etRetries = findViewById(R.id.etRetries)
        etCaptchaKey = findViewById(R.id.etCaptchaKey)
        switchProxy = findViewById(R.id.switchProxy)
        btnLoadCombo = findViewById(R.id.btnLoadCombo)
        btnLoadProxy = findViewById(R.id.btnLoadProxy)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnExportHits = findViewById(R.id.btnExportHits)
        tvStats = findViewById(R.id.tvStats)
        recyclerView = findViewById(R.id.recyclerView)

        adapter = AccountAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnStop.visibility = View.GONE
        btnExportHits.visibility = View.GONE
    }

    private fun setupSpinner() {
        val modules = CheckModule.values().map { it.displayName }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modules)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModule.adapter = spinnerAdapter

        spinnerModule.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val isCustom = CheckModule.values()[position] == CheckModule.CUSTOM
                customPanel.visibility = if (isCustom) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupListeners() {
        btnLoadCombo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, COMBO_PICKER)
        }

        btnLoadProxy.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, PROXY_PICKER)
        }

        btnStart.setOnClickListener { startChecking() }
        btnStop.setOnClickListener { stopChecking() }
        btnExportHits.setOnClickListener { exportHits() }

        switchProxy.setOnCheckedChangeListener { _, isChecked ->
            etProxyFile.isEnabled = isChecked
            btnLoadProxy.isEnabled = isChecked
        }

        spCustomMethod.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("POST", "GET", "PUT", "PATCH", "DELETE")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun startChecking() {
        val moduleIndex = spinnerModule.selectedItemPosition
        val module = CheckModule.values()[moduleIndex]

        config = CheckConfig(
            threads = etThreads.text.toString().toIntOrNull() ?: 10,
            timeout = etTimeout.text.toString().toIntOrNull() ?: 10,
            useProxy = switchProxy.isChecked,
            module = module,
            customUrl = etCustomUrl.text.toString().trim(),
            customMethod = spCustomMethod.selectedItem?.toString() ?: "POST",
            customBodyTemplate = etCustomBody.text.toString().trim(),
            customContentType = etCustomContentType.text.toString().trim(),
            customSuccessMarker = etCustomSuccess.text.toString().trim(),
            customFailMarker = etCustomFail.text.toString().trim(),
            twoCaptchaKey = etCaptchaKey.text.toString().trim(),
            retries = etRetries.text.toString().toIntOrNull() ?: 1
        )

        if (module == CheckModule.CUSTOM && config.customUrl.isEmpty()) {
            Toast.makeText(this, "Custom module needs a URL", Toast.LENGTH_SHORT).show()
            return
        }

        if (module == CheckModule.CUSTOM && config.customBodyTemplate.isEmpty()) {
            Toast.makeText(this, "Custom module needs a body template", Toast.LENGTH_SHORT).show()
            return
        }

        if (comboUri == null) {
            Toast.makeText(this, "Load a combo file first", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = true
        btnStart.visibility = View.GONE
        btnStop.visibility = View.VISIBLE
        btnExportHits.visibility = View.GONE
        adapter.submitList(emptyList())

        tvStats.text = "Starting checker..."

        val comboPath = if (comboUri != null) FileUtils.cacheUri(this, comboUri!!, "combos.txt") else ""
        val proxyPath = if (switchProxy.isChecked && proxyUri != null) FileUtils.cacheUri(this, proxyUri!!, "proxies.txt") else ""

        if (comboPath.isEmpty()) {
            Toast.makeText(this, "Could not load files", Toast.LENGTH_SHORT).show()
            stopChecking()
            return
        }

        val intent = Intent(this, CheckerService::class.java).apply {
            action = CheckerService.ACTION_START
            putExtra("config", config)
            putExtra("combo_path", comboPath)
            putExtra("proxy_path", proxyPath)
        }
        startForegroundService(intent)
    }

    private fun stopChecking() {
        isRunning = false
        btnStart.visibility = View.VISIBLE
        btnStop.visibility = View.GONE
        btnExportHits.visibility = if (adapter.currentList.isNotEmpty()) View.VISIBLE else View.GONE

        val intent = Intent(this, CheckerService::class.java).apply {
            action = CheckerService.ACTION_STOP
        }
        startService(intent)
    }

    private fun exportHits() {
        val hits = adapter.currentList.filter { it.status == AccountStatus.HIT }
        if (hits.isEmpty()) {
            Toast.makeText(this, "No hits to export", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = System.currentTimeMillis()
        FileUtils.saveResultsToFile(this, hits, "hits_$timestamp.txt")
        Toast.makeText(this, "Hits exported: hits_$timestamp.txt", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                COMBO_PICKER -> {
                    comboUri = data?.data
                    etComboFile.setText("Combo file loaded")
                    etComboFile.setTextColor(getColor(R.color.hit_green))
                }
                PROXY_PICKER -> {
                    proxyUri = data?.data
                    etProxyFile.setText("Proxy file loaded")
                    etProxyFile.setTextColor(getColor(R.color.hit_green))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, IntentFilter("CHECKER_RESULT"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resultReceiver, IntentFilter("CHECKER_RESULT"))
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(resultReceiver)
    }

    companion object {
        private const val COMBO_PICKER = 1001
        private const val PROXY_PICKER = 1002
    }
}
