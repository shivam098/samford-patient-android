package com.samford.patient.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Injectable clock so sync, backoff and cache tests are deterministic. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val System = Clock { Instant.now() }
    }
}

/** All patient-facing times are shown in India Standard Time, whatever the phone's zone. */
object Ist {
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    fun date(epochMillis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", locale).withZone(zone)
            .format(Instant.ofEpochMilli(epochMillis))

    fun dateTime(epochMillis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", locale).withZone(zone)
            .format(Instant.ofEpochMilli(epochMillis)) + " IST"

    fun time(epochMillis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("h:mm a", locale).withZone(zone)
            .format(Instant.ofEpochMilli(epochMillis))

    /** Parses ISO-8601 UTC timestamps from the server; null when absent or malformed. */
    fun parseOrNull(iso: String?): Long? = try {
        iso?.let { Instant.parse(it).toEpochMilli() }
    } catch (_: Exception) {
        try {
            iso?.let { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        } catch (_: Exception) {
            null
        }
    }
}
