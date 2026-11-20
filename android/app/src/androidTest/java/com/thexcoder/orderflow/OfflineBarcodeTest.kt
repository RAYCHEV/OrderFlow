package com.thexcoder.orderflow

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the bundled ML Kit model on a real bitmap, rather than mocking barcode recognition. */
@RunWith(AndroidJUnit4::class)
class OfflineBarcodeTest {
    @Test fun bundledModelDecodesTestEmployeeBarcodeWithoutInternetPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertEquals(
            "The installed app must not require network access for barcode scanning",
            PackageManager.PERMISSION_DENIED,
            target.packageManager.checkPermission(Manifest.permission.INTERNET, target.packageName)
        )
        val bitmap = instrumentation.context.assets.open("test-user-001-qr.png").use {
            requireNotNull(BitmapFactory.decodeStream(it)) { "QR fixture is missing or invalid" }
        }
        val scanner = BarcodeScanning.getClient()
        try {
            val results = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            assertTrue("Bundled model did not recognize the employee fixture", results.any {
                it.rawValue == "TEST-USER-001" && it.format == Barcode.FORMAT_QR_CODE
            })
        } finally {
            scanner.close()
            bitmap.recycle()
        }
    }
}
