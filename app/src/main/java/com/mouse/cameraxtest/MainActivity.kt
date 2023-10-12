package com.mouse.cameraxtest

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toFile
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.util.concurrent.HandlerExecutor
import com.mouse.cameraxtest.ui.theme.CameraXTestTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordVideoUtil=RecordVideoUtil(this)
        setContent {
            LaunchedEffect(Unit){
                val a=recordVideoUtil.startRecord("testCamera")
                dbgPrint("a=${a.absoluteFile.path}")

            }
            LaunchedEffect(Unit){
                delay(5000)
                recordVideoUtil.pauseRecord()
                delay(5000)
                recordVideoUtil.resumeRecord()
                delay(5000)
                recordVideoUtil.stopRecord()
            }
            CameraXTestTheme {
                // A surface container using the 'background' color from the theme
                AndroidView(
                    factory = { recordVideoUtil.previewView },
                    modifier = Modifier
                        .fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CameraXTestTheme {
        Greeting("Android")
    }
}

class RecordVideoUtil(val context: Context) {

    var recording: Recording? = null
    val previewView: PreviewView = PreviewView(context)
    var videoCapture: VideoCapture<Recorder>? = null
    suspend fun startRecord(appName: String): File = suspendCancellableCoroutine { continuation ->
        (context as LifecycleOwner).lifecycleScope.launch {
            delay(1000)//停一秒再起動，不然畫面銷毀中會壞掉拿不到lifecycle
            videoCapture = context.createVideoCaptureUseCase(
                lifecycleOwner = (context as LifecycleOwner),
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
                previewView = previewView
            )
            videoCapture?.let { videoCapture ->
                val mediaDir = context.externalCacheDirs.firstOrNull()?.let {
                    File(it, appName).apply { mkdirs() }
                }


                recording = startRecordingVideo(
                    context = context,
                    filenameFormat = "yyyy-MM-dd-HH-mm-ss-SSS",
                    videoCapture = videoCapture,
                    outputDirectory = if (mediaDir != null && mediaDir.exists()) mediaDir else context.filesDir,
                    executor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) context.mainExecutor else HandlerExecutor(
                        Looper.getMainLooper()
                    ),
                ) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val uri = event.outputResults.outputUri
                        dbgPrint("RecordVideoUtil.印到這邊T.uri =$uri")
                        if (uri != Uri.EMPTY) {
                            dbgPrint("錄影最終結果result2.Uri.EMPTY=${uri}")
                            dbgPrint("RecordVideoUtil.印到這邊W")
                            continuation.resumeWith(Result.success((uri.toFile())))
                        }
                    }
                }

            }
        }

    }

    fun stopRecord() {
        dbgPrint("RecordVideoUtil.印到這邊A.stopRecord")
        recording?.stop()
    }
    fun pauseRecord(){
        recording?.pause()
    }
    fun resumeRecord(){
        recording?.resume()
    }
}
suspend fun Context.createVideoCaptureUseCase(
    lifecycleOwner: LifecycleOwner,
    cameraSelector: CameraSelector,
    previewView: PreviewView,
): VideoCapture<Recorder> {
    val preview = androidx.camera.core.Preview.Builder()
        .build()
        .apply { setSurfaceProvider(previewView.surfaceProvider) }

    val qualitySelector = QualitySelector.from(
        Quality.LOWEST,
        FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
    )
    val recorder = Recorder.Builder()
        .setExecutor(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mainExecutor else HandlerExecutor(
                Looper.getMainLooper()
            )
        )
        .setQualitySelector(qualitySelector)
        .build()

    val videoCapture = VideoCapture.withOutput(recorder)
    val cameraProvider = getCameraProvider()
    cameraProvider.unbindAll()
    dbgPrint("lifecycleOwner.lifecycle.currentState=${lifecycleOwner.lifecycle.currentState}")
    cameraProvider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        preview,
        videoCapture
    )
    return videoCapture
}

suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { future ->
        future.addListener(
            {
                continuation.resume(future.get())
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mainExecutor else HandlerExecutor(
                Looper.getMainLooper()
            )
        )
    }
}

fun startRecordingVideo(
    context: Context,
    filenameFormat: String,
    videoCapture: VideoCapture<Recorder>,
    outputDirectory: File,
    executor: Executor,
    consumer: Consumer<VideoRecordEvent>,
): Recording {
    val videoFile = File(
        outputDirectory,
        SimpleDateFormat(filenameFormat, Locale.US).format(System.currentTimeMillis()) + ".mp4"
    )
    val outputOptions = FileOutputOptions.Builder(videoFile).build()
    return videoCapture.output
        .prepareRecording(context, outputOptions)
        //.apply { if (audioEnabled) withAudioEnabled() }
        .start(executor, consumer)
}
fun dbgPrint(str:String){
    println(str)
}