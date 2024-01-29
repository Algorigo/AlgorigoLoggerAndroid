package com.algorigo.logger

import java.util.Date
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

class AlgorigoLogFormatter : Formatter() {
    override fun format(logRecord: LogRecord?): String {
        return logRecord?.let {
            val stringBuilder = StringBuilder()
            stringBuilder.append("${Date(it.millis)} [${it.level?.toAlgorigoLevelName() ?: ""}:${it.loggerName}] ${it.message}\n")
            it.thrown?.let { throwable ->
                stringBuilder.append("### ${throwable.javaClass.simpleName}: ${throwable.message}\n")
                throwable.stackTrace.forEach { stackTraceElement ->
                    stringBuilder.append("### ${stackTraceElement.className}.${stackTraceElement.methodName}(${stackTraceElement.fileName}:${stackTraceElement.lineNumber})\n")
                }
            }
            stringBuilder.toString()
        } ?: ""
    }
}

fun Level.toAlgorigoLevelName(): String {
    return when (this) {
        Level.FINE -> "DEBUG"
        Level.CONFIG -> "INFO"
        Level.INFO -> "NOTICE"
        Level.WARNING -> "WARNING"
        Level.SEVERE -> "ERROR"
        Level.OFF -> "ASSERTS"
        else -> "VERBOSE"
    }
}
