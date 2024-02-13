package com.algorigo.logger.handler

import android.content.Context
import com.algorigo.logger.Level
import com.algorigo.logger.formatter.TimelessLogFormatter
import com.algorigo.logger.util.InputLogEventExt
import com.algorigo.logger.util.LogUploadStream
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.logs.AmazonCloudWatchLogsClient
import com.amazonaws.services.logs.model.CreateLogGroupRequest
import com.amazonaws.services.logs.model.CreateLogStreamRequest
import com.amazonaws.services.logs.model.DataAlreadyAcceptedException
import com.amazonaws.services.logs.model.DescribeLogGroupsRequest
import com.amazonaws.services.logs.model.DescribeLogStreamsRequest
import com.amazonaws.services.logs.model.InvalidSequenceTokenException
import com.amazonaws.services.logs.model.PutLogEventsRequest
import com.amazonaws.services.logs.model.PutRetentionPolicyRequest
import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.ReplayRelay
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class CloudWatchHandler(
    context: Context,
    logGroupNameSingle: Single<String>,
    logStreamNameSingle: Single<String>,
    awsAccessKey: String,
    awsSecretKey: String,
    awsRegion: Region,
    formatter: Formatter? = null,
    level: Level = Level.INFO,
    private val useQueue: Boolean = true,
    sendIntervalMillis: Long = 1000 * 60, // 1 minutes
    maxQueueSize: Int = 1048576, // 1 MBytes
    maxBatchCount: Int = 10000,
    private val maxMessageSize: Int = 262114, // 256 KBytes
    logGroupRetentionDays: RetentionDays = RetentionDays.month6,
    createLogGroup: Boolean = true,
    createLogStream: Boolean = true,
) : Handler() {

    enum class RetentionDays(val days: Int) {
        day1(1),
        day3(3),
        day5(5),
        week1(7),
        week2(14),
        month1(30),
        month2(60),
        month3(90),
        month4(120),
        month5(150),
        month6(180),
        year1(365),
        month13(400),
        month18(545),
        year2(731),
        year3(1096),
        year5(1827),
        year6(2192),
        year7(2557),
        year8(2922),
        year9(3288),
        year10(3653);

        companion object {
            fun fromDays(days: Int): RetentionDays {
                return values().sortedBy { it.days }.firstOrNull { it.days >= days } ?: year10
            }
        }
    }

    class LogGroupNotFoundException : RuntimeException()
    class LogStreamNotFoundException : RuntimeException()

    private val client: AmazonCloudWatchLogsClient = AmazonCloudWatchLogsClient(
        BasicAWSCredentials(awsAccessKey, awsSecretKey),
        ClientConfiguration()
//            .withMaxConnections(1000)
//            .withMaxErrorRetry(10)
//            .withConnectionTimeout(10000)
//            .withSocketTimeout(10000)
    ).apply {
        setRegion(awsRegion)
    }

    private val logUploadStream = LogUploadStream(
        context,
        logGroupRetentionDays.days,
        if (sendIntervalMillis < 10000) 10000 else sendIntervalMillis,
        maxQueueSize,
        maxBatchCount,
    )
    private val logNameRelay = BehaviorRelay.create<Pair<String, String>>()
    private var initDisposable: Disposable? = null
    private var uploadDisposable: Disposable? = null
    private val logRelay = ReplayRelay.create<InputLogEventExt>()

    val debugLogger = Logger.getLogger("algorigo_logger.cloud_watch_handler")

    init {
        setFormatter(formatter ?: TimelessLogFormatter())
        setLevel(level.level)
        initDisposable = Single.zip(logGroupNameSingle, logStreamNameSingle) { logGroupName, logStreamName ->
            Pair(logGroupName, logStreamName)
        }
            .subscribeOn(Schedulers.io())
            .flatMap {
                initCloudWatch(it.first, it.second, createLogGroup, logGroupRetentionDays, createLogStream)
                    .toSingleDefault(it)
            }
            .doFinally {
                initDisposable = null
            }
            .subscribe({
                logNameRelay.accept(it)
                flush()
            }, {})
    }

    constructor(
        context: Context,
        logGroupName: String,
        logStreamName: String,
        awsAccessKey: String,
        awsSecretKey: String,
        awsRegion: Region,
        formatter: Formatter? = null,
        level: Level = Level.INFO,
        useQueue: Boolean = true,
        sendIntervalMillis: Long = 1000 * 60, // 1 minutes
        maxQueueSize: Int = 1048576, // 1 MBytes
        maxBatchCount: Int = 10000,
        maxMessageSize: Int = 262114, // 256 KBytes
        logGroupRetentionDays: RetentionDays = RetentionDays.month6,
        createLogGroup: Boolean = true,
        createLogStream: Boolean = true,
    ) : this(
        context,
        Single.just(logGroupName),
        Single.just(logStreamName),
        awsAccessKey,
        awsSecretKey,
        awsRegion,
        formatter,
        level,
        useQueue,
        sendIntervalMillis,
        maxQueueSize,
        maxBatchCount,
        maxMessageSize,
        logGroupRetentionDays,
        createLogGroup,
        createLogStream,
    )

    constructor(
        context: Context,
        logGroupNameSingle: Single<String>,
        logStreamNameSingle: Single<String>,
        awsAccessKey: String,
        awsSecretKey: String,
        awsRegionString: String,
        formatter: Formatter? = null,
        level: Level = Level.INFO,
        useQueue: Boolean = true,
        sendIntervalMillis: Long = 1000 * 60, // 1 minutes
        maxQueueSize: Int = 1048576, // 1 MBytes
        maxBatchCount: Int = 10000,
        maxMessageSize: Int = 262114, // 256 KBytes
        logGroupRetentionDays: RetentionDays = RetentionDays.month6,
        createLogGroup: Boolean = true,
        createLogStream: Boolean = true,
    ) : this(
        context,
        logGroupNameSingle,
        logStreamNameSingle,
        awsAccessKey,
        awsSecretKey,
        Region.getRegion(awsRegionString),
        formatter,
        level,
        useQueue,
        sendIntervalMillis,
        maxQueueSize,
        maxBatchCount,
        maxMessageSize,
        logGroupRetentionDays,
        createLogGroup,
        createLogStream,
    )

    constructor(
        context: Context,
        logGroupName: String,
        logStreamName: String,
        awsAccessKey: String,
        awsSecretKey: String,
        awsRegionString: String,
        formatter: Formatter? = null,
        level: Level = Level.INFO,
        useQueue: Boolean = true,
        sendIntervalMillis: Long = 1000 * 60, // 1 minutes
        maxQueueSize: Int = 1048576, // 1 MBytes
        maxBatchCount: Int = 10000,
        maxMessageSize: Int = 262114, // 256 KBytes
        logGroupRetentionDays: RetentionDays = RetentionDays.month6,
        createLogGroup: Boolean = true,
        createLogStream: Boolean = true,
    ) : this(
        context,
        Single.just(logGroupName),
        Single.just(logStreamName),
        awsAccessKey,
        awsSecretKey,
        Region.getRegion(awsRegionString),
        formatter,
        level,
        useQueue,
        sendIntervalMillis,
        maxQueueSize,
        maxBatchCount,
        maxMessageSize,
        logGroupRetentionDays,
        createLogGroup,
        createLogStream,
    )

    private fun initCloudWatch(
        logGroupName: String,
        logStreamName: String,
        createLogGroup: Boolean,
        retentionDays: RetentionDays,
        createLogStream: Boolean
    ): Completable {
        return ensureLogGroup(logGroupName, createLogGroup, retentionDays)
            .andThen(ensureLogStream(logGroupName, logStreamName, createLogStream))
            .doOnError {
                debugLogger.warning("initCloudWatch error: ${it.message}\n${it.stackTraceToString()}")
            }
            .retryWhen { it.delay(1, TimeUnit.MINUTES) }
    }

    private fun ensureLogGroup(
        logGroupName: String,
        createLogGroup: Boolean,
        retentionDays: RetentionDays
    ) = logGroupExists(logGroupName)
        .flatMapCompletable {
            if (it) {
                Completable.complete()
            } else if (createLogGroup) {
                createLogGroup(logGroupName)
            } else {
                Completable.error(LogGroupNotFoundException())
            }
        }
        .doOnComplete {
            client.putRetentionPolicy(
                PutRetentionPolicyRequest().withLogGroupName(logGroupName)
                    .withRetentionInDays(retentionDays.days)
            )
        }

    private fun logGroupExists(logGroupName: String) = Single.fromCallable {
        val result =
            client.describeLogGroups(DescribeLogGroupsRequest().withLogGroupNamePrefix(logGroupName))
        result.logGroups.isNotEmpty()
    }
        .subscribeOn(Schedulers.io())

    private fun createLogGroup(logGroupName: String) = Completable.fromCallable {
        client.createLogGroup(CreateLogGroupRequest().withLogGroupName(logGroupName))
    }
        .subscribeOn(Schedulers.io())

    private fun ensureLogStream(
        logGroupName: String,
        logStreamName: String,
        createLogStream: Boolean
    ) = logStreamExists(logGroupName, logStreamName)
        .flatMapCompletable {
            if (it) {
                Completable.complete()
            } else if (createLogStream) {
                createLogStream(logGroupName, logStreamName)
            } else {
                Completable.error(LogStreamNotFoundException())
            }
        }

    private fun logStreamExists(logGroupName: String, logStreamName: String) = Single.fromCallable {
        val result =
            client.describeLogStreams(
                DescribeLogStreamsRequest().withLogGroupName(logGroupName)
                    .withLogStreamNamePrefix(logStreamName)
            )
        result.logStreams.isNotEmpty()
    }
        .subscribeOn(Schedulers.io())

    private fun createLogStream(logGroupName: String, logStreamName: String) = Completable.fromCallable {
        client.createLogStream(
            CreateLogStreamRequest().withLogGroupName(logGroupName).withLogStreamName(logStreamName)
        )
    }
        .subscribeOn(Schedulers.io())

    private fun submitLogs(
        logGroupName: String,
        logStreamName: String,
        logs: List<InputLogEventExt>,
        sequenceToken: String? = null,
    ): Single<Pair<Boolean, String?>> {
        if (logs.isEmpty()) {
            return Single.just(Pair(false, sequenceToken))
        }
        return Single.fromCallable {
            client.putLogEvents(
                PutLogEventsRequest()
                    .withLogGroupName(logGroupName)
                    .withLogStreamName(logStreamName)
                    .withLogEvents(logs)
                    .withSequenceToken(sequenceToken)
            )
        }
            .map {
                Pair(true, it.nextSequenceToken)
            }
            .onErrorResumeNext {
                debugLogger.warning("Failed to deliver logs error: ${it.message}\n${it.stackTraceToString()}")
                when (it) {
                    is DataAlreadyAcceptedException -> {
                        Single.just(Pair(true, it.expectedSequenceToken))
                    }

                    is InvalidSequenceTokenException -> {
                        Completable.timer(1, TimeUnit.SECONDS)
                            .andThen(submitLogs(logGroupName, logStreamName, logs, it.expectedSequenceToken))
                    }

                    else -> {
                        Single.just(Pair(false, null))
                    }
                }
            }
    }

    private fun startLogBatchUpload() {
        var nextSequenceToken: String? = null
        uploadDisposable = logNameRelay
            .flatMapCompletable { (logGroupName, logStreamName) ->
                logUploadStream.getOutputObservable()
                    .concatMapCompletable { output ->
                        submitLogs(logGroupName, logStreamName, output.second, nextSequenceToken)
                            .doOnSuccess {
                                nextSequenceToken = it.second
                                if (it.first) {
                                    logUploadStream.delete(output.first)
                                }
                            }
                            .ignoreElement()
                    }
            }
            .doFinally {
                uploadDisposable = null
            }
            .subscribe({
                debugLogger.warning("startLogBatchUpload complete")
            }, {
                debugLogger.warning("startLogBatchUpload error: ${it.message}\n${it.stackTraceToString()}")
            })
    }

    private fun startLogUpload() {
        var nextSequenceToken: String? = null
        uploadDisposable = logNameRelay
            .flatMapCompletable { (logGroupName, logStreamName) ->
                logRelay
                    .concatMapCompletable { log ->
                        submitLogs(logGroupName, logStreamName, listOf(log), nextSequenceToken)
                            .doOnSuccess {
                                nextSequenceToken = it.second
                            }
                            .ignoreElement()
                    }
            }
            .doFinally {
                uploadDisposable = null
            }
            .subscribe({
                debugLogger.warning("startLogUpload complete")
            }, {
                debugLogger.warning("startLogUpload error: ${it.message}\n${it.stackTraceToString()}")
            })
    }

    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) {
            return
        }
        flush()
        if (record?.message?.isEmpty() != false) {
            debugLogger.warning("publish record is null or empty")
            return
        }

        val inputLogEvent = InputLogEventExt(formatter.format(record), record.millis, maxMessageSize);

        if (useQueue) {
            if (uploadDisposable == null) {
                startLogBatchUpload()
            }
            logUploadStream.add(inputLogEvent)
        } else {
            if (uploadDisposable == null) {
                startLogUpload()
            }
            logRelay.accept(inputLogEvent)
        }
    }

    override fun flush() {
    }

    override fun close() {
        uploadDisposable?.dispose()
        initDisposable?.dispose()
        logUploadStream.close()
    }
}
