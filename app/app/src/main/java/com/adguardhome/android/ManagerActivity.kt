package com.adguardhome.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.adguardhome.android.ui.AdGuardManager
import com.adguardhome.android.ui.ManagerTheme

class ManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ManagerTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AdGuardManager()
                }
            }
        }
    }
}
