package com.algorigo.logger.datadoglogapp

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.algorigo.logger.DataDogLogDelegate
import com.algorigo.logger.L
import com.algorigo.logger.Level
import com.algorigo.logger.LogManager
import com.algorigo.logger.handler.DataDogHandler
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var compositeDisposable = CompositeDisposable()
    private lateinit var dataDogHandler: DataDogHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LogManager.addDelegate(
            DataDogLogDelegate(
                this,
                "",
                "dev",
                "log_test_android",
                verboseLevel = Level.VERBOSE,
                remoteLevel = Level.DEBUG,
            ).apply {
                addDDTag("tagName", "tagValue")
                addAttribute("attributeName", "attributeValue")
            }
        )
        LogManager.initTags(LogTag)

        LogManager.removeRootAndroidHandler()
        dataDogHandler = DataDogHandler()
        LogManager.getLogger(LogTag).level = Level.VERBOSE.level
        LogManager.getLogger(LogTag).addHandler(dataDogHandler)

        L.info(LogTag, "test info 1")
        L.info(LogTag.Test, "test info 2")
        L.debug(LogTag.Test.Test2, "test debug")
        L.info(LogTag.Test, "test info")
        L.warning(LogTag.Test3, "test warning")
        L.error(LogTag.Test3, "test error", RuntimeException("Test Error"))
        Observable.interval(0,  5, TimeUnit.SECONDS)
            .subscribe({
                L.info(LogTag.Test, "test info $it")
            }, {
                Log.e(LOG_TAG, "error", it)
            }).addTo(compositeDisposable)
    }

    companion object {
        private val LOG_TAG = MainActivity::class.java.simpleName
    }
}
