package com.example.ai_assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.VideoProfile
import android.util.Log

/**
 * Receiver para capturar cliques nos botões de ação ("Atender" / "Recusar") da Notificação.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_ANSWER = "com.example.ai_assistant.ACTION_ANSWER"
        const val ACTION_REJECT = "com.example.ai_assistant.ACTION_REJECT"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val callId = intent.getStringExtra(IncomingCallActivity.EXTRA_CALL_ID) ?: return

        val call = CallRepository.getCall(callId)
        if (call == null) {
            Log.w(TAG, "[UI CALL] Chamada não encontrada para ID $callId")
            CallNotificationManager.cancelNotification(context)
            return
        }

        when (action) {
            ACTION_ANSWER -> {
                Log.i(TAG, "[UI CALL] Answer pressed via Notification")
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                CallNotificationManager.cancelNotification(context)

                // Abre a IncomingCallActivity para a chamada em andamento
                val activityIntent = Intent(context, IncomingCallActivity::class.java).apply {
                    putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(activityIntent)
            }
            ACTION_REJECT -> {
                Log.i(TAG, "[UI CALL] Reject pressed via Notification")
                call.reject(false, null)
                CallNotificationManager.cancelNotification(context)
            }
        }
    }
}
