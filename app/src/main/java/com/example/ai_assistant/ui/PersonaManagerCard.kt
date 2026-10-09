package com.example.ai_assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ai_assistant.persona.Persona

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonaManagerCard(
    personas: List<Persona>,
    activePersona: Persona,
    onSelectPersona: (String) -> Unit,
    onCreatePersona: (name: String, description: String, prompt: String) -> Unit,
    onUpdatePersona: (id: String, name: String, description: String, prompt: String) -> Unit,
    onDeletePersona: (String) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showPromptPreview by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF141E18) else Color(0xFFF1F8F4)
        ),
        border = BorderStroke(1.dp, if (isDark) Color(0xFF2E4D38) else Color(0xFFB8DBC2)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Cabeçalho
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Personas do Sistema (JSON)",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
                        )
                    }
                    Text(
                        text = "Define a personalidade e o tom com que o modelo atende as ligações",
                        fontSize = 11.sp,
                        color = if (isDark) Color(0xFF81C784) else Color(0xFF388E3C)
                    )
                }

                IconButton(
                    onClick = onResetDefaults,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Restaurar padrões",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chips de Seleção de Persona
            Text(
                text = "Escolha a Persona Ativa:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.White else Color.Black
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                personas.forEach { persona ->
                    val isSelected = persona.id == activePersona.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPersona(persona.id) },
                        label = {
                            Text(
                                text = persona.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (isDark) Color(0xFF2E7D32) else Color(0xFF81C784),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                // Botão Adicionar Persona
                AssistChip(
                    onClick = { showCreateDialog = true },
                    label = { Text("+ Nova Persona", fontSize = 12.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (isDark) Color(0xFF1B2E21) else Color(0xFFE8F5E9)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Painel da Persona Ativa
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF0F1A12) else Color(0xFFE8F5E9)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activePersona.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Botão Editar
                            IconButton(
                                onClick = { showEditDialog = true },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Editar",
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Botão Apagar (desabilitado se houver apenas 1 persona)
                            if (personas.size > 1) {
                                IconButton(
                                    onClick = { showDeleteConfirmDialog = true },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Excluir",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (activePersona.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = activePersona.description,
                            fontSize = 12.sp,
                            color = if (isDark) Color(0xFFB0BEC5) else Color(0xFF455A64)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Botão alternar visualização do prompt
                    TextButton(
                        onClick = { showPromptPreview = !showPromptPreview },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (showPromptPreview) "▲ Ocultar System Prompt" else "▼ Ver System Prompt Ativo",
                            fontSize = 11.sp,
                            color = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                        )
                    }

                    if (showPromptPreview) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) Color(0xFF0A100B) else Color(0xFFF1F8F4)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = activePersona.systemPrompt,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDark) Color(0xFFC8E6C9) else Color(0xFF1B5E20),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Persistência em arquivo JSON: app/files/personas.json",
                fontSize = 10.sp,
                color = if (isDark) Color(0xFF78909C) else Color(0xFF546E7A)
            )
        }
    }

    // Dialog de Criação de Persona
    if (showCreateDialog) {
        PersonaFormDialog(
            title = "Criar Nova Persona",
            initialName = "",
            initialDescription = "",
            initialPrompt = "Você é o assistente pessoal atendendo uma ligação no celular do titular.\n\n" +
                    "COMPORTAMENTO:\n" +
                    "- Responda diretamente, com autenticidade e agilidade.\n" +
                    "- Se a pessoa for inconveniente, seja firme e assertivo.\n" +
                    "- Fale em 1 ou 2 frases curtas sem markdown.",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, desc, prompt ->
                onCreatePersona(name, desc, prompt)
                showCreateDialog = false
            }
        )
    }

    // Dialog de Edição de Persona
    if (showEditDialog) {
        PersonaFormDialog(
            title = "Editar Persona: ${activePersona.name}",
            initialName = activePersona.name,
            initialDescription = activePersona.description,
            initialPrompt = activePersona.systemPrompt,
            onDismiss = { showEditDialog = false },
            onConfirm = { name, desc, prompt ->
                onUpdatePersona(activePersona.id, name, desc, prompt)
                showEditDialog = false
            }
        )
    }

    // Dialog de Confirmação de Exclusão
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Excluir Persona?") },
            text = { Text("Deseja realmente apagar a persona \"${activePersona.name}\" do personas.json?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePersona(activePersona.id)
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun PersonaFormDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    initialPrompt: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, prompt: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var prompt by remember { mutableStateOf(initialPrompt) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome da Persona") },
                    placeholder = { Text("Ex: Assistente (Irônico)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrição Breve") },
                    placeholder = { Text("Ex: Humor ácido, atende o próprio celular") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("System Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 250.dp),
                    maxLines = 10
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isNotBlank() && prompt.isNotBlank()) {
                                onConfirm(name, description, prompt)
                            }
                        },
                        enabled = name.isNotBlank() && prompt.isNotBlank()
                    ) {
                        Text("Salvar no JSON")
                    }
                }
            }
        }
    }
}
