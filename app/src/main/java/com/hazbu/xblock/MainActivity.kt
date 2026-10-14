package com.hazbu.xblock

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.work.*
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.hazbu.xblock.Constants.KEY_DOMAINS
import com.hazbu.xblock.Constants.KEY_IS_DOWNLOADING_ADGUARD
import com.hazbu.xblock.Constants.KEY_IS_DOWNLOADING_EXODUS
import com.hazbu.xblock.Constants.KEY_LAST_UPDATE_ADGUARD
import com.hazbu.xblock.Constants.KEY_LAST_UPDATE_EXODUS
import com.hazbu.xblock.Constants.KEY_PACKAGES
import com.hazbu.xblock.Constants.PREFS_NAME
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var adguardStatus: TextView
    private lateinit var exodusStatus: TextView
    private lateinit var btnAdGuard: Button
    private lateinit var btnExodus: Button
    private lateinit var btnDeleteAdGuard: Button
    private lateinit var btnDeleteExodus: Button
    private lateinit var tvTitle: TextView
    private lateinit var masterSwitch: SwitchMaterial
    private lateinit var masterStatus: TextView
    private var changingMaster = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupWindowInsets()
        setupUI()
        normalizeFilterState()
        RemoteConfig.connect(this)
        refreshUI()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = systemBars.left + (24 * resources.displayMetrics.density).toInt(),
                top = systemBars.top,
                right = systemBars.right + (24 * resources.displayMetrics.density).toInt(),
                bottom = systemBars.bottom
            )
            insets
        }
    }

    private fun setupUI() {
        adguardStatus = findViewById(R.id.adguard_status)
        exodusStatus = findViewById(R.id.exodus_status)
        masterSwitch = findViewById(R.id.master_switch)
        masterStatus = findViewById(R.id.master_status)
        btnAdGuard = findViewById(R.id.btn_update_adguard)
        btnExodus = findViewById(R.id.btn_update_exodus)
        btnDeleteAdGuard = findViewById(R.id.btn_delete_adguard)
        btnDeleteExodus = findViewById(R.id.btn_delete_exodus)
        tvTitle = findViewById(R.id.title)

        setupTitleSpannable()

        masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (!changingMaster) setMasterEnabled(checked)
        }

        btnAdGuard.setOnClickListener { triggerUpdate(FilterUpdateWorker.TYPE_ADGUARD) }
        btnExodus.setOnClickListener { triggerUpdate(FilterUpdateWorker.TYPE_EXODUS) }

        btnDeleteAdGuard.setOnClickListener {
            deleteFilter(KEY_DOMAINS, KEY_LAST_UPDATE_ADGUARD)
        }
        btnDeleteExodus.setOnClickListener {
            deleteFilter(KEY_PACKAGES, KEY_LAST_UPDATE_EXODUS)
        }

        refreshMaster()
    }

    private fun refreshMaster() {
        Thread {
            val status = RootControl.status()
            runOnUiThread {
                changingMaster = true
                masterSwitch.isChecked = status.enabled
                changingMaster = false
                masterStatus.text = if (status.rootAvailable) {
                    if (status.enabled) "ON · root hosts + modern LSPosed" else "OFF"
                } else {
                    "Root unavailable: ${status.details}"
                }
            }
        }.start()
    }

    private fun setMasterEnabled(enabled: Boolean) {
        masterSwitch.isEnabled = false
        masterStatus.text = "Applying…"
        Thread {
            val result = RootControl.setEnabled(enabled)
            val status = RootControl.status()
            runOnUiThread {
                changingMaster = true
                masterSwitch.isChecked = status.enabled
                masterSwitch.isEnabled = true
                changingMaster = false
                masterStatus.text = if (result.success && status.enabled == enabled) {
                    if (enabled) "ON · reopen apps after switching" else "OFF"
                } else {
                    "Could not change state: ${result.error.ifBlank { "root command failed" }}"
                }
            }
        }.start()
    }

    private fun setupTitleSpannable() {
        val titleText = tvTitle.text.toString()
        val spannable = SpannableStringBuilder(titleText)
        
        val primaryColor = MaterialColors.getColor(
            this, 
            androidx.appcompat.R.attr.colorPrimary,
            "#FF6200EE".toColorInt()
        )

        spannable.setSpan(
            ForegroundColorSpan(primaryColor),
            0, 1, // index of 'x'
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvTitle.text = spannable
    }

    private fun deleteFilter(vararg keys: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (keys.contains(KEY_DOMAINS)) AdBlockUtils.deleteDomainsFile(this)
        prefs.edit().apply {
            keys.forEach { remove(it) }
        }.apply()
        AdBlockUtils.fixPermissions(this)
        refreshUI()
    }

    private fun normalizeFilterState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val domainsFile = AdBlockUtils.domainsFile(this)
        val domainsReady = domainsFile.isFile && domainsFile.length() > 0
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
        val editor = prefs.edit()
        if (domainsReady) {
            // Older builds put 184k domains in SharedPreferences XML. That
            // left the download flag stuck and made subsequent reads unreliable.
            editor.remove(KEY_DOMAINS)
                .putBoolean(KEY_IS_DOWNLOADING_ADGUARD, false)
                .putLong(KEY_LAST_UPDATE_ADGUARD, domainsFile.lastModified())
        }
        if (packages.isNotEmpty()) {
            editor.putBoolean(KEY_IS_DOWNLOADING_EXODUS, false)
        }
        editor.apply()
    }

    private fun triggerUpdate(type: String) {
        val data = Data.Builder()
            .putString(FilterUpdateWorker.KEY_TYPE, type)
            .build()

        val request = OneTimeWorkRequestBuilder<FilterUpdateWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "Update_$type",
            ExistingWorkPolicy.REPLACE,
            request
        )

        val targetButton = if (type == FilterUpdateWorker.TYPE_EXODUS) btnExodus else btnAdGuard

        // Observe progress
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info?.state == WorkInfo.State.SUCCEEDED || info?.state == WorkInfo.State.FAILED) {
                refreshUI()
                targetButton.isEnabled = true
            } else {
                targetButton.isEnabled = false
            }
        }
    }

    private fun refreshUI() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

        // AdGuard
        val domainCount = AdBlockUtils.domainCount(this)
        val lastUpdateAdGuard = prefs.getLong(KEY_LAST_UPDATE_ADGUARD, 0L).takeIf { it > 0 }
            ?: AdBlockUtils.domainsFile(this).takeIf { it.isFile }?.lastModified() ?: 0L
        val isDownloadingAdGuard = prefs.getBoolean(KEY_IS_DOWNLOADING_ADGUARD, false) &&
            domainCount == 0
        if (isDownloadingAdGuard) {
            adguardStatus.text = getString(R.string.status_updating)
            btnAdGuard.isEnabled = false
        } else {
            val adguardDateStr = if (lastUpdateAdGuard > 0) sdf.format(Date(lastUpdateAdGuard)) else getString(R.string.never)
            adguardStatus.text = getString(R.string.count_label, domainCount) + "\n" + getString(R.string.last_label, adguardDateStr)
            btnAdGuard.isEnabled = true
        }

        // Exodus
        val isDownloadingExodus = prefs.getBoolean(KEY_IS_DOWNLOADING_EXODUS, false)
        if (isDownloadingExodus) {
            exodusStatus.text = getString(R.string.status_updating)
            btnExodus.isEnabled = false
        } else {
            val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
            val lastUpdateExodus = prefs.getLong(KEY_LAST_UPDATE_EXODUS, 0L)
            val exodusDateStr = if (lastUpdateExodus > 0) sdf.format(Date(lastUpdateExodus)) else getString(R.string.never)
            exodusStatus.text = getString(R.string.count_label, packages.size) + "\n" + getString(R.string.last_label, exodusDateStr)
            btnExodus.isEnabled = true
        }
    }
}
