package com.thexcoder.orderflow

import android.app.Application
import com.thexcoder.orderflow.data.AppRepository

class OrderFlowApplication : Application() {
    val repository: AppRepository by lazy { AppRepository(this) }
}
