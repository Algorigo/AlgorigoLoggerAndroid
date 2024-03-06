package com.algorigo.logger.util

import java.text.DateFormat
import java.text.FieldPosition
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


class KeyFormat(pattern: String, private val locale: Locale = Locale.getDefault()) : DateFormat() {

    private val formats: List<DateFormat>
    private val normalStrings: List<String>
    private val regex: Regex
    private val dateOnlyFormat: SimpleDateFormat

    init {
        val split = pattern.split("@")
        val formatStrings = split.filterIndexed { index, _ -> index % 2 == 1 }
        formats = formatStrings.map { SimpleDateFormat(it, locale) }
        normalStrings = split.filterIndexed { index, _ -> index % 2 == 0 }
        regex = Regex("^" + normalStrings.joinToString("(.*)") + "$")
        dateOnlyFormat = SimpleDateFormat(formatStrings.joinToString(""), locale)
    }

    override fun format(date: Date, buffer: StringBuffer, position: FieldPosition): StringBuffer {
        for (index in formats.indices) {
            buffer.append(normalStrings[index])
            buffer.append(formats[index].format(date))
        }
        buffer.append(normalStrings.last())
        return buffer
    }

    override fun parse(str: String, position: ParsePosition): Date? {
        val matches = regex.find(str)
        if (matches == null) {
            position.errorIndex = 0
            return null
        }
        val dateBuilder = StringBuilder()
        for (match in matches.groupValues.subList(1, matches.groupValues.size)) {
            dateBuilder.append(match)
        }
        return dateOnlyFormat.parse(dateBuilder.toString())
    }

    override fun getTimeZone(): TimeZone {
        return dateOnlyFormat.timeZone
    }

    override fun setTimeZone(timeZone: TimeZone) {
        formats.forEach {
            it.timeZone = timeZone
        }
        dateOnlyFormat.timeZone = timeZone
    }
}
