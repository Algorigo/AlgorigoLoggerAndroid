package com.algorigo.logger

import android.content.Context
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.jakewharton.rxrelay3.PublishRelay
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.AccessController
import java.security.PrivilegedAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.logging.ErrorManager
import java.util.logging.Formatter
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.StreamHandler


class RotatingFileHandler(
    private val baseFilePath: String,
    formatter: Formatter? = null,
    level: Level = Level.DEBUG,
    private var rotateAtSizeBytes: Int = 10 * 1024 * 1024, // 10 MBytes
    private val backupCount: Int = 5,
    private val rotateCheckIntervalMillis: Long = 1000 * 60 * 5, // 5 munites
    private val append: Boolean = true,
) : StreamHandler() {

    /**
     * A metered stream is a subclass of OutputStream that
     * (a) forwards all its output to a target stream
     * (b) keeps track of how many bytes have been written
     */
    private inner class MeteredStream constructor(
        val out: OutputStream, var written: Int
    ) : OutputStream() {
        @Throws(IOException::class)
        override fun write(b: Int) {
            out.write(b)
            written++
        }

        @Throws(IOException::class)
        override fun write(buff: ByteArray) {
            out.write(buff)
            written += buff.size
        }

        @Throws(IOException::class)
        override fun write(buff: ByteArray, off: Int, len: Int) {
            out.write(buff, off, len)
            written += len
        }

        @Throws(IOException::class)
        override fun flush() {
            out.flush()
        }

        @Throws(IOException::class)
        override fun close() {
            out.close()
        }
    }

    class LogFile(
        private val base: String, val rotatedDate: Date = Date(), internal val postfix: String = ""
    ) {

        internal val path = "$base${format.format(rotatedDate)}$postfix"

        fun withPostfix(postfix: String): LogFile {
            return LogFile(base, rotatedDate, postfix)
        }

        companion object {
            private val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")

            fun fromPath(path: String, base: String): LogFile? {
                return Regex("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})(.*)$").find(path)?.let {
                    val rotatedDate = format.parse(it.groupValues[1])!!
                    return LogFile(base, rotatedDate, it.groupValues[2]);
                }
            }
        }
    }

    private var meter: MeteredStream? = null
    private val files = mutableListOf<LogFile>()
    private var rotatedDate = 0L
    private val _rotatedFileRelay = PublishRelay.create<LogFile>()
    val rotatedFileObservable: Observable<LogFile>
        get() = _rotatedFileRelay.startWith(Observable.fromIterable(files))
    private var uploadDisposable: Disposable? = null

    private val debugLogger = Logger.getLogger("algorigo_logger.rotating_file_appender")

    init {
        setFormatter(formatter ?: AlgorigoLogFormatter())
        setLevel(level.level)
        openFiles()
    }

    constructor(
        context: Context,
        relativePath: String,
        formatter: Formatter? = null,
        level: Level = Level.INFO,
        rotateAtSizeBytes: Int = 10 * 1024 * 1024, // 10 MBytes
        backupCount: Int = 5,
        rotateCheckIntervalMillis: Long = 1000 * 60 * 5, // 5 munites
        append: Boolean = true,
    ) : this(
        File(context.filesDir, relativePath).let {
            if (it.parentFile?.let { !it.exists() || !it.isDirectory } == true) {
                it.parentFile!!.mkdirs()
            }
            it.absolutePath
        },
        formatter,
        level,
        rotateAtSizeBytes,
        backupCount,
        rotateCheckIntervalMillis,
        append,
    );

    fun getAllLogFiles() = files.map { it.path } + baseFilePath

    private fun openFiles() {
        require(backupCount >= 1) { "file count = $backupCount" }
        if (rotateAtSizeBytes < 0) {
            rotateAtSizeBytes = 0
        }

        File(baseFilePath).parentFile?.list()?.mapNotNull { LogFile.fromPath(it, baseFilePath) }
            ?.sortedBy { it.rotatedDate }?.let {
                files.addAll(it)
            }

        // Create the initial log file.
        if (append) {
            open(true)
        } else {
            rotate()
        }
    }

    private fun open(append: Boolean): MeteredStream {
        val outputFile = File(baseFilePath)
        val len = if (append) {
            outputFile.length().toInt()
        } else {
            0
        }
        val fout = FileOutputStream(outputFile, true)
        val bout = BufferedOutputStream(fout)
        return MeteredStream(bout, len).also {
            meter = it
            setOutputStream(it)
        }
    }

    @Synchronized
    fun rotate() {
        if (Date().time < rotatedDate) {
            return
        }
        rotatedDate = Date().time + rotateCheckIntervalMillis

        val oldLevel = level
        level = java.util.logging.Level.OFF
        super.close()

        val logFile = File(baseFilePath)
        val rotateFile = LogFile(baseFilePath)
        if (logFile.exists()) {
            logFile.renameTo(File(rotateFile.path))
            files.add(rotateFile)
        }

        synchronized(files) {
            if (files.size >= backupCount) {
                val removeList = files.subList(0, files.size - backupCount + 1)
                removeList.forEach { File(it.path).delete() }
                files.removeAll(removeList)
            }
        }

        _rotatedFileRelay.accept(rotateFile)

        try {
            open(false)
        } catch (ix: IOException) {
            // We don't want to throw an exception here, but we
            // report the exception to any registered ErrorManager.
            reportError(null, ix, ErrorManager.OPEN_FAILURE)
        }
        level = oldLevel
    }

    @Synchronized
    override fun publish(record: LogRecord) {
        if (!isLoggable(record)) {
            return
        }
        super.publish(record)
        flush()

        if (rotateAtSizeBytes > 0 && (meter?.written ?: 0) >= rotateAtSizeBytes) {
            // We performed access checks in the "init" method to make sure
            // we are only initialized from trusted code.  So we assume
            // it is OK to write the target files, even if we are
            // currently being called from untrusted code.
            // So it is safe to raise privilege here.
            AccessController.doPrivileged(PrivilegedAction<Any?> {
                rotate()
                null
            })
        }
    }

    fun setPostfix(logFile: LogFile, postfix: String): LogFile? {
        if (!File(logFile.path).exists()) {
            return null;
        }
        if (logFile.postfix == postfix) {
            return logFile;
        }

        synchronized(files) {
            val index = files.indexOf(logFile)
            return logFile.withPostfix(postfix).also {
                files[index] = it
                File(logFile.path).renameTo(File(it.path));
            }
        }
    }

    fun registerS3Uploader(
        accessKey: String,
        secretKey: String,
        region: Region,
        bucketName: String,
        keyDelegate: (LogFile) -> String
    ) {
        uploadDisposable?.dispose()
        uploadDisposable = Single.fromCallable {
            val credentials = BasicAWSCredentials(accessKey, secretKey)
            AmazonS3Client(credentials, region)
        }.subscribeOn(Schedulers.io()).flatMapCompletable { client ->
                rotatedFileObservable.filter { it.postfix.isEmpty() }.flatMapCompletable {
                        Single.fromCallable {
                            val file = File(it.path)
                            if (!file.exists()) {
                                throw FileNotFoundException()
                            }
                            val key = keyDelegate(it)
                            client.putObject(bucketName, key, file)
                        }.retryWhen { observable ->
                                observable.doOnNext {
                                        if (it is FileNotFoundException) {
                                            throw it
                                        }
                                    }.delay(1, TimeUnit.MINUTES)
                            }.ignoreElement().doOnComplete { setPostfix(it, "s3") }
                            .onErrorComplete()
                    }
            }.subscribe({}, {
                debugLogger.warning("registerS3Uploader error : $it")
            })
    }

    fun unregisterUploader() {
        uploadDisposable?.dispose()
        uploadDisposable = null
    }
}
