package com.dfdx047.odinhub.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dfdx047.odinhub.data.CustomProfile
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.onPrimary

/*
 * Perfis de desempenho guardados (ex: "Game Boy Advance", "Economia"). Partilhado pela aba
 * Desempenho e pelo overlay: o mesmo visual nos dois sítios, e o perfil ATIVO é inconfundível --
 * cartão preenchido na cor do tema, borda grossa e selo "✓ ATIVO"; os outros ficam escuros e
 * sem borda.
 */

/** Faixa de estado: qual perfil está aplicado (ou "ajustes manuais"). */
@Composable
fun ActiveProfileBanner(activeName: String?, theme: ConsoleTheme, isEn: Boolean, scopeLabel: String? = null) {
    val active = activeName != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) theme.primary.copy(alpha = 0.16f) else theme.text.copy(alpha = 0.06f))
            .border(1.dp, if (active) theme.primary.copy(alpha = 0.7f) else theme.text.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (active) "●" else "○", color = if (active) theme.primary else theme.text.copy(alpha = 0.4f), fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (active) (if (isEn) "Active profile: " else "Perfil ativo: ") + activeName
                else (if (isEn) "Manual settings (no saved profile)" else "Ajustes manuais (nenhum perfil guardado)"),
                color = theme.text, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            scopeLabel?.let { Text(it, color = theme.text.copy(alpha = 0.6f), fontFamily = theme.fontFamily, fontSize = 10.sp) }
        }
    }
}

/**
 * Grelha dos perfis. Toque = aplicar; toque longo = [onLongPress] (apagar / atualizar).
 * [linkedName] marca, além do ativo, o perfil ligado ao jogo (overlay).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SavedProfileGrid(
    profiles: List<CustomProfile>,
    activeName: String?,
    theme: ConsoleTheme,
    isEn: Boolean,
    onApply: (CustomProfile) -> Unit,
    onLongPress: ((CustomProfile) -> Unit)? = null,
) {
    if (profiles.isEmpty()) {
        Text(
            if (isEn) "No saved profiles yet. Adjust TDP or clocks, cores and fan, then save them below with a name (e.g. \"Game Boy Advance\")."
            else "Ainda não há perfis. Ajuste TDP ou clocks, núcleos e ventoinha e guarde-os abaixo com um nome (ex: \"Game Boy Advance\").",
            color = theme.text.copy(alpha = 0.6f), fontFamily = theme.fontFamily, fontSize = 11.sp,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        profiles.forEach { p ->
            val selected = p.name == activeName
            Column(
                modifier = Modifier
                    .widthIn(min = 132.dp, max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) theme.primary else theme.text.copy(alpha = 0.07f))
                    .border(if (selected) 2.dp else 1.dp, if (selected) theme.text.copy(alpha = 0.85f) else theme.text.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { onApply(p) }, onLongClick = onLongPress?.let { { it(p) } })
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name, color = if (selected) theme.onPrimary else theme.text, fontFamily = theme.fontFamily,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(theme.onPrimary.copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 1.dp),
                        ) { Text(if (isEn) "✓ ACTIVE" else "✓ ATIVO", color = theme.onPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Text(
                    (if (p.type == "TDP") "TDP · " else "Clocks · ") + p.summary(isEn),
                    color = if (selected) theme.onPrimary.copy(alpha = 0.85f) else theme.text.copy(alpha = 0.6f),
                    fontFamily = theme.fontFamily, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Campo + botão "Guardar ajustes atuais como perfil". */
@Composable
fun SaveProfileRow(theme: ConsoleTheme, isEn: Boolean, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(32) }, singleLine = true,
            label = { Text(if (isEn) "New profile name" else "Nome do novo perfil", fontSize = 10.sp) },
            modifier = Modifier.weight(1f).height(54.dp), textStyle = TextStyle(fontSize = 12.sp, color = theme.text),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = theme.primary, focusedLabelColor = theme.primary, cursorColor = theme.primary,
                unfocusedBorderColor = theme.text.copy(alpha = 0.25f), unfocusedLabelColor = theme.text.copy(alpha = 0.6f),
            ),
        )
        Button(
            onClick = { if (name.isNotBlank()) { onSave(name.trim()); name = "" } },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            modifier = Modifier.height(50.dp),
        ) { Text(if (isEn) "Save current" else "Guardar atuais", fontSize = 11.sp, color = theme.onPrimary) }
    }
}

/** Diálogo do toque longo: atualizar o perfil com os ajustes atuais, ou apagá-lo. */
@Composable
fun ProfileActionsDialog(
    profile: CustomProfile,
    theme: ConsoleTheme,
    isEn: Boolean,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surface,
        title = { Text(profile.name, color = theme.text, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                if (isEn) "Update it with the settings applied now, or delete it. Games linked to a deleted profile keep its last values."
                else "Atualize-o com os ajustes aplicados agora, ou apague-o. Jogos ligados a um perfil apagado ficam com os últimos valores dele.",
                color = theme.text.copy(alpha = 0.8f), fontSize = 12.sp,
            )
        },
        confirmButton = { TextButton(onClick = { onUpdate(); onDismiss() }) { Text(if (isEn) "Update with current" else "Atualizar com os atuais", color = theme.primary) } },
        dismissButton = { TextButton(onClick = { onDelete(); onDismiss() }) { Text(if (isEn) "Delete" else "Apagar", color = theme.text.copy(alpha = 0.7f)) } },
    )
}
