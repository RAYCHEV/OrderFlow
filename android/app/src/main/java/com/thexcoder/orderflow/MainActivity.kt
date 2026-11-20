package com.thexcoder.orderflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thexcoder.orderflow.ui.AppViewModel
import com.thexcoder.orderflow.ui.OrderFlowApp
import com.thexcoder.orderflow.ui.Screen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { viewModel.timeChanged() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Private admin activity and PIN must not appear in screenshots or the recents preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        })
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            BackHandler(enabled = state.screen != Screen.LOGIN && state.screen != Screen.ORDERS) { viewModel.back() }
            OrderFlowApp(state, viewModel)
        }
    }
    override fun onResume() { super.onResume(); viewModel.onResume() }
    override fun onStop() { viewModel.onBackground(); super.onStop() }
    override fun onDestroy() { unregisterReceiver(timeReceiver); super.onDestroy() }
}
