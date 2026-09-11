package io.heckel.ntfy.msg

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.isIgnoringBatteryOptimizations
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AnKor fork: dead-man's switch for the whole push chain.
 *
 * Messages on the [CANARY_TOPIC] of the app's primary host are never shown. Instead the app
 * pings healthchecks.io, which alarms (by e-mail, off the phone) when the pings stop. The
 * publisher is Alertmanager's Watchdog, so a ping proves Prometheus -> Alertmanager -> ntfy ->
 * FCM -> Play Services -> this app end to end. Disabled notifications hit the /fail endpoint,
 * so a silently revoked POST_NOTIFICATIONS permission alarms too.
 *
 * The ping key is a build-time secret ([BuildConfig.HC_PING_KEY]); an empty key disables the
 * feature entirely and canary messages fall through to the normal notification path.
 */
class CanaryService(private val context: Context) {
    private val appBaseUrl = context.getString(R.string.app_base_url)
    private val client = OkHttpClient.Builder()
        .callTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** True if the notification must be swallowed by the canary instead of being dispatched. */
    fun handles(subscription: Subscription, notification: Notification): Boolean {
        if (notification.event != ApiService.EVENT_MESSAGE) return false
        if (subscription.topic != CANARY_TOPIC || subscription.baseUrl != appBaseUrl) return false
        if (BuildConfig.HC_PING_KEY.isEmpty()) {
            Log.w(TAG, "Canary message ${notification.id} received but HC_PING_KEY is empty; showing it as a normal notification")
            return false
        }
        return true
    }

    /** Pings healthchecks.io (or its /fail endpoint if notifications are disabled). Never throws. */
    fun ping(notification: Notification) {
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
        val url = if (notificationsEnabled) pingUrl() else "${pingUrl()}/fail"
        val body = "app=${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})\n" +
            "notifications_enabled=$notificationsEnabled\n" +
            "battery_optimization_ignored=$batteryOptimizationIgnored\n" +
            "message_id=${notification.id}\n" +
            "message_time=${notification.timestamp}\n"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", HttpUtil.USER_AGENT)
            .post(body.toRequestBody())
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Canary ping for message ${notification.id} rejected: HTTP ${response.code} (notificationsEnabled=$notificationsEnabled)")
                    return
                }
                Log.d(TAG, "Canary ping for message ${notification.id} delivered (notificationsEnabled=$notificationsEnabled, batteryOptimizationIgnored=$batteryOptimizationIgnored)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Canary ping for message ${notification.id} failed: ${e.message}", e)
        }
    }

    private fun pingUrl(): String = "$HC_PING_BASE_URL/${BuildConfig.HC_PING_KEY}/$HC_CHECK_SLUG"

    companion object {
        const val CANARY_TOPIC = "canary"
        private const val HC_PING_BASE_URL = "https://hc-ping.com"
        private const val HC_CHECK_SLUG = "ntfy-android-canary"
        private const val PING_TIMEOUT_SECONDS = 20L
        private const val TAG = "NtfyCanary"
    }
}
