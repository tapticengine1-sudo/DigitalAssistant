package com.example.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.roundToInt

class Tools(private val ctx: Context) {

    companion object {
        private fun prop(type: String, desc: String, options: List<String>? = null): JSONObject {
            val o = JSONObject().put("type", type).put("description", desc)
            if (options != null) o.put("enum", JSONArray(options))
            return o
        }

        private fun tool(
            name: String, desc: String,
            props: Map<String, JSONObject> = emptyMap(),
            required: List<String> = emptyList()
        ): JSONObject {
            val p = JSONObject()
            props.forEach { (k, v) -> p.put(k, v) }
            val params = JSONObject().put("type", "object").put("properties", p)
                .put("required", JSONArray(required))
            return JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", name).put("description", desc).put("parameters", params)
            )
        }

        fun definitions(): JSONArray = JSONArray().apply {
            put(tool("web_search", "Поиск в интернете. Возвращает заголовки, ссылки и сниппеты.",
                mapOf("query" to prop("string", "Поисковый запрос")), listOf("query")))
            put(tool("open_url", "Скачать страницу по URL и вернуть её текст.",
                mapOf("url" to prop("string", "Полный URL")), listOf("url")))
            put(tool("call_phone", "Позвонить контакту по имени или на номер.",
                mapOf("target" to prop("string", "Имя контакта или номер телефона")), listOf("target")))
            put(tool("media_control", "Управление любым запущенным плеером (Spotify, YouTube Music и т.д.) и громкостью.",
                mapOf("action" to prop("string", "Действие",
                    listOf("play_pause", "play", "pause", "next", "previous", "stop", "volume_up", "volume_down"))),
                listOf("action")))
            put(tool("open_app", "Открыть приложение по названию (например, плеер).",
                mapOf("name" to prop("string", "Название приложения")), listOf("name")))
            put(tool("set_volume", "Установить громкость потока в процентах.",
                mapOf(
                    "stream" to prop("string", "Поток", listOf("music", "ring", "alarm", "notification")),
                    "percent" to prop("integer", "0-100")
                ), listOf("stream", "percent")))
            put(tool("set_brightness", "Установить яркость экрана в процентах.",
                mapOf("percent" to prop("integer", "0-100")), listOf("percent")))
            put(tool("set_screen_timeout", "Время до отключения экрана в секундах.",
                mapOf("seconds" to prop("integer", "Секунды, например 30 или 120")), listOf("seconds")))
            put(tool("set_flashlight", "Включить или выключить фонарик.",
                mapOf("on" to prop("boolean", "true — включить")), listOf("on")))
            put(tool("set_dnd", "Включить или выключить режим «Не беспокоить».",
                mapOf("on" to prop("boolean", "true — включить")), listOf("on")))
            put(tool("open_settings", "Открыть раздел системных настроек (Wi-Fi, Bluetooth и др. нельзя переключить напрямую в Android 10+, поэтому открывается экран настроек).",
                mapOf("section" to prop("string", "Раздел",
                    listOf("main", "wifi", "internet", "bluetooth", "airplane", "display", "sound",
                        "location", "battery", "apps", "date", "language", "accessibility"))),
                listOf("section")))
        }
    }

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun run(name: String, a: JSONObject): String = try {
        when (name) {
            "web_search" -> webSearch(a.getString("query"))
            "open_url" -> clean(httpGet(a.getString("url"))).take(6000)
            "call_phone" -> call(a.getString("target"))
            "media_control" -> media(a.getString("action"))
            "open_app" -> openApp(a.getString("name"))
            "set_volume" -> setVolume(a.getString("stream"), a.getInt("percent"))
            "set_brightness" -> setBrightness(a.getInt("percent"))
            "set_screen_timeout" -> setTimeout(a.getInt("seconds"))
            "set_flashlight" -> flashlight(a.getBoolean("on"))
            "set_dnd" -> dnd(a.getBoolean("on"))
            "open_settings" -> openSettings(a.getString("section"))
            else -> "Неизвестный инструмент: $name"
        }
    } catch (e: Exception) {
        "Ошибка: ${e.message}"
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ---------- Интернет ----------
    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun clean(html: String) = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun webSearch(q: String): String {
        val html = httpGet("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"))
        val linkRe = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snips = snipRe.findAll(html).toList()
        if (links.isEmpty()) return "Ничего не найдено."
        val sb = StringBuilder()
        for (i in 0 until minOf(5, links.size)) {
            var url = links[i].groupValues[1]
            if (url.contains("uddg=")) {
                url = URLDecoder.decode(url.substringAfter("uddg=").substringBefore("&"), "UTF-8")
            }
            val snip = snips.getOrNull(i)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            sb.append("${i + 1}. ${clean(links[i].groupValues[2])}\n$url\n$snip\n\n")
        }
        return sb.toString()
    }

    // ---------- Звонки ----------
    private fun call(target: String): String {
        val number: String
        if (target.any { it.isLetter() }) {
            if (!granted(Manifest.permission.READ_CONTACTS)) return "Нет доступа к контактам."
            number = findContact(target) ?: return "Контакт «$target» не найден."
        } else {
            number = target
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (granted(Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_CALL, uri)); "Звоню: $target ($number)"
        } else {
            launch(Intent(Intent.ACTION_DIAL, uri)); "Нет разрешения на звонки — открыл набор номера $number."
        }
    }

    private fun findContact(name: String): String? {
        val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        ctx.contentResolver.query(
            p, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    // ---------- Плеер и приложения ----------
    private fun key(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun media(action: String): String {
        when (action) {
            "play_pause" -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "play" -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
            "pause" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "stop" -> key(KeyEvent.KEYCODE_MEDIA_STOP)
            "volume_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            else -> return "Неизвестное действие."
        }
        return "Выполнено: $action"
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(q, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "Приложение «$name» не найдено."
        val i = pm.getLaunchIntentForPackage(app.activityInfo.packageName) ?: return "Не удалось открыть."
        launch(i)
        return "Открыл ${app.loadLabel(pm)}"
    }

    // ---------- Системные настройки ----------
    private fun setVolume(s: String, p: Int): String {
        val stream = when (s) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        am.setStreamVolume(stream, (max * p.coerceIn(0, 100) / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
        return "Громкость ($s) = $p%"
    }

    private fun needWrite(): String? {
        if (Settings.System.canWrite(ctx)) return null
        launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        return "Нужно разрешение на изменение системных настроек — открыл экран, включите переключатель и повторите."
    }

    private fun setBrightness(p: Int): String {
        needWrite()?.let { return it }
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (255 * p.coerceIn(0, 100) / 100.0).roundToInt())
        return "Яркость = $p%"
    }

    private fun setTimeout(sec: Int): String {
        needWrite()?.let { return it }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, sec.coerceIn(15, 1800) * 1000)
        return "Экран гаснет через $sec с"
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Фонарика нет."
        cm.setTorchMode(id, on)
        return if (on) "Фонарик включён" else "Фонарик выключен"
    }

    private fun dnd(on: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "Нужен доступ к «Не беспокоить» — открыл экран, разрешите и повторите."
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (on) "«Не беспокоить» включён" else "«Не беспокоить» выключен"
    }

    private fun openSettings(section: String): String {
        val action = when (section) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "internet" -> Settings.ACTION_WIRELESS_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        return "Открыл настройки: $section"
    }
}
