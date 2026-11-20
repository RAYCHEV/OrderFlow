package com.thexcoder.orderflow.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** CameraX preview, offline barcode analysis, and JPEG capture. No public file is exposed. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen(
    scanning: Boolean,
    busy: Boolean,
    onBarcode: (String) -> Unit,
    onPhoto: (File) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var error by remember { mutableStateOf<String?>(null) }
    var takingPhoto by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    val currentBarcode by rememberUpdatedState(onBarcode)
    val currentPhoto by rememberUpdatedState(onPhoto)
    val currentBusy by rememberUpdatedState(busy)
    val previewView = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera permission", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(if (scanning) "Allow the camera to scan an employee barcode. Barcode recognition runs on this device." else "Allow the camera to photograph this order.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
        return
    }

    DisposableEffect(lifecycleOwner, previewView, scanning) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val scanner = if (scanning) BarcodeScanning.getClient() else null
        val nextBarcodeAt = AtomicLong(0L)
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val imageCapture = if (!scanning) ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() else null
        val analysis = if (scanning) ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() else null
        analysis?.setAnalyzer(executor) { proxy ->
            val image = proxy.image
            if (image == null || currentBusy || android.os.SystemClock.elapsedRealtime() < nextBarcodeAt.get()) {
                proxy.close()
            } else {
                try {
                    scanner!!.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener(mainExecutor) { codes ->
                            val value = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                            if (!disposed && !currentBusy && value != null) {
                                val now = android.os.SystemClock.elapsedRealtime()
                                val previous = nextBarcodeAt.get()
                                if (now >= previous && nextBarcodeAt.compareAndSet(previous, now + 2500L)) currentBarcode(value)
                            }
                        }
                        .addOnFailureListener(mainExecutor) { failure ->
                            if (!disposed) error = failure.localizedMessage ?: "Barcode could not be read. Try again."
                        }
                        .addOnCompleteListener { proxy.close() }
                } catch (failure: Exception) {
                    proxy.close()
                    mainExecutor.execute { if (!disposed) error = failure.localizedMessage ?: "Camera analysis failed." }
                }
            }
        }
        providerFuture.addListener({
            if (!disposed) {
                try {
                    val provider = providerFuture.get()
                    val useCases = listOfNotNull(preview, imageCapture, analysis).toTypedArray()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                    boundProvider = provider
                    capture = imageCapture
                } catch (failure: Exception) {
                    error = failure.localizedMessage ?: "Unable to open the camera."
                }
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            capture = null
            analysis?.clearAnalyzer()
            boundProvider?.unbind(*listOfNotNull(preview, imageCapture, analysis).toTypedArray())
            scanner?.close()
            executor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Surface(color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                Text(if (scanning) "Place the employee barcode inside the camera view" else "Photograph the order clearly", modifier = Modifier.padding(14.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
            if (!scanning) {
                Button(
                    enabled = capture != null && !takingPhoto && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (takingPhoto || currentBusy) return@Button
                        val camera = capture ?: return@Button
                        takingPhoto = true
                        error = null
                        val photo = try { File.createTempFile("orderflow-capture-", ".jpg", context.cacheDir) }
                        catch (failure: Exception) {
                            takingPhoto = false
                            error = failure.localizedMessage ?: "Cannot prepare photograph storage."
                            return@Button
                        }
                        try {
                            camera.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                            camera.takePicture(ImageCapture.OutputFileOptions.Builder(photo).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    takingPhoto = false
                                    currentPhoto(photo)
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    takingPhoto = false
                                    photo.delete()
                                    error = exception.localizedMessage ?: "The photograph was not saved."
                                }
                            })
                        } catch (failure: Exception) {
                            takingPhoto = false
                            photo.delete()
                            error = failure.localizedMessage ?: "Unable to capture the photograph."
                        }
                    }
                ) { Text(if (takingPhoto || busy) "Saving photograph…" else "Take photo") }
            }
            TextButton(onClick = onCancel, enabled = !takingPhoto && !busy) { Text("Cancel") }
        }
    }
}
