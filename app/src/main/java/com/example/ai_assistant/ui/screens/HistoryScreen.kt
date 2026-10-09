package com.example.ai_assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.history.CallHistoryItem
import com.example.ai_assistant.history.CallHistoryManager
import com.example.ai_assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    historyManager: CallHistoryManager,
    modifier: Modifier = Modifier
) {
    val calls by historyManager.calls.collectAsState()
    var selectedCall by remember { mutableStateOf<CallHistoryItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AioBackground)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Cabeçalho da Tela
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Histórico",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = AioGraphite
            )

            if (calls.isNotEmpty()) {
                IconButton(
                    onClick = { historyManager.clearAll() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Limpar histórico",
                        tint = AioTextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (calls.isEmpty()) {
            // Estado vazio real, sem dados fictícios
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(AioSelection, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = AioPineGreen,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Nenhuma ligação registrada",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AioGraphite
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "As chamadas atendidas pelo AIô aparecerão aqui.",
                        fontSize = 14.sp,
                        color = AioTextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(calls, key = { it.id }) { item ->
                    CallHistoryCard(
                        item = item,
                        onClick = { selectedCall = item }
                    )
                }
            }
        }
    }

    // Modal de detalhes da conversa
    selectedCall?.let { call ->
        CallDetailDialog(
            call = call,
            onDismiss = { selectedCall = null },
            onDelete = {
                historyManager.deleteCallRecord(call.id)
                selectedCall = null
            }
        )
    }
}

@Composable
fun CallHistoryCard(
    item: CallHistoryItem,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale("pt", "BR")) }
    val dateText = remember(item.timestamp) { dateFormat.format(Date(item.timestamp)) }
    val durationText = remember(item.durationSeconds) {
        val mins = item.durationSeconds / 60
        val secs = item.durationSeconds % 60
        if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AioSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(AioSelection, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Call,
                    contentDescription = null,
                    tint = AioPineGreen,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.callerName.isNotBlank() && item.callerName != "Sem Nome") item.callerName else item.phoneNumber,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AioGraphite
                )

                if (item.callerName.isNotBlank() && item.callerName != item.phoneNumber && item.phoneNumber.isNotBlank()) {
                    Text(
                        text = item.phoneNumber,
                        fontSize = 13.sp,
                        color = AioTextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "$dateText • Duração: $durationText",
                    fontSize = 12.sp,
                    color = AioTextSecondary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.audioFilePath.isNullOrBlank() && java.io.File(item.audioFilePath).exists()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AioSelection,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VolumeUp,
                                contentDescription = "Áudio gravado",
                                tint = AioPineGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "ÁUDIO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AioPineGreen
                            )
                        }
                    }
                }

                if (item.messages.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AioSelection
                    ) {
                        Text(
                            text = "${item.messages.size} msgs",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AioPineGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CallDetailDialog(
    call: CallHistoryItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR")) }
    val fullDate = remember(call.timestamp) { dateFormat.format(Date(call.timestamp)) }

    val audioFile = remember(call.audioFilePath) {
        call.audioFilePath?.let { java.io.File(it) }?.takeIf { it.exists() && it.length() > 44L }
    }

    var isPlayingAudio by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    DisposableEffect(call.audioFilePath) {
        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            isPlayingAudio = false
        }
    }

    AlertDialog(
        onDismissRequest = {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            onDismiss()
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (_: Exception) {}
                onDismiss()
            }) {
                Text("Fechar", color = AioPineGreen, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (_: Exception) {}
                // Também remove o arquivo de áudio se existir
                audioFile?.delete()
                onDelete()
            }) {
                Text("Excluir", color = AioError)
            }
        },
        title = {
            Column {
                Text(
                    text = if (call.callerName.isNotBlank() && call.callerName != "Sem Nome") call.callerName else call.phoneNumber,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AioGraphite
                )
                Text(
                    text = fullDate,
                    fontSize = 12.sp,
                    color = AioTextSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                // Seletor/Player de gravação de áudio da chamada
                if (audioFile != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AioSelection,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AioPineGreen.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (isPlayingAudio) {
                                        try {
                                            mediaPlayer?.pause()
                                            isPlayingAudio = false
                                        } catch (_: Exception) {}
                                    } else {
                                        try {
                                            if (mediaPlayer == null) {
                                                mediaPlayer = android.media.MediaPlayer().apply {
                                                    setDataSource(audioFile.absolutePath)
                                                    prepare()
                                                    setOnCompletionListener {
                                                        isPlayingAudio = false
                                                    }
                                                }
                                            }
                                            mediaPlayer?.start()
                                            isPlayingAudio = true
                                        } catch (e: Exception) {
                                            isPlayingAudio = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(AioPineGreen, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlayingAudio) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                    contentDescription = if (isPlayingAudio) "Pausar" else "Ouvir",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isPlayingAudio) "Reproduzindo gravação..." else "Gravação da Ligação (.wav)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AioPineGreen
                                )
                                val sizeKb = audioFile.length() / 1024
                                Text(
                                    text = "Áudio combinado (Voz Remota + AIô) • ${sizeKb} KB",
                                    fontSize = 11.sp,
                                    color = AioTextSecondary
                                )
                            }
                        }
                    }
                }

                if (call.messages.isEmpty()) {
                    Text(
                        text = "Nenhuma transcrição de texto gravada para esta chamada.",
                        fontSize = 14.sp,
                        color = AioTextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(call.messages) { msg ->
                            val isAssistant = msg.role == "assistant"
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (isAssistant) Alignment.End else Alignment.Start
                            ) {
                                Text(
                                    text = if (isAssistant) "AIô" else "Interlocutor",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAssistant) AioPineGreen else AioTextSecondary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isAssistant) AioSelection else AioSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(0.9f)
                                ) {
                                    Text(
                                        text = msg.content,
                                        fontSize = 14.sp,
                                        color = if (isAssistant) AioPineGreen else AioGraphite,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = AioSurface,
        shape = RoundedCornerShape(20.dp)
    )
}
