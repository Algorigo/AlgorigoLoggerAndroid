package com.algorigo.logger.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import java.util.Date

class LogDatabase(
    context: Context,
    private val retentionDays: Int,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, VERSION) {

    override fun onCreate(database: SQLiteDatabase?) {
        database?.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_NAME (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY," +
                    "$COLUMN_MESSAGE TEXT NOT NULL," +
                    "$COLUMN_TIMESTAMP INTEGER NOT NULL," +
                    "$COLUMN_SIZE INTEGER NOT NULL," +
                    "$COLUMN_CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "$COLUMN_SEND_INDEX INTEGER DEFAULT 0" +
                    ");"
        )
    }

    override fun onUpgrade(database: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {

    }

    override fun onOpen(database: SQLiteDatabase?) {
        super.onOpen(database)
        database?.also {
            val retentionTimestamp = Date().time - retentionDays * 24 * 60 * 60 * 1000
            deleteBefore(it, retentionTimestamp)
            updateAllSendIndexZero(it)
        }
    }

    internal fun insert(log: InputLogEventExt) =
        Single.fromCallable {
            writableDatabase.insert(
                TABLE_NAME,
                null,
                ContentValues().apply {
                    put(COLUMN_MESSAGE, log.message)
                    put(COLUMN_TIMESTAMP, log.timestamp)
                    put(COLUMN_SIZE, log.size)
                }
            )
        }

    internal fun selectForId(id: Long) =
        Single.fromCallable {
            readableDatabase.query(
                TABLE_NAME,
                arrayOf(COLUMN_CREATED_AT),
                "$COLUMN_ID = ?",
                arrayOf(id.toString()),
                null,
                null,
                null
            ).let {
                it.moveToFirst()
                it.getString(0).apply {
                    it.close()
                }
            }
        }

    internal fun selectBetween(
        from: String,
        to: String,
        sendIndex: Int
    ): Single<List<InputLogEventExt>> = Single.fromCallable {
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COLUMN_ID, COLUMN_MESSAGE, COLUMN_TIMESTAMP),
            "$COLUMN_CREATED_AT BETWEEN ? AND ? AND $COLUMN_SEND_INDEX = ?",
            arrayOf(from, to, "0"),
            null,
            null,
            "$COLUMN_TIMESTAMP ASC"
        ).let {
            val maps = mutableListOf<Map<String, Any>>()
            while (it.moveToNext()) {
                maps.add(
                    mapOf(
                        COLUMN_ID to it.getLong(0),
                        COLUMN_MESSAGE to it.getString(1),
                        COLUMN_TIMESTAMP to it.getLong(2),
                    )
                )
            }
            it.close()
            maps
        }
    }
        .flatMap {
            Completable.fromCallable {
                writableDatabase.update(
                    TABLE_NAME,
                    ContentValues().apply {
                        put(COLUMN_SEND_INDEX, sendIndex)
                    },
                    "$COLUMN_ID IN (${it.map { it[COLUMN_ID] }.joinToString(",")})",
                    null
                )
            }
                .toSingleDefault(
                    it.map {
                        InputLogEventExt(
                            it[COLUMN_MESSAGE] as String,
                            it[COLUMN_TIMESTAMP] as Long
                        )
                    }
                )
        }

    internal fun delete(sendIndex: Int) = Completable.fromCallable {
        writableDatabase.delete(
            TABLE_NAME,
            "$COLUMN_SEND_INDEX = ?",
            arrayOf(sendIndex.toString())
        )
    }

    internal fun countAndSize() = Single.fromCallable {
        readableDatabase.query(
            TABLE_NAME,
            arrayOf("COUNT(*)", "SUM($COLUMN_SIZE)"),
            null,
            null,
            null,
            null,
            null
        ).let {
            it.moveToFirst()
            Pair(it.getInt(0), it.getInt(1)).apply {
                it.close()
            }
        }
    }

    private fun deleteBefore(database: SQLiteDatabase, timestamp: Long) {
        database.delete(
            TABLE_NAME,
            "$COLUMN_TIMESTAMP < ?",
            arrayOf(timestamp.toString())
        )
    }

    private fun updateAllSendIndexZero(database: SQLiteDatabase) {
        database.update(
            TABLE_NAME,
            ContentValues().apply {
                put(COLUMN_SEND_INDEX, 0)
            },
            null,
            null
        )
    }

    companion object {
        private const val DATABASE_NAME = "android_cloudwatch_log.db"
        private val VERSION = 1
        private val TABLE_NAME = "log"
        private val COLUMN_ID = "_id"
        private val COLUMN_MESSAGE = "message"
        private val COLUMN_TIMESTAMP = "timestamp"
        private val COLUMN_SIZE = "size"
        private val COLUMN_CREATED_AT = "created_at"
        private val COLUMN_SEND_INDEX = "send_index"
    }
}