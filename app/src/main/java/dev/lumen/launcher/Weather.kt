package dev.lumen.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Current weather from Open-Meteo (free, no API key). The city is guessed from the IP address. */
object Weather {
    class Result(val temp: Int, val icon: String, val city: String)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Blocking; call off the main thread. Returns null when offline. */
    fun fetch(): Result? = try {
        val loc = JSONObject(get("https://ipwho.is/"))
        val lat = loc.getDouble("latitude")
        val lon = loc.getDouble("longitude")
        val city = loc.optString("city")
        val w = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day"))
            .getJSONObject("current")
        Result(
            Math.round(w.getDouble("temperature_2m")).toInt(),
            icon(w.getInt("weather_code"), w.optInt("is_day", 1) == 1),
            city,
        )
    } catch (e: Exception) {
        null
    }

    private fun icon(code: Int, day: Boolean) = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1, 2 -> if (day) "🌤" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈"
        else -> "🌡"
    }
}
