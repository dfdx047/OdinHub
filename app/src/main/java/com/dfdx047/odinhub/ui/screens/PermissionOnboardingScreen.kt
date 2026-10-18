package com.dfdx047.odinhub.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dfdx047.odinhub.tools.PermissionChecker

@Composable
fun PermissionOnboardingWrapper(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Este ecrã aparece antes de qualquer ViewModel; lê a mesma preferência de idioma que o resto
    // da app (SharedPrefsRepo.isEnglish), que por omissão segue o idioma do sistema.
    val isEn = remember {
        context.getSharedPreferences(context.packageName + "_preferences", android.content.Context.MODE_PRIVATE)
            .getBoolean("app_language_en", java.util.Locale.getDefault().language != "pt")
    }

    // Estados locais que monitoram as permissões em tempo real
    var hasUsage by remember { mutableStateOf(PermissionChecker.hasUsageAccess(context)) }
    var hasOverlay by remember { mutableStateOf(PermissionChecker.hasOverlayAccess(context)) }

    // Ouve o ciclo de vida da Activity: reavalia as permissões assim que o usuário volta do menu do Android
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsage = PermissionChecker.hasUsageAccess(context)
                hasOverlay = PermissionChecker.hasOverlayAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val allGranted = hasUsage && hasOverlay

    if (!allGranted) {
        // Tela de Configuração Inicial mantendo a paleta escura e o estilo Glassmorphism
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F1115))
                .windowInsetsPadding(WindowInsets.systemBars)
                // Rolagem: na horizontal o cartão não cabia e os botões ficavam escondidos.
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            // Cartão com efeito translúcido (Glassmorphism)
            Box(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x1AFFFFFF)) // Fundo translúcido estilo vidro fosco
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (isEn) "Welcome to Odin Hub" else "Bem-vindo ao Odin Hub",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (isEn) "For game detection and TDP control to work in the background, please enable the essential permissions below:" else "Para que o monitoramento de jogos e o controle de TDP operem perfeitamente em segundo plano, precisamos ativar as permissões essenciais abaixo:",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Item 1: Acesso a Dados de Uso (Fundamental para o AutoTDP por app)
                    PermissionCardItem(
                        title = if (isEn) "1. Usage Access" else "1. Acesso a Dados de Uso",
                        description = if (isEn) "Needed to detect when you open a game and apply the right profile." else "Necessário para identificar quando você abre um jogo e disparar o perfil correto.",
                        isGranted = hasUsage,
                        isEn = isEn,
                        onClick = {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            context.startActivity(intent)
                        }
                    )

                    // Item 2: Sobreposição de Tela (Fundamental para OSD e painel flutuante)
                    PermissionCardItem(
                        title = if (isEn) "2. Display Over Other Apps" else "2. Sobreposição sobre Outros Apps",
                        description = if (isEn) "Needed to show the in-game side panel." else "Necessário para exibir o painel lateral dentro dos jogos.",
                        isGranted = hasOverlay,
                        isEn = isEn,
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    } else {
        // Se todas as permissões estiverem ativas, libera a interface principal que você construiu
        content()
    }
}

@Composable
fun PermissionCardItem(
    title: String,
    description: String,
    isGranted: Boolean,
    isEn: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0DFFFFFF))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = Color(0xFFB8BCC6),
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) Color(0xFF2E7D32) else Color(0xFF1976D2)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = if (isGranted) (if (isEn) "Enabled" else "Ativado") else (if (isEn) "Set up" else "Configurar"),
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}