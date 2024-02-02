package com.algorigo.logger

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.algorigo.logger.handler.AlgorigoLogHandler
import com.algorigo.logger.handler.CloudWatchHandler
import com.algorigo.logger.handler.RotatingFileHandler
import com.algorigo.logger.ui.theme.AlgorigoLoggerTheme
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import kotlinx.coroutines.rx3.asFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var compositeDisposable = CompositeDisposable()
    private var algorigoLogHandler = AlgorigoLogHandler()
    private lateinit var rotatingFileHandler: RotatingFileHandler
    private lateinit var cloudWatchHandler: CloudWatchHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogManager.initTags(LogTag)

        val logDir = File(filesDir, "log")
        if (!logDir.exists() || !logDir.isDirectory) {
            logDir.mkdirs()
        }
        LogManager.getLogger(LogTag).level = Level.VERBOSE.level
        LogManager.getLogger(LogTag).addHandler(algorigoLogHandler)
        rotatingFileHandler = RotatingFileHandler(
            this,
            relativePath = "logs/log.txt",
            level = Level.VERBOSE,
            rotateAtSizeBytes = 100,
        ).also {
            algorigoLogHandler.addHandler(it)
            it.registerS3Uploader(accessKey, secretKey, region, "woon") {
                "log_file/${pathFormat.format(it.rotatedDate)}" +
                        "/algorigo_logger_android-log-${filenameFormat.format(it.rotatedDate)}.log"
            }
        }
        cloudWatchHandler = CloudWatchHandler(
            this,
            "/test/algorigo_logger_android",
            "device_id",
            accessKey,
            secretKey,
            region,
            level = Level.VERBOSE,
            logGroupRetentionDays = CloudWatchHandler.RetentionDays.day1,
        ).also {
            algorigoLogHandler.addHandler(it)
        }

        L.debug(LogTag.Test.Test2, "test debug")
        L.error(LogTag.Test3, "test error")
        Observable.interval(0, 1, TimeUnit.SECONDS)
            .subscribe({
                L.info(LogTag.Test, "test info $it")
            }, {
                Log.e(LOG_TAG, "error", it)
            }).addTo(compositeDisposable)

        setContent {
            val files = rotatingFileHandler.rotatedFileObservable
                .map {
                    rotatingFileHandler.getAllLogFiles()
                }
                .asFlow()
                .collectAsState(initial = listOf())

            val contentState = remember { mutableStateOf("") }

            BackHandler(true) {
                if (contentState.value.isNotEmpty()) {
                    contentState.value = ""
                } else {
                    finish()
                }
            }

            AlgorigoLoggerTheme {
                // A surface container using the 'background' color from the theme
                MainView(files.value, onButtonClick = {
                    rotate()
                }, onItemClicked = {
                    contentState.value = it
                })
                if (contentState.value.isNotEmpty()) {
                    ContentDialog(
                        onDismissRequest = {
                            contentState.value = ""
                        },
                        dialogContent = contentState.value,
                    )
                }
            }
        }
    }

    private fun rotate() {
        rotatingFileHandler.rotate()
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.clear()
    }

    companion object {
        private val LOG_TAG = MainActivity::class.java.simpleName
        private val pathFormat = SimpleDateFormat("yyyy/MM/dd").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val filenameFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private const val accessKey = ""
        private const val secretKey = ""
        private val region = Region.getRegion(Regions.AP_NORTHEAST_2)
    }
}

@Composable
private fun MainView(
    files: List<String>,
    onButtonClick: () -> Unit,
    onItemClicked: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = { onButtonClick() }) {
            Text(text = "Rotate")
        }
        LazyColumn(modifier = Modifier.fillMaxWidth(), content = {
            items(files.size) { index ->
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable {
                        try {
                            File(files[index])
                                .readText()
                                .let {
                                    onItemClicked(it)
                                }
                        } catch (e: Exception) {
                            onItemClicked(e.message ?: "Unknown error")
                        }
                    }) {
                    Text(text = files[index])
                }
            }
        })
    }
}

@Composable
private fun ContentDialog(
    onDismissRequest: () -> Unit,
    dialogContent: String
) {
    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()) {
                    Text(
                        modifier = Modifier
                            .wrapContentHeight()
                            .fillMaxWidth(),
                        text = dialogContent,
                    )
                }
                Button(
                    modifier = Modifier
                        .fillMaxWidth(),
                    onClick = {
                        onDismissRequest()
                    }) {
                        Text(text = "Close")
                    }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AlgorigoLoggerTheme {
        MainView(listOf("file1", "file2"), onButtonClick = {}, onItemClicked = {})
    }
}