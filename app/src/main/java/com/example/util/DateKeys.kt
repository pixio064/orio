package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Universal date key formatting and manipulation helper.
 * Strictly uses Locale.US to prevent locale-dependent numerals (e.g. Bengali digits)
 * from corrupting stored database keys and query comparisons.
 */
object DateKeys {
    private const val PATTERN = "yyyy-MM-dd"

    private val formatterThreadLocal = ThreadLocal.withInitial {
        SimpleDateFormat(PATTERN, Locale.US).apply {
            isLenient = false
        }
    }

    private fun getFormatter(): SimpleDateFormat = formatterThreadLocal.get()!!

    fun today(): String = format(System.currentTimeMillis())

    fun format(timestamp: Long): String {
        return getFormatter().format(Date(timestamp))
    }

    fun format(date: Date): String {
        return getFormatter().format(date)
    }

    fun parse(dateKey: String): Date? {
        return try {
            getFormatter().parse(dateKey)
        } catch (_: Exception) {
            null
        }
    }

    fun parseToMillis(dateKey: String): Long {
        return parse(dateKey)?.time ?: 0L
    }

    fun isToday(dateKey: String): Boolean = dateKey == today()

    fun shiftDays(dateKey: String, days: Int): String {
        val cal = Calendar.getInstance(Locale.US)
        val parsed = parse(dateKey)
        if (parsed != null) {
            cal.time = parsed
        }
        cal.add(Calendar.DAY_OF_YEAR, days)
        return format(cal.time)
    }

    fun yesterday(): String = shiftDays(today(), -1)
    fun tomorrow(): String = shiftDays(today(), 1)

    fun startOfDay(dateKey: String): Long {
        val cal = Calendar.getInstance(Locale.US)
        parse(dateKey)?.let { cal.time = it }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun endOfDay(dateKey: String): Long {
        val cal = Calendar.getInstance(Locale.US)
        parse(dateKey)?.let { cal.time = it }
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}
