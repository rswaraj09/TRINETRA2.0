package com.example.assistantapp

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class SplashActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            // All permissions granted, proceed to main activity
            startMainActivity()
        } else {
            // Some permissions denied
            Toast.makeText(
                this,
                "Required permissions are needed for the app to function properly",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (permissionManager.hasAllPermissions()) {
            // All permissions already granted, proceed to main activity
            startMainActivity()
        } else {
            // Request missing permissions
            requestPermissionLauncher.launch(permissionManager.getMissingPermissions())
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
} 