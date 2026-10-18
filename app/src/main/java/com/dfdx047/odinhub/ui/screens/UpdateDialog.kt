package com.dfdx047.odinhub.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dfdx047.odinhub.BuildConfig
import com.dfdx047.odinhub.tools.AppRelease
import com.dfdx047.odinhub.tools.UpdateState
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.secondaryText

/**
 * Pop-up do auto-update: "versão nova disponível" -> descarregar -> instalar (pelo PServer, sem
 * perguntas; ou pelo instalador do Android). Também mostra o resultado de uma verificação manual.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    theme: ConsoleTheme,
    isEn: Boolean,
    onInstall: (AppRelease) -> Unit,
    onSkip: (AppRelease) -> Unit,
    onDismiss: () -> Unit,
) {
    val title: String
    val body: @Composable () -> Unit
    var confirm: (@Composable () -> Unit)? = null
    var dismiss: (@Composable () -> Unit)? = null
    val canClose: Boolean

    when (state) {
        UpdateState.Idle -> return
        UpdateState.Checking -> {
            title = if (isEn) "Checking for updates…" else "Procurando atualizações…"
            body = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = theme.primary) }
            canClose = true
        }
        UpdateState.UpToDate -> {
            title = if (isEn) "You're up to date" else "Você está na versão mais recente"
            body = { DialogText("Odin Hub ${BuildConfig.VERSION_NAME}", theme) }
            confirm = { DialogButton("OK", theme, onDismiss) }
            canClose = true
        }
        is UpdateState.Available -> {
            val r = state.release
            title = (if (isEn) "Update available: " else "Atualização disponível: ") + r.tag
            body = {
                Column {
                    DialogText(
                        (if (isEn) "Installed: " else "Instalada: ") + BuildConfig.VERSION_NAME +
                            (if (r.apkSizeBytes > 0) " · APK ${"%.1f".format(java.util.Locale.US, r.apkSizeBytes / 1_048_576f)} MB" else ""),
                        theme,
                    )
                    if (r.notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        // Notas da release (markdown cru, só sem os cabeçalhos/asteriscos mais ruidosos).
                        Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            DialogText(r.notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", "").trim(), theme, size = 12)
                        }
                    }
                }
            }
            confirm = { DialogButton(if (isEn) "Update now" else "Atualizar agora", theme) { onInstall(r) } }
            dismiss = {
                Column {
                    DialogButton(if (isEn) "Later" else "Depois", theme, onDismiss, secondary = true)
                    DialogButton(if (isEn) "Skip this version" else "Ignorar esta versão", theme, { onSkip(r) }, secondary = true)
                }
            }
            canClose = true
        }
        is UpdateState.Downloading -> {
            title = (if (isEn) "Downloading " else "Baixando ") + state.release.tag
            body = {
                Column {
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = theme.primary)
                        Spacer(Modifier.height(6.dp))
                        DialogText("${(state.progress * 100).toInt()}%", theme)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = theme.primary)
                    }
                }
            }
            canClose = false
        }
        is UpdateState.Installing -> {
            title = if (isEn) "Installing…" else "Instalando…"
            body = {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = theme.primary)
                    Spacer(Modifier.height(8.dp))
                    DialogText(if (isEn) "Odin Hub will close and reopen on its own." else "O Odin Hub vai fechar e reabrir sozinho.", theme)
                }
            }
            canClose = false
        }
        is UpdateState.WaitingForInstaller -> {
            title = if (isEn) "Confirm in the installer" else "Confirme no instalador"
            body = {
                DialogText(
                    if (isEn) "The Android installer was opened. If it asks, allow Odin Hub to install apps."
                    else "O instalador do Android foi aberto. Se ele pedir, permita que o Odin Hub instale apps.",
                    theme,
                )
            }
            confirm = { DialogButton("OK", theme, onDismiss) }
            canClose = true
        }
        is UpdateState.Failed -> {
            title = if (isEn) "Update failed" else "Falha na atualização"
            body = { DialogText(state.message, theme) }
            val failedRelease = state.release
            if (failedRelease != null) {
                confirm = { DialogButton(if (isEn) "Try again" else "Tentar de novo", theme) { onInstall(failedRelease) } }
                dismiss = { DialogButton(if (isEn) "Close" else "Fechar", theme, onDismiss, secondary = true) }
            } else {
                confirm = { DialogButton("OK", theme, onDismiss) }
            }
            canClose = true
        }
    }

    AlertDialog(
        onDismissRequest = { if (canClose) onDismiss() },
        containerColor = theme.surface,
        title = { Text(title, color = theme.text, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold) },
        text = body,
        confirmButton = { confirm?.invoke() },
        dismissButton = dismiss,
    )
}

@Composable
private fun DialogText(text: String, theme: ConsoleTheme, size: Int = 13) {
    Text(text, color = theme.secondaryText, fontFamily = theme.fontFamily, fontSize = size.sp)
}

@Composable
private fun DialogButton(label: String, theme: ConsoleTheme, onClick: () -> Unit, secondary: Boolean = false) {
    TextButton(onClick = onClick) {
        Text(label, color = if (secondary) theme.secondaryText else theme.primary, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold)
    }
}
