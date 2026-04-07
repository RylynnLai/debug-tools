package com.debugtools.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.debugtools.app.databinding.ActivityMainBinding
import com.debugtools.debugkit.DebugKit
import com.debugtools.debugkit.DebugVpnController
import com.debugtools.debugkit.MockRule
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    /** Demo-only client. Your production client should be built at the Application layer. */
    private val demoHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (DebugVpnController.prepareIntent(this) == null) {
                DebugVpnController.start(this, packageName)
                Toast.makeText(this, "VPN proxy started", Toast.LENGTH_SHORT).show()
                renderStatus()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    // Strong reference used for leak demo. Holding Activity-related objects can leak.
    private var leakyHolder: LeakyObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.refreshButton.setOnClickListener { renderStatus() }
        binding.copyConnectButton.setOnClickListener { copyConnectInfo() }
        binding.startVpnButton.setOnClickListener { startVpnProxy() }
        binding.stopVpnButton.setOnClickListener { stopVpnProxy() }
        binding.addMockButton.setOnClickListener { registerSampleMock() }
        binding.sendRequestButton.setOnClickListener { sendMockRequest() }
        binding.watchObjectButton.setOnClickListener { watchSampleObject() }
        binding.triggerGcButton.setOnClickListener { triggerGc() }
        binding.runLeakTestButton.setOnClickListener { runLeakTest() }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    // --- Status ---

    private fun renderStatus() {
        val state = DebugKit.describeState()
        binding.statusText.text = buildString {
            appendLine("running   : ${state.running}")
            appendLine("host      : ${state.host}")
            appendLine("port      : ${state.port}")
            appendLine("clients   : ${state.connectedClients}")
            appendLine("mocks     : ${state.mockRules}")
            appendLine("watching  : ${state.watchedObjects}")
            append("vpnProxy  : ${DebugVpnController.isRunning()}")
        }
        binding.connectInfoText.text = "${state.host}:${state.port}"
    }

    private fun startVpnProxy() {
        val prepareIntent = DebugVpnController.prepareIntent(this)
        if (prepareIntent == null) {
            DebugVpnController.start(this, packageName)
            Toast.makeText(this, "VPN proxy started", Toast.LENGTH_SHORT).show()
            renderStatus()
            return
        }
        vpnPermissionLauncher.launch(prepareIntent)
    }

    private fun stopVpnProxy() {
        DebugVpnController.stop(this)
        Toast.makeText(this, "VPN proxy stopped", Toast.LENGTH_SHORT).show()
        renderStatus()
    }

    // --- Connection info ---

    private fun copyConnectInfo() {
        val state = DebugKit.describeState()
        val info = "${state.host}:${state.port}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("debug_connect", info))
        Toast.makeText(this, "Copied: $info", Toast.LENGTH_SHORT).show()
    }

    // --- Mock demo ---

    private fun registerSampleMock() {
        DebugKit.mockRegistry().upsert(
            MockRule(
                method = "GET",
                path = "/api/profile",
                statusCode = 200,
                body = """{"name":"debug-user","env":"mocked"}""",
                headers = mapOf("Content-Type" to "application/json")
            )
        )
        binding.mockResultText.text = "✅ Mock registered\nGET /api/profile -> 200"
        renderStatus()
    }

    private fun sendMockRequest() {
        binding.mockResultText.text = "⏳ Sending request..."
        Thread {
            val result = try {
                val request = Request.Builder()
                    .url("http://localhost/api/profile")
                    .build()
                val response = demoHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: "(empty)"
                "HTTP ${response.code}\n$body"
            } catch (e: IOException) {
                // Localhost request may fail before a mock is registered.
                "⚠️ ${e.message}\nTip: click 'Register Mock' before sending request"
            }
            runOnUiThread { binding.mockResultText.text = result }
        }.start()
    }

    // --- Leak watch demo ---

    private fun watchSampleObject() {
        // Expected release: temp object should become not retained after GC.
        val tempObj = LeakyObject("temp-${System.currentTimeMillis()}")
        DebugKit.watch("temp-object", tempObj)

        // Expected retained: strong reference keeps this object alive.
        leakyHolder = LeakyObject("leaky-holder")
        DebugKit.watch("leaky-holder", leakyHolder!!)

        binding.leakResultText.text = "✅ Watching 2 objects\n• temp-object (should be GCed)\n• leaky-holder (strong ref, should remain retained after GC)\n\nClick 'Trigger GC' then check 'List Watches' on desktop"
        renderStatus()
    }

    private fun runLeakTest() {
        watchSampleObject()
        triggerGc()
        binding.leakResultText.append("\n\nLeak test executed. Verify retained status in desktop watcher list.")
    }

    private fun triggerGc() {
        Runtime.getRuntime().gc()
        System.gc()
        binding.leakResultText.text = "🗑 GC triggered\nCheck retained status from desktop watch list"
    }
}

/** Simple object used by the leak demo. */
private class LeakyObject(val name: String)
