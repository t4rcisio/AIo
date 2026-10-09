package com.example.ai_assistant.contacts

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat

data class ContactItem(
    val id: String,
    val name: String,
    val number: String
)

object ContactsHelper {

    private const val TAG = "ContactsHelper"

    /**
     * Verifica se o aplicativo possui a permissão READ_CONTACTS concedida.
     */
    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Limpa uma string de telefone, mantendo apenas dígitos.
     */
    fun cleanDigits(number: String): String {
        return number.filter { it.isDigit() }
    }

    /**
     * Verifica se um número de telefone está salvo na agenda do aparelho.
     */
    fun isContactSaved(context: Context, rawNumber: String): Boolean {
        if (rawNumber.isBlank() || rawNumber.equals("Desconhecido", ignoreCase = true)) {
            return false
        }
        if (!hasContactsPermission(context)) {
            Log.w(TAG, "Permissão READ_CONTACTS não concedida. Não foi possível verificar se contato está salvo.")
            return false
        }

        try {
            // 1. Tentar consulta rápida via PhoneLookup
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(rawNumber)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME
            )

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                    Log.d(TAG, "Número $rawNumber encontrado na agenda via PhoneLookup: $name")
                    return true
                }
            }

            // 2. Fallback: Comparação normalizada caso o número tenha formatação específica (DDI/DDD)
            val cleanIncoming = cleanDigits(rawNumber)
            if (cleanIncoming.length < 8) return false

            val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val phoneProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )

            context.contentResolver.query(phoneUri, phoneProjection, null, null, null)?.use { cursor ->
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val savedNumber = cursor.getString(numIdx) ?: continue
                    if (isNumberMatch(rawNumber, savedNumber)) {
                        val name = cursor.getString(nameIdx)
                        Log.d(TAG, "Número $rawNumber encontrado na agenda via CommonDataKinds: $name ($savedNumber)")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar contato na agenda para $rawNumber: ${e.message}", e)
        }

        return false
    }

    /**
     * Retorna o nome do contato salvo na agenda, ou null se não for encontrado.
     */
    fun getContactName(context: Context, rawNumber: String): String? {
        if (rawNumber.isBlank() || !hasContactsPermission(context)) return null

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(rawNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao obter nome do contato para $rawNumber: ${e.message}")
        }
        return null
    }

    /**
     * Compara dois números de telefone considerando prefixos nacionais, DDD e formatação.
     */
    fun isNumberMatch(num1: String, num2: String): Boolean {
        if (PhoneNumberUtils.compare(num1, num2)) return true

        val clean1 = cleanDigits(num1)
        val clean2 = cleanDigits(num2)

        if (clean1.isEmpty() || clean2.isEmpty()) return false
        if (clean1 == clean2) return true

        // Se ambos tiverem pelo menos 8 dígitos (tamanho de número local), compara os últimos 8 dígitos
        if (clean1.length >= 8 && clean2.length >= 8) {
            val end1 = clean1.takeLast(8)
            val end2 = clean2.takeLast(8)
            if (end1 == end2) return true
        }

        return false
    }

    /**
     * Verifica se o número de quem está ligando corresponde a algum item da lista selecionada pelo usuário.
     * Cada item na lista pode estar no formato "Nome:::Número" ou apenas "Número".
     */
    fun isNumberInSelectedList(phoneNumber: String, selectedSet: Set<String>): Boolean {
        if (phoneNumber.isBlank() || selectedSet.isEmpty()) return false

        return selectedSet.any { entry ->
            val numberPart = if (entry.contains(":::")) {
                entry.substringAfter(":::")
            } else {
                entry
            }
            isNumberMatch(phoneNumber, numberPart)
        }
    }

    /**
     * Busca todos os contatos da agenda do aparelho com suporte a filtro de pesquisa.
     */
    fun getDeviceContacts(context: Context, searchQuery: String = ""): List<ContactItem> {
        if (!hasContactsPermission(context)) return emptyList()

        val contactsList = mutableListOf<ContactItem>()
        val seenNumbers = mutableSetOf<String>()

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone._ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx) ?: ""
                    val name = cursor.getString(nameIdx) ?: "Sem Nome"
                    val number = cursor.getString(numIdx) ?: ""

                    val clean = cleanDigits(number)
                    if (clean.length >= 8 && !seenNumbers.contains(clean)) {
                        seenNumbers.add(clean)

                        if (searchQuery.isBlank() ||
                            name.contains(searchQuery, ignoreCase = true) ||
                            number.contains(searchQuery)
                        ) {
                            contactsList.add(ContactItem(id = id, name = name, number = number))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao listar contatos da agenda: ${e.message}", e)
        }

        return contactsList
    }
}
