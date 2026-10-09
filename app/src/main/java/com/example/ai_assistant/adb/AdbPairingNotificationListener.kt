package com.example.ai_assistant.adb

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * AdbPairingNotificationListener captura automaticamente o código de pareamento
 * de 6 dígitos exibido nas notificações do sistema Android (Depuração sem fio).
 *
 * Ao detectar o código, dispara o pareamento e a conexão automaticamente em segundo plano,
 * eliminando a necessidade de o usuário digitar manualmente qualquer número.
 */
class AdbPairingNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AICallADB"
        private val SIX_DIGIT_REGEX = Regex("\\b(\\d{6})\\b")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: ""
        // Notificações do sistema de depuração geralmente vêm de com.android.systemui ou com.android.settings
        if (pkg.contains("systemui") || pkg.contains("settings") || pkg.contains("android")) {
            val extras = sbn.notification?.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val fullContent = "$title $text $bigText"

            // Verifica se a notificação se refere à depuração sem fio ou pareamento
            val isAdbRelated = fullContent.contains("depura", ignoreCase = true) ||
                    fullContent.contains("debug", ignoreCase = true) ||
                    fullContent.contains("pareamento", ignoreCase = true) ||
                    fullContent.contains("pairing", ignoreCase = true) ||
                    fullContent.contains("código", ignoreCase = true) ||
                    fullContent.contains("code", ignoreCase = true)

            if (isAdbRelated) {
                val match = SIX_DIGIT_REGEX.find(fullContent)
                if (match != null) {
                    val code = match.value
                    Log.i(TAG, "[AICALL ADB] Código de pareamento capturado automaticamente da notificação: $code")
                    AdbManager.getInstance(applicationContext).pairWithCode(code)
                }
            }
        }
    }
}
