package com.algorigo.logger.util

import android.content.Context
import com.jakewharton.rxrelay3.PublishRelay
import com.jakewharton.rxrelay3.ReplayRelay
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

internal class LogUploadStream(
    context: Context,
    retentionDays: Int,
    private val sendIntervalMillis: Long,
    private val maxBatchSize: Int,
    private val maxBatchCount: Int,
) {
    sealed class Event {
        data class LogAddEvent(val log: InputLogEventExt) : Event()
        data class LogDeleteEvent(val sendIndex: Int) : Event()
    }

    sealed class DatabaseResult {
        data class LogInserted(val count: Int, val size: Int, val createdAt: String) : DatabaseResult()
        data class LogDeleted(val sendIndex: Int, val restCount: Int, val restSize: Int) : DatabaseResult()
    }

    private val eventRelay = ReplayRelay.create<Event>()
    private val outputRelay = PublishRelay.create<Pair<Int, List<InputLogEventExt>>>()
    private val logDatabase = LogDatabase(context, retentionDays)
    private var initializeDisposable: Disposable? = null

    init {
        initializeDisposable = logDatabase.countAndSize()
            .flatMapObservable { countAndSize ->
                eventRelay
                    .concatMapSingle { event ->
                        when (event) {
                            is Event.LogAddEvent -> {
                                logDatabase.insert(event.log)
                                    .flatMap {
                                        logDatabase.selectForId(it)
                                    }
                                    .map {
                                        DatabaseResult.LogInserted(1, event.log.size, it)
                                    }
                            }
                            is Event.LogDeleteEvent -> {
                                logDatabase.delete(event.sendIndex)
                                    .andThen(logDatabase.countAndSize())
                                    .map { (count, size) ->
                                        DatabaseResult.LogDeleted(event.sendIndex, count, size)
                                    }
                            }
                        }
                    }
                    .scan<Pair<List<Triple<Pair<Int, Int>, Pair<String, String>, Int>>, Triple<String, String, Int>?>>(Pair(listOf(Triple(countAndSize, Pair("", ""), 0)), null)) { acc, result ->
                        var logBatches = acc.first.toMutableList()
                        when (result) {
                            is DatabaseResult.LogInserted -> {
                                if (logBatches.isEmpty() || logBatches.last().third != 0) {
                                    logBatches.add(Triple(Pair(result.count, result.size), Pair(result.createdAt, result.createdAt), 0))
                                } else {
                                    val lastIndex = logBatches.size - 1
                                    val count = logBatches.last().first.first + result.count
                                    val size = logBatches.last().first.second + result.size
                                    if (count >= maxBatchCount || size >= maxBatchSize) {
                                        logBatches[lastIndex] = Triple(Pair(count, size), Pair(logBatches.last().second.first, result.createdAt), generateRandomInt())
                                    } else {
                                        logBatches[lastIndex] = Triple(Pair(count, size), Pair(logBatches.last().second.first, logBatches.last().second.second), 0)
                                    }
                                }

                                if ((logBatches.lastOrNull()?.third ?: 0) > 0) {
                                    Pair(
                                        logBatches,
                                        Triple(
                                            logBatches.last().second.first,
                                            logBatches.last().second.second,
                                            logBatches.last().third,
                                        )
                                    )
                                } else {
                                    Pair(
                                        logBatches,
                                        null
                                    )
                                }
                            }

                            is DatabaseResult.LogDeleted -> {
                                val index = logBatches.indexOfFirst { it.third == result.sendIndex }
                                if (index >= 0) {
                                    logBatches.removeAt(index)
                                } else {
                                    logBatches = mutableListOf(Triple(Pair(result.restCount, result.restSize), Pair("", ""), 0))
                                }
                                Pair(logBatches, null)
                            }
                        }
                    }
            }
            .filter { it.second != null }
            .map { it.second!! }
            .flatMapSingle { event ->
                logDatabase.selectBetween(event.first, event.second, event.third)
                    .map { logs ->
                        Pair(event.third, logs)
                    }
            }
            .doFinally {
                initializeDisposable = null
            }
            .subscribe({
                outputRelay.accept(it)
            }, {
            })
    }

    internal fun getOutputObservable() = outputRelay
        .timeout(sendIntervalMillis, TimeUnit.MILLISECONDS)
        .onErrorResumeNext {
            Single.fromCallable {
                generateRandomInt()
            }.flatMapObservable { random ->
                logDatabase.selectBetween("", formatter.format(Date()), random)
                    .map { Pair(random, it) }
                    .toObservable()
                    .concatWith(outputRelay.timeout(sendIntervalMillis, TimeUnit.MILLISECONDS))
            }
                .retry()
        }


    private fun generateRandomInt() = (Math.random() * Int.MAX_VALUE - 1).toInt() + 1

    fun add(log: InputLogEventExt) {
        eventRelay.accept(Event.LogAddEvent(log))
    }

    fun delete(sendIndex: Int) {
        eventRelay.accept(Event.LogDeleteEvent(sendIndex))
    }

    fun close() {
        initializeDisposable?.dispose()
        logDatabase.close()
    }

    companion object {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    }
}
