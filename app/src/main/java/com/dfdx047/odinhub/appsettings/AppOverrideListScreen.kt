package com.dfdx047.odinhub.appsettings

import com.dfdx047.odinhub.ui.theme.onPrimary
import com.dfdx047.odinhub.ui.theme.backdropBottom
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.dfdx047.odinhub.R
import com.dfdx047.odinhub.ui.theme.AvailableThemes
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.getResolvedTheme

@Composable
fun AppOverrideListScreen(viewModel: AppOverrideListViewModel = hiltViewModel(), navigateToOverrides: (packageName: String) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val isEn by viewModel.isEnglish.collectAsState()

    // Antes este ecrã pintava tudo com cores fixas (fundo 0xFF0F1115, azul 0xFF1976D2) e era o
    // único da app a ignorar o motor de temas -- daí parecer "de outro aplicativo". Agora segue o
    // mesmo ConsoleTheme (e o modo AMOLED) que o resto da interface.
    val rawTheme = AvailableThemes.getOrElse(uiState.selectedThemeIndex) { AvailableThemes[0] }
    val theme = getResolvedTheme(rawTheme, uiState.useAmoledBlack, uiState.useGlass)

    if (uiState.showAppSelectDialog) {
        AppPickerDialog(
            apps = uiState.overrideCandidates,
            theme = theme,
            isEn = isEn,
            onAppSelected = {
                viewModel.dismissAppSelectDialog()
                navigateToOverrides(it)
            },
            onDismiss = { viewModel.dismissAppSelectDialog() }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(theme.background)) {
        // Mesmo gradiente vertical do ecrã principal, para os dois lerem como a mesma app.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(theme.background.copy(alpha = 0.4f), theme.backdropBottom)
                )
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).windowInsetsPadding(WindowInsets.systemBars),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = if (isEn) "PER-GAME RULES" else "REGRAS POR JOGO",
                    color = theme.primary,
                    fontSize = 24.sp,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = if (isEn) "Each game keeps its own profile. Whatever you set in the in-game overlay shows up here." else "Cada jogo guarda o seu próprio perfil. O que configurares no overlay em jogo aparece aqui.",
                    color = theme.text.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = theme.fontFamily,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = theme.primary.copy(alpha = 0.85f)),
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.addClicked() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(painterResource(id = R.drawable.ic_add), contentDescription = null, tint = theme.onPrimary, modifier = Modifier.size(28.dp))
                        Text(
                            if (isEn) "Add New Game" else "Adicionar Novo Jogo",
                            color = theme.onPrimary,
                            fontFamily = theme.fontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }

            if (uiState.overrideList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.surface.copy(alpha = 0.4f))
                            .border(1.dp, theme.text.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isEn) "No rules yet.\nOpen a game and change something in the overlay -- the rule is created automatically." else "Nenhuma regra ainda.\nAbre um jogo e ajusta algo no overlay — a regra é criada sozinha.",
                            color = theme.text.copy(alpha = 0.5f),
                            fontFamily = theme.fontFamily,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            items(uiState.overrideList) { app ->
                OverrideListCard(theme = theme, onClick = { navigateToOverrides(app.packageName) }) {
                    AppItem(app.packageName, app.appName, app.appIcon, 48.dp, app.subtitle, theme) {}
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * Cartão com o mesmo comportamento de foco (escala + brilho na borda) do `ConsoleCard` usado no
 * ecrã principal, para a navegação por D-Pad se sentir igual em toda a app.
 */
@Composable
private fun OverrideListCard(theme: ConsoleTheme, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "overrideCardScale",
    )

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().scale(scale)
            .border(1.dp, if (isFocused) theme.primary else theme.text.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
    ) {
        content()
    }
}

@Composable
fun AppItem(
    packageName: String,
    label: String,
    icon: Drawable,
    iconSize: Dp = 48.dp,
    subLabel: String? = "",
    theme: ConsoleTheme,
    onClick: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Image(painter = rememberDrawablePainter(drawable = icon), contentDescription = null, Modifier.size(iconSize))
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = label, color = theme.text, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (!subLabel.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subLabel, color = theme.text.copy(alpha = 0.6f), fontFamily = theme.fontFamily, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun AppPickerDialog(apps: List<AppUiModel>, theme: ConsoleTheme, isEn: Boolean, onAppSelected: (String) -> Unit, onDismiss: () -> Unit) {
    var searchText by rememberSaveable { mutableStateOf("") }
    val filteredApps = apps.filter {
        it.appName.contains(searchText, ignoreCase = true) || it.packageName.contains(searchText, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surface,
        title = { Text(if (isEn) "Select a Game" else "Selecione um Jogo", color = theme.text, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text(if (isEn) "Search..." else "Procurar...", color = theme.text.copy(alpha = 0.5f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.text,
                        unfocusedTextColor = theme.text,
                        focusedBorderColor = theme.primary,
                        cursorColor = theme.primary,
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(filteredApps) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onAppSelected(app.packageName) }.padding(vertical = 8.dp)
                        ) {
                            Image(painter = rememberDrawablePainter(drawable = app.appIcon), contentDescription = null, Modifier.size(36.dp))
                            Text(app.appName, color = theme.text.copy(alpha = 0.85f), fontFamily = theme.fontFamily, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (isEn) "Cancel" else "Cancelar", color = theme.primary, fontFamily = theme.fontFamily) }
        }
    )
}
