@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.dfdx047.odinhub.ui.screens

import com.dfdx047.odinhub.ui.theme.secondaryText
import com.dfdx047.odinhub.ui.theme.glass
import com.dfdx047.odinhub.ui.theme.onPrimary
import com.dfdx047.odinhub.ui.theme.scrim
import com.dfdx047.odinhub.ui.theme.backdropBottom
import android.content.Intent
import android.content.res.Configuration
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.dfdx047.odinhub.R
import com.dfdx047.odinhub.models.AppInfo
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.FeatureFlags
import com.dfdx047.odinhub.main.MainUiModel
import com.dfdx047.odinhub.main.MainViewModel
import com.dfdx047.odinhub.tools.SettingsRepo
import com.dfdx047.odinhub.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transição entre as abas do ecrã principal.
 *
 * Antes: `slideInHorizontally(tween(300)) + fadeIn()` com deslocamento de uma largura inteira — o
 * conteúdo entrava a atravessar o ecrã todo, a velocidade constante (o `tween` linear por
 * omissão), o que lia como um "salto" brusco em vez de uma transição.
 *
 * Agora o deslocamento é curto (um quinto da largura) e feito com uma mola crítica — sem
 * oscilação, mas com desaceleração natural no fim —, combinado com um leve zoom e um
 * cross-fade rápido. O `SizeTransform(clip = false)` evita que o conteúdo da aba seja recortado
 * enquanto as duas coexistem, que era o que fazia o texto "piscar" a meio da troca.
 *
 * NOTA: tem de ser uma extensão de [AnimatedContentTransitionScope]. O `using` não é uma função
 * de topo — é membro dessa interface —, por isso uma função solta que o invoque não compila
 * ("Unresolved reference 'using'"). O receiver vem implícito da lambda `transitionSpec`.
 */
private fun AnimatedContentTransitionScope<Int>.tabTransition(forward: Boolean): ContentTransform {
    val offset: (Int) -> Int = { width -> (width / 5) * if (forward) 1 else -1 }
    val outOffset: (Int) -> Int = { width -> (width / 5) * if (forward) -1 else 1 }

    return (
        slideInHorizontally(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            initialOffsetX = offset,
        ) + fadeIn(animationSpec = tween(180)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(220))
        ) togetherWith (
        slideOutHorizontally(
            animationSpec = tween(180),
            targetOffsetX = outOffset,
        ) + fadeOut(animationSpec = tween(140))
        ) using SizeTransform(clip = false)
}

/**
 * Transição do vídeo de arranque para o ecrã inicial.
 *
 * Antes era um `Crossfade(tween(800))`: as duas imagens simplesmente atravessavam-se, e como o
 * vídeo acaba num fotograma escuro, o resultado prático era o ecrã ficar quase preto durante quase
 * um segundo antes de a interface aparecer — parecia que a app tinha travado.
 *
 * Agora o vídeo afasta-se (fade rápido com um ligeiro zoom para dentro) enquanto a interface entra
 * a "assentar": começa 8% maior e ligeiramente acima, e desce até ao lugar com um `FastOutSlowIn`.
 * O fade da interface arranca com um pequeno atraso para não competir com a saída do vídeo.
 */
private fun AnimatedContentTransitionScope<Boolean>.bootTransition(enteringHome: Boolean): ContentTransform {
    if (!enteringHome) {
        // Caminho inverso (voltar a ver o vídeo, via "rever intro"): simples e curto.
        return fadeIn(animationSpec = tween(300)) togetherWith
            fadeOut(animationSpec = tween(300)) using SizeTransform(clip = false)
    }

    // A entrada "dramática" da interface é agora a montagem em cascata (BootAssemble.kt); aqui
    // basta um fade curto para não competir com ela.
    return (
        fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
        ) togetherWith (
        fadeOut(animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.94f, animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing))
        ) using SizeTransform(clip = false)
}

@Composable
fun SettingsScreen(viewModel: MainViewModel = hiltViewModel(), navigateToOverrideList: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showWelcomeSetup by rememberSaveable { mutableStateOf(viewModel.isFirstRun()) }
    var showBootAnimation by rememberSaveable { mutableStateOf(false) }

    // Progresso da animação "interface a reconstruir-se" depois do vídeo de arranque (1 = parada).
    val assembleProgress = remember { Animatable(1f) }
    val assembleScope = rememberCoroutineScope()
    val assemble: () -> Float = { assembleProgress.value }

    // Snackbar discreto para falhas reais de escrita em sysfs/PServer (ver
    // MainViewModel.hardwareErrorEvents) -- antes disso, uma escrita rejeitada não tinha
    // nenhum sinal visível para o utilizador além do Logcat.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.hardwareErrorEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Idioma persistido em SharedPrefsRepo (partilhado com o overlay e o editor por app) -- antes
    // era um rememberSaveable local que se perdia ao reabrir a app e que os outros ecrãs não viam.
    val isEn by viewModel.isEnglish.collectAsState()
    val currentLanguage = if (isEn) "English (US)" else "Português (PT-BR)"

    val rawTheme = AvailableThemes.getOrElse(uiState.selectedThemeIndex) { AvailableThemes[0] }
    val finalTheme = getResolvedTheme(rawTheme, uiState.useAmoledBlack, uiState.useGlass)

    // Fundo: arranca do que ficou gravado (antes voltava sempre ao padrão ao reabrir a app).
    val savedWallpaper = remember { viewModel.wallpaperSettings() }
    var liveWallpaperType by rememberSaveable { mutableStateOf(savedWallpaper.type) }
    var blurEnabled by rememberSaveable { mutableStateOf(savedWallpaper.blur) }
    var blurIntensity by rememberSaveable { mutableFloatStateOf(savedWallpaper.blurIntensity) }
    var wallpaperOpacity by rememberSaveable { mutableFloatStateOf(savedWallpaper.opacity) }

    var selectedStaticRes by rememberSaveable { mutableIntStateOf(if (savedWallpaper.staticIndex == 2) R.drawable.static_wallpaper_2 else R.drawable.static_wallpaper_1) }
    var selectedCustomUriString by rememberSaveable { mutableStateOf(savedWallpaper.customUri) }
    val selectedCustomUri = selectedCustomUriString?.let { Uri.parse(it) }
    var selectedWallpaperName by rememberSaveable { mutableStateOf(savedWallpaper.name ?: (if (isEn) "Preset 1" else "Predefinição 1")) }

    // Grava as alterações (com uma pequena espera: arrastar um slider não escreve a cada frame).
    LaunchedEffect(liveWallpaperType, blurEnabled, blurIntensity, wallpaperOpacity, selectedStaticRes, selectedCustomUriString, selectedWallpaperName) {
        kotlinx.coroutines.delay(400)
        viewModel.saveWallpaperSettings(
            MainViewModel.WallpaperSettings(
                type = liveWallpaperType,
                blur = blurEnabled,
                blurIntensity = blurIntensity,
                opacity = wallpaperOpacity,
                staticIndex = if (selectedStaticRes == R.drawable.static_wallpaper_2) 2 else 1,
                customUri = selectedCustomUriString,
                name = selectedWallpaperName,
            ),
        )
    }

    // Delega no SoundManager (respeita o volume/on-off configurados na aba Sistema) em vez de
    // criar um MediaPlayer novo com volume fixo (0.8f) a cada toque, como acontecia antes.
    fun playSfx(@Suppress("UNUSED_PARAMETER") resId: Int) = viewModel.playClickSound()

    // Este aviso era calculado no MainViewModel (`showPServerNotAvailableDialog`) mas nunca
    // chegava a ser desenhado por ninguém. Sem canal root, TODOS os controlos de hardware da app
    // são inócuos -- o utilizador mexia em tudo e nada acontecia, sem qualquer explicação. Agora
    // é dito à cara.
    // Auto-update (pop-up de versão nova / progresso do download e da instalação).
    val updateState by viewModel.updateState.collectAsState()
    // Ao voltar à app (ex: tocando na notificação de versão nova), volta a verificar/mostrar.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.onAppResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    UpdateDialog(
        state = updateState,
        theme = finalTheme,
        isEn = isEn,
        onInstall = { viewModel.installUpdate(it) },
        onSkip = { viewModel.skipUpdate(it) },
        onDismiss = { viewModel.dismissUpdate() },
    )

    if (uiState.showPServerNotAvailableDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.pServerDialogDismissed() },
            containerColor = finalTheme.surface,
            title = {
                Text(
                    if (isEn) "No hardware access" else "Sem acesso ao hardware",
                    color = finalTheme.text,
                    fontFamily = finalTheme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    if (isEn) {
                        "Odin Hub could not reach the PServerBinder helper (stock AYN/Retroid firmware), so performance, " +
                            "fan and display controls will not affect the device. Everything else still works."
                    } else {
                        "O Odin Hub não conseguiu alcançar o PServerBinder (firmware AYN/Retroid de fábrica), por isso os controlos " +
                            "de desempenho, ventoinha e ecrã não vão afetar o aparelho. O resto continua a funcionar."
                    },
                    color = finalTheme.text.copy(alpha = 0.8f),
                    fontFamily = finalTheme.fontFamily,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.pServerDialogDismissed() }) {
                    Text("OK", color = finalTheme.primary, fontFamily = finalTheme.fontFamily)
                }
            },
        )
    }

    if (showWelcomeSetup) {
        OdinHubWelcomeScreen(
            theme = finalTheme,
            isEn = isEn,
            currentThemeIndex = uiState.selectedThemeIndex,
            amoledBlack = uiState.useAmoledBlack,
            onThemeChange = { viewModel.updateThemeIndex(it) },
            onAmoledToggle = { viewModel.updateAmoledBlack(it) },
            onLanguageChange = { viewModel.setLanguage(it == "English (US)") },
            onFinish = {
                viewModel.finishWelcomeSetup()
                showWelcomeSetup = false
                showBootAnimation = true
            }
        )
        return
    }

    AnimatedContent(
        targetState = showBootAnimation,
        transitionSpec = { bootTransition(enteringHome = !targetState) },
        label = "BootTransition",
    ) { isBooting ->
        if (isBooting) {
            VideoBootScreen(theme = finalTheme, onVideoEnded = {
                assembleScope.launch {
                    assembleProgress.snapTo(0f)
                    showBootAnimation = false
                    assembleProgress.animateTo(1f, tween(durationMillis = ASSEMBLE_DURATION_MS, easing = LinearEasing))
                }
                playSfx(R.raw.sfx_select)
            })
        } else {
            Box(modifier = Modifier.fillMaxSize().background(finalTheme.background)) {
                Box(modifier = Modifier.fillMaxSize().alpha(wallpaperOpacity)) {
                    if (liveWallpaperType == "Live (MP4)") {
                        val activeVideoUri = if (selectedCustomUri != null && selectedCustomUri.toString().startsWith("content://")) selectedCustomUri else Uri.parse("android.resource://${context.packageName}/${R.raw.live_wallpaper}")
                        LiveWallpaperRenderer(uri = activeVideoUri)
                        if (blurEnabled) Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.2f + (blurIntensity * 0.5f)), Color.Black.copy(alpha = 0.4f + (blurIntensity * 0.5f))))))
                    } else {
                        StaticWallpaperRenderer(resId = if (selectedCustomUri == null) selectedStaticRes else null, uri = if (selectedCustomUri != null) selectedCustomUri else null, blurModifier = if (blurEnabled) Modifier.blur((blurIntensity * 40).dp) else Modifier)
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(finalTheme.background.copy(alpha = 0.4f), finalTheme.backdropBottom))))

                if (isLandscape) {
                    Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        Column(
                            modifier = Modifier.width(110.dp).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("ODIN\nHUB", fontSize = 20.sp, fontFamily = finalTheme.fontFamily, fontWeight = FontWeight.Black, color = finalTheme.primary, letterSpacing = 1.sp, textAlign = TextAlign.Center, modifier = Modifier.assembleStage(assemble, 0.10f, 0.40f, fromY = (-18).dp).padding(bottom = 16.dp))
                            Box(modifier = Modifier.assembleStage(assemble, 0.22f, 0.58f, fromX = (-40).dp), contentAlignment = Alignment.Center) {
                                ConsoleMenuBar(selectedTab = selectedTab, theme = finalTheme, isEn = isEn, isLandscape = true) {
                                    if (selectedTab != it) { playSfx(R.raw.sfx_nav); selectedTab = it; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                }
                            }
                        }

                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = { tabTransition(targetState > initialState) },
                            modifier = Modifier.weight(1f).fillMaxHeight().assembleStage(assemble, 0.40f, 0.95f, fromY = 36.dp).padding(end = 24.dp, top = 24.dp, bottom = 16.dp),
                            label = "tab_anim"
                        ) { targetTab ->
                            when (targetTab) {
                                0 -> PerformancePanel(uiState, viewModel, finalTheme, isEn, navigateToOverrideList) { playSfx(R.raw.sfx_select) }
                                1 -> DisplayPanel(uiState, viewModel, finalTheme, isEn) { playSfx(R.raw.sfx_select) }
                                2 -> ControlsPanel(uiState, viewModel, finalTheme, isEn) { playSfx(R.raw.sfx_select) }
                                3 -> SystemPanel(uiState, viewModel, finalTheme, isEn, amoledBlack = uiState.useAmoledBlack, liveWallpaperType = liveWallpaperType, blurEnabled = blurEnabled, blurIntensity = blurIntensity, wallpaperOpacity = wallpaperOpacity, selectedWallpaperName = selectedWallpaperName, currentThemeIndex = uiState.selectedThemeIndex, currentLanguage = currentLanguage, onThemeChange = { viewModel.updateThemeIndex(it) }, onLanguageChange = { viewModel.setLanguage(it == "English (US)") }, onAmoledToggle = { viewModel.updateAmoledBlack(it) }, onLiveWallpaperTypeChange = { liveWallpaperType = it }, onBlurToggle = { blurEnabled = it }, onBlurIntensityChange = { blurIntensity = it }, onWallpaperOpacityChange = { wallpaperOpacity = it }, onPresetStaticSelected = { res, name -> selectedCustomUriString = null; selectedStaticRes = res; selectedWallpaperName = name }, onPresetVideoSelected = { selectedCustomUriString = null; selectedWallpaperName = if (isEn) "Default Live Wallpaper" else "Live Wallpaper Padrão" }, onCustomUriSelected = { uri, name -> selectedCustomUriString = uri.toString(); selectedWallpaperName = name }, onReplayBoot = { showWelcomeSetup = true }, bgmEnabled = uiState.bgmEnabled, bgmVolume = uiState.bgmVolume, sfxEnabled = uiState.sfxEnabled, sfxVolume = uiState.sfxVolume, onBgmToggle = { viewModel.updateBgmEnabled(it) }, onBgmVolume = { viewModel.updateBgmVolume(it) }, onSfxToggle = { viewModel.updateSfxEnabled(it) }, onSfxVolume = { viewModel.updateSfxVolume(it) }) { playSfx(R.raw.sfx_select) }
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        Text("ODIN HUB", fontSize = 28.sp, fontFamily = finalTheme.fontFamily, fontWeight = FontWeight.Black, color = finalTheme.primary, letterSpacing = 2.sp, modifier = Modifier.assembleStage(assemble, 0.10f, 0.40f, fromY = (-18).dp).padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
                        Box(modifier = Modifier.assembleStage(assemble, 0.22f, 0.58f, fromY = (-24).dp)) {
                            ConsoleMenuBar(selectedTab = selectedTab, theme = finalTheme, isEn = isEn, isLandscape = false) {
                                if (selectedTab != it) { playSfx(R.raw.sfx_nav); selectedTab = it; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        }

                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = { tabTransition(targetState > initialState) },
                            modifier = Modifier.fillMaxSize().assembleStage(assemble, 0.40f, 0.95f, fromY = 36.dp).padding(horizontal = 24.dp).padding(bottom = 16.dp),
                            label = "tab_anim"
                        ) { targetTab ->
                            when (targetTab) {
                                0 -> PerformancePanel(uiState, viewModel, finalTheme, isEn, navigateToOverrideList) { playSfx(R.raw.sfx_select) }
                                1 -> DisplayPanel(uiState, viewModel, finalTheme, isEn) { playSfx(R.raw.sfx_select) }
                                2 -> ControlsPanel(uiState, viewModel, finalTheme, isEn) { playSfx(R.raw.sfx_select) }
                                3 -> SystemPanel(uiState, viewModel, finalTheme, isEn, amoledBlack = uiState.useAmoledBlack, liveWallpaperType = liveWallpaperType, blurEnabled = blurEnabled, blurIntensity = blurIntensity, wallpaperOpacity = wallpaperOpacity, selectedWallpaperName = selectedWallpaperName, currentThemeIndex = uiState.selectedThemeIndex, currentLanguage = currentLanguage, onThemeChange = { viewModel.updateThemeIndex(it) }, onLanguageChange = { viewModel.setLanguage(it == "English (US)") }, onAmoledToggle = { viewModel.updateAmoledBlack(it) }, onLiveWallpaperTypeChange = { liveWallpaperType = it }, onBlurToggle = { blurEnabled = it }, onBlurIntensityChange = { blurIntensity = it }, onWallpaperOpacityChange = { wallpaperOpacity = it }, onPresetStaticSelected = { res, name -> selectedCustomUriString = null; selectedStaticRes = res; selectedWallpaperName = name }, onPresetVideoSelected = { selectedCustomUriString = null; selectedWallpaperName = if (isEn) "Default Live Wallpaper" else "Live Wallpaper Padrão" }, onCustomUriSelected = { uri, name -> selectedCustomUriString = uri.toString(); selectedWallpaperName = name }, onReplayBoot = { showWelcomeSetup = true }, bgmEnabled = uiState.bgmEnabled, bgmVolume = uiState.bgmVolume, sfxEnabled = uiState.sfxEnabled, sfxVolume = uiState.sfxVolume, onBgmToggle = { viewModel.updateBgmEnabled(it) }, onBgmVolume = { viewModel.updateBgmVolume(it) }, onSfxToggle = { viewModel.updateSfxEnabled(it) }, onSfxVolume = { viewModel.updateSfxVolume(it) }) { playSfx(R.raw.sfx_select) }
                            }
                        }
                    }
                }

                AssembleScanOverlay(progress = assemble, theme = finalTheme)

                SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars).padding(bottom = 8.dp))
            }
        }
    }
}

/**
 * Pré-visualização do puxador lateral do overlay.
 *
 * Existe porque os sliders de opacidade/espessura configuram um elemento que só é visível dentro
 * de um jogo (ver ForegroundAppTracker) — sem isto, o utilizador estaria a afinar um controlo que
 * não consegue ver. Reproduz a mesma geometria do puxador real desenhado em
 * `QuickAccessContent`: cantos arredondados só do lado esquerdo, cor primária do tema com a
 * opacidade escolhida, e o risco branco central.
 */
@Composable
private fun OverlayHandlePreview(
    theme: ConsoleTheme,
    handleOpacity: Float,
    handleWidthDp: Int,
    isEn: Boolean,
) {
    Column {
        Text(
            if (isEn) "Preview (as it appears over a game)" else "Pré-visualização (como aparece sobre o jogo)",
            color = theme.text.copy(alpha = 0.5f),
            fontFamily = theme.fontFamily,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .border(1.dp, theme.text.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(handleWidthDp.dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(theme.primary.copy(alpha = handleOpacity.coerceIn(0.1f, 1f)))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.15f),
                        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(30.dp)
                        .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(50)),
                )
            }
        }
    }
}

@Composable
fun OdinHubWelcomeScreen(theme: ConsoleTheme, isEn: Boolean, currentThemeIndex: Int, amoledBlack: Boolean, onThemeChange: (Int) -> Unit, onAmoledToggle: (Boolean) -> Unit, onLanguageChange: (String) -> Unit, onFinish: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var expandedTheme by remember { mutableStateOf(false) }
    var expandedLang by remember { mutableStateOf(false) }

    // Com rolagem: na horizontal o ecrã é baixo e o botão INICIAR ficava fora do ecrã.
    Box(modifier = Modifier.fillMaxSize().background(theme.background).windowInsetsPadding(WindowInsets.systemBars), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (isEn) "WELCOME TO ODIN HUB" else "BEM-VINDO AO ODIN HUB", fontSize = 24.sp, fontFamily = theme.fontFamily, fontWeight = FontWeight.Black, color = theme.primary)

            ConsoleCard(if (isEn) "Language" else "Idioma", if (isEn) "English (US)" else "Português (PT-BR)", theme, playClick = { expandedLang = true }) {
                DropdownMenu(expanded = expandedLang, onDismissRequest = { expandedLang = false }, modifier = Modifier.background(theme.surface)) {
                    listOf("Português (PT-BR)", "English (US)").forEach { lang -> DropdownMenuItem(text = { Text(lang, color = theme.text, fontFamily = theme.fontFamily) }, onClick = { onLanguageChange(lang); expandedLang = false; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) }
                }
            }

            ConsoleCard(if (isEn) "Theme" else "Tema", AvailableThemes.getOrElse(currentThemeIndex) { AvailableThemes[0] }.name, theme, playClick = { expandedTheme = true }) {
                DropdownMenu(expanded = expandedTheme, onDismissRequest = { expandedTheme = false }, modifier = Modifier.background(theme.surface)) {
                    AvailableThemes.forEachIndexed { index, consoleTheme -> DropdownMenuItem(text = { Text(consoleTheme.name, color = theme.text, fontFamily = theme.fontFamily) }, onClick = { onThemeChange(index); expandedTheme = false; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) }
                }
            }

            ConsoleCard(if (isEn) "AMOLED Black" else "Preto AMOLED", if (isEn) "Absolute dark mode" else "Modo escuro absoluto", theme) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Force AMOLED Black" else "Forçar Preto AMOLED", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = amoledBlack, theme = theme, onCheckedChange = { onAmoledToggle(it); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) })
                }
            }

            Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onFinish() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) {
                Text(if (isEn) "START" else "INICIAR", color = theme.onPrimary, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun LiveWallpaperRenderer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ALL; volume = 0f } }
    LaunchedEffect(uri) { exoPlayer.setMediaItem(MediaItem.fromUri(uri)); exoPlayer.prepare(); exoPlayer.playWhenReady = true }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = false; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM } }, modifier = Modifier.fillMaxSize())
}

@Composable
fun StaticWallpaperRenderer(resId: Int?, uri: Uri?, blurModifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(resId, uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(resId, uri) {
        withContext(Dispatchers.IO) {
            try {
                if (resId != null) {
                    val drawable = ContextCompat.getDrawable(context, resId)
                    if (drawable is android.graphics.drawable.BitmapDrawable) { bitmap = drawable.bitmap.asImageBitmap() } else if (drawable != null) { val source = ImageDecoder.createSource(context.resources, resId); bitmap = ImageDecoder.decodeBitmap(source).asImageBitmap() }
                } else if (uri != null) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    bitmap = ImageDecoder.decodeBitmap(source).asImageBitmap()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
    bitmap?.let { Image(bitmap = it, contentDescription = "Static Wallpaper", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(blurModifier)) }
}

@Composable
fun VideoBootScreen(theme: ConsoleTheme, onVideoEnded: () -> Unit) {
    val context = LocalContext.current
    val videoUri = "android.resource://${context.packageName}/${R.raw.boot_video}"
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(videoUri)); prepare(); playWhenReady = true; addListener(object : Player.Listener { override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) onVideoEnded() } }) } }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) { AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = false; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM } }, modifier = Modifier.matchParentSize()) }
}

@Composable
fun ConsoleMenuBar(selectedTab: Int, theme: ConsoleTheme, isEn: Boolean, isLandscape: Boolean, onTabSelected: (Int) -> Unit) {
    val modifier = if (isLandscape) { Modifier.padding(horizontal = 8.dp).fillMaxHeight(0.85f).width(85.dp) } else { Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().height(85.dp) }
    val railShape = RoundedCornerShape(32.dp)
    // Modo vidro: a barra das abas usa o mesmo material dos cartões (superfície translúcida,
    // reflexo no topo e contorno de luz em degradê) -- antes ficava de fora e destoava.
    val railStyle = if (theme.glassMode) {
        Modifier.clip(railShape)
            .background(theme.glass(true))
            .background(Brush.verticalGradient(listOf(theme.glassHighlight, Color.Transparent)))
            .border(1.dp, Brush.verticalGradient(listOf(theme.glassEdgeTop, theme.glassEdgeBottom)), railShape)
    } else {
        Modifier.clip(railShape).background(theme.surface.copy(alpha = 0.5f)).border(1.dp, theme.text.copy(alpha = 0.15f), railShape)
    }
    Box(modifier = modifier.then(railStyle), contentAlignment = Alignment.Center) {
        if (isLandscape) {
            Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                ConsoleTabItem(0, "PERF", R.drawable.ic_sliders, selectedTab, theme, isLandscape, onTabSelected)
                ConsoleTabItem(1, "DISP", R.drawable.ic_palette, selectedTab, theme, isLandscape, onTabSelected)
                ConsoleTabItem(2, "CTRL", R.drawable.ic_gamepad, selectedTab, theme, isLandscape, onTabSelected)
                ConsoleTabItem(3, "SYS", R.drawable.ic_app_settings, selectedTab, theme, isLandscape, onTabSelected)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                ConsoleTabItem(0, "PERFORMANCE", R.drawable.ic_sliders, selectedTab, theme, isLandscape, onTabSelected)
                ConsoleTabItem(1, "DISPLAY", R.drawable.ic_palette, selectedTab, theme, isLandscape, onTabSelected)
                ConsoleTabItem(2, if (isEn) "CONTROLS" else "CONTROLES", R.drawable.ic_gamepad, selectedTab, theme, isLandscape, onTabSelected)
                ConsoleTabItem(3, if (isEn) "SYSTEM" else "SISTEMA", R.drawable.ic_app_settings, selectedTab, theme, isLandscape, onTabSelected)
            }
        }
    }
}

@Composable
fun ConsoleTabItem(index: Int, title: String, iconResId: Int, selectedTab: Int, theme: ConsoleTheme, isLandscape: Boolean, onClick: (Int) -> Unit) {
    val isSelected = selectedTab == index
    val color by animateColorAsState(if (isSelected) theme.primary else theme.text.copy(alpha = 0.4f), label = "tabColor")
    // No modo vidro a aba ativa ganha uma "pílula" translúcida, como nas barras do Liquid Glass.
    val pill by animateColorAsState(if (isSelected && theme.glassMode) theme.primary.copy(alpha = 0.16f) else Color.Transparent, label = "tabPill")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(pill).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick(index) }.padding(if (isLandscape) 8.dp else 10.dp)) {
        Icon(painterResource(iconResId), contentDescription = title, tint = color, modifier = Modifier.size(if (isLandscape) 24.dp else 28.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(title, color = color, fontSize = if (isLandscape) 10.sp else 12.sp, fontFamily = theme.fontFamily, fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold, letterSpacing = 1.sp)
        Box(modifier = Modifier.height(3.dp).width(24.dp).clip(RoundedCornerShape(50)).background(if (isSelected) theme.primary else Color.Transparent))
    }
}

@Composable
fun PerformancePanel(uiState: MainUiModel, viewModel: MainViewModel, theme: ConsoleTheme, isEn: Boolean, navigateToOverrideList: () -> Unit, playClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val isTdpMode = uiState.activeLimitMode == "TDP"
    val isClockMode = uiState.activeLimitMode == "CLOCK"

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DeviceInfoCard(device = uiState.device, tables = uiState.clockTables, theme = theme, isEn = isEn)

        ConsoleCard(if (isEn) "Per-App Overrides" else "Configurações por Aplicativo", if (isEn) "Customize TDP, Clocks, and Shaders per game" else "Personalize TDP, Clocks e Shaders individualmente por jogo", theme, playClick = { navigateToOverrideList(); playClick() }) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (isEn) "Manage game-specific rules" else "Gerenciar regras específicas de jogos", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                Text(if (isEn) "Configure >" else "Configurar >", color = theme.primary, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily, fontSize = 12.sp)
            }
        }

        ConsoleSectionHeader(if (isEn) "Fan Control" else "Controle de Ventoinha", theme)
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.scrim).border(1.dp, theme.text.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // Usa os settingsValue reais de FanMode (1/4/5) em vez de índices 0/1/2 escritos à
            // mão: a auditoria encontrou este mesmo ecrã, o overlay e o ForegroundAppWatcherService
            // cada um com o seu próprio mapeamento de números, pelo que a ventoinha escolhida
            // aqui era reinterpretada como outro modo (ou como "Stock") assim que o serviço de
            // acessibilidade reaplicava os perfis ao trocar de app.
            // "Stock" devolve a ventoinha ao modo que o sistema tinha antes (ver
            // PerformanceManager.applyFanMode); antes não havia forma de voltar atrás.
            for (mode in FanMode.selectable + FanMode.Stock) {
                val isSel = if (mode == FanMode.Stock) FanMode.selectable.none { it.settingsValue == uiState.fanMode } else uiState.fanMode == mode.settingsValue
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else Color.Transparent).clickable { viewModel.updateFanMode(mode.settingsValue) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(mode.shortLabel, color = if (isSel) theme.onPrimary else theme.text.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        ConsoleSectionHeader(if (isEn) "Hardware Limitation Mode" else "Modo de Limitação de Hardware", theme)
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.scrim).border(1.dp, theme.text.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(4.dp)) {
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isTdpMode) theme.primary.copy(alpha = 0.8f) else Color.Transparent).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateLimitMode("TDP"); playClick() }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text(if (isEn) "Lock by TDP" else "Limitar por TDP", color = if (isTdpMode) theme.onPrimary else theme.text.copy(alpha=0.6f), fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily) }
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isClockMode) theme.primary.copy(alpha = 0.8f) else Color.Transparent).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateLimitMode("CLOCK"); playClick() }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text(if (isEn) "Lock by Clocks" else "Limitar por Clocks", color = if (isClockMode) theme.onPrimary else theme.text.copy(alpha=0.6f), fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily) }
        }

        // Os DOIS cartões ficam sempre visíveis; o do modo inativo é desenhado com
        // `enabled = false` (o ConsoleCard esbate-o) e com um aviso de uma linha lá dentro. O
        // perfil de TDP é o mestre (conduz CPU e GPU); os clocks são manuais, para quem quer
        // fixar valores específicos -- os dois nunca interferem (ver PerformanceCards.kt).
        TdpProfileCard(
            enabled = isTdpMode,
            tdpProfileId = uiState.tdpProfileId,
            tdpValue = uiState.tdpValue,
            customProfiles = uiState.customProfiles,
            autoTdp = uiState.autoTdp,
            theme = theme,
            isEn = isEn,
            onSelectProfile = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.selectTdpProfile(it); playClick() },
            onSelectCustomProfile = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.selectCustomTdpProfile(it); playClick() },
            onSliderChange = { viewModel.updateTdp(it) },
            onSavePreset = { name, watts -> viewModel.saveCustomProfile(name, "TDP", watts, 0f, 0f, 0f) },
        )

        ManualClockCard(
            soc = uiState.device.soc,
            enabled = isClockMode,
            tables = uiState.clockTables,
            perfMHz = uiState.cpuPerfClock,
            primeMHz = uiState.cpuPrimeClock,
            effMHz = uiState.cpuEffClock,
            gpuMHz = uiState.gpuClock,
            customProfiles = uiState.customProfiles,
            theme = theme,
            isEn = isEn,
            onClocks = { perf, prime, gpu, eff -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateManualClocks(perf, prime, gpu, eff); playClick() },
            onSavePreset = { name, perf, prime, gpu, eff -> viewModel.saveCustomProfile(name, "CLOCK", 0f, perf, prime, gpu, eff) },
        )

        ConsoleSectionHeader(if (isEn) "Thermal" else "Térmico", theme)
        ThermalLimitCard(
            status = uiState.thermalStatus,
            theme = theme,
            isEn = isEn,
            onSelectMode = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateThermalLimitMode(it); playClick() },
            onDisableZoneMode = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateThermalDisableZoneMode(it); playClick() },
            onRefresh = { viewModel.refreshThermalZones() },
        )
    }
}

@Composable
fun DisplayPanel(uiState: MainUiModel, viewModel: MainViewModel, theme: ConsoleTheme, isEn: Boolean, playClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var expandedLsfgMult by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // SGSR / Frame Gen escondidos enquanto não houver motor gráfico funcional dentro dos
        // jogos (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) -- eram interruptores sem efeito.
        if (!FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) {
            ConsoleCard(
                if (isEn) "Graphics Engine (SGSR / ReShade / Frame Gen)" else "Motor Gráfico (SGSR / ReShade / Frame Gen)",
                if (isEn) "Not available in this version" else "Indisponível nesta versão",
                theme,
                enabled = false,
            ) {
                Text(
                    if (isEn) "In-game upscaling and post-processing are not part of this build yet." else "O upscaling e o pós-processamento dentro dos jogos ainda não fazem parte desta build.",
                    color = theme.text.copy(alpha = 0.75f), fontFamily = theme.fontFamily, fontSize = 12.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) {
        ConsoleSectionHeader(if (isEn) "Upscaling & Frame Generation" else "Upscaling e Geração de Quadros", theme)

        ConsoleCard("Snapdragon Super Resolution (SGSR)", if (isEn) "Global graphics upscaling" else "Upscaling gráfico global", theme) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Enable global SGSR" else "Ativar SGSR global", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = uiState.globalSgsrEnabled, theme = theme, onCheckedChange = { viewModel.updateGlobalSgsr(it); playClick() })
                }
                Spacer(Modifier.height(12.dp))
                Text(if (isEn) "SGSR mode" else "Modo SGSR", color = theme.text.copy(alpha = 0.7f), fontFamily = theme.fontFamily, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Quality", "Balanced", "Performance", "Ultra").forEach { mode ->
                        val isSel = uiState.sgsrMode == mode
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else theme.surface).clickable { viewModel.updateSgsrOptions(mode, uiState.sgsrSharpness); playClick() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(text = mode, color = if (isSel) theme.onPrimary else theme.text, fontFamily = theme.fontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text((if (isEn) "Sharpness: " else "Nitidez: ") + "%.2f".format(uiState.sgsrSharpness), color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                Slider(value = uiState.sgsrSharpness, onValueChange = { viewModel.updateSgsrOptions(uiState.sgsrMode, it) }, valueRange = 0.0f..1.0f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
            }
        }

        ConsoleCard("Lossless Scaling (Global)", if (uiState.isDllImported) (if (isEn) "Lossless.dll imported" else "Lossless.dll importada") else (if (isEn) "Lossless.dll missing -- import it in System" else "Lossless.dll ausente -- importe em Sistema"), theme) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Enable LSFG Globally" else "Ativar LSFG Globalmente", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = uiState.globalLsfgEnabled, theme = theme, onCheckedChange = { if (uiState.isDllImported) { viewModel.updateGlobalLsfg(it); playClick() } })
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isEn) "Multiplier" else "Multiplicador", color = theme.text, fontFamily = theme.fontFamily)
                    Box {
                        Text(uiState.lsfgMultiplier, color = theme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if (uiState.globalLsfgEnabled) { expandedLsfgMult = true; playClick() } }.alpha(if(uiState.globalLsfgEnabled) 1f else 0.5f))
                        DropdownMenu(expanded = expandedLsfgMult, onDismissRequest = { expandedLsfgMult = false }, modifier = Modifier.background(theme.surface)) {
                            listOf("2x", "3x", "4x").forEach { mult -> DropdownMenuItem(text = { Text(mult, color = theme.text, fontFamily = theme.fontFamily) }, onClick = { viewModel.updateLsfgOptions(mult, uiState.lsfgFramePacing, uiState.lsfgPerformanceMode); expandedLsfgMult = false; playClick() }) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isEn) "Frame pacing" else "Sincronia (Frame Pacing)", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = uiState.lsfgFramePacing, theme = theme, onCheckedChange = { viewModel.updateLsfgOptions(uiState.lsfgMultiplier, it, uiState.lsfgPerformanceMode); playClick() })
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isEn) "LSFG performance mode" else "Modo Performance LSFG", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = uiState.lsfgPerformanceMode, theme = theme, onCheckedChange = { viewModel.updateLsfgOptions(uiState.lsfgMultiplier, uiState.lsfgFramePacing, it); playClick() })
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isEn) "Show LSFG FPS counter" else "Exibir contador de FPS LSFG", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = uiState.showFpsOverlay, theme = theme, onCheckedChange = { viewModel.toggleFpsOverlay(it); playClick() })
                }
            }
        }
        }

        ConsoleSectionHeader(if (isEn) "Color Calibration" else "Calibração de Cor", theme)
        ConsoleCard(if (isEn) "Manual Adjustments" else "Ajustes Manuais", if (isEn) "Saturation & temperature" else "Saturação e temperatura", theme) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text((if (isEn) "Saturation: " else "Saturação: ") + "%.1f".format(uiState.currentSaturation), color = theme.text, fontFamily = theme.fontFamily)
                    Text(if (isEn) "RESET" else "REDEFINIR", color = theme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.resetDisplayColors(); playClick() })
                }
                Slider(value = uiState.currentSaturation, onValueChange = { viewModel.saveSaturation(it) }, valueRange = 0.0f..2.0f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text((if (isEn) "Temperature: " else "Temperatura: ") + "${uiState.currentTemperature.toInt()}K", color = theme.text, fontFamily = theme.fontFamily)
                    Text(if (isEn) "RESET" else "REDEFINIR", color = theme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.resetDisplayColors(); playClick() })
                }
                Slider(value = uiState.currentTemperature, onValueChange = { viewModel.saveTemperature(it) }, valueRange = 4000f..9000f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
            }
        }
    }
}

@Composable
fun ControlsPanel(uiState: MainUiModel, viewModel: MainViewModel, theme: ConsoleTheme, isEn: Boolean, playClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current

    if (uiState.showRemapButtonDialog) {
        ConsoleRemapDialog(initialValue = uiState.currentButtonKeyCode, theme = theme, isEn = isEn, onCancel = { viewModel.remapButtonDialogDismissed() }, onReset = { viewModel.saveButtonKeyCode(uiState.currentButtonSetting, 0); playClick() }, onSave = { viewModel.saveButtonKeyCode(uiState.currentButtonSetting, it); playClick() })
    }

    uiState.macroEditorFor?.let { button ->
        val isM1 = button == "m1"
        MacroEditorDialog(
            buttonLabel = if (isM1) "M1" else "M2",
            initialEnabled = if (isM1) uiState.m1MacroEnabled else uiState.m2MacroEnabled,
            initialSteps = if (isM1) uiState.m1MacroSteps else uiState.m2MacroSteps,
            theme = theme,
            isEn = isEn,
            onCancel = { viewModel.closeMacroEditor() },
            onSave = { enabled, steps -> viewModel.saveMacro(button, enabled, steps); playClick() },
        )
    }

    if (uiState.showOverlayShortcutDialog) {
        ConsoleRemapDialog(initialValue = uiState.overlayShortcutKeyCode, theme = theme, isEn = isEn, onCancel = { viewModel.hideOverlayShortcutDialog() }, onReset = { viewModel.saveOverlayShortcut(0); playClick() }, onSave = { viewModel.saveOverlayShortcut(it); playClick() })
    }

    fun getDisplayName(keyCode: Int): String {
        if (keyCode == 0) return if (isEn) "None" else "Nenhum"
        return android.view.KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConsoleSectionHeader(if (isEn) "Shortcuts & Actions" else "Atalhos e Ações", theme)

        ConsoleCard(if (isEn) "Odin Hub Overlay" else "Atalho do Odin Hub", if (isEn) "Physical button to open the side menu" else "Botão físico para invocar o menu lateral", theme, playClick = null) {
            Row(modifier = Modifier.fillMaxWidth().clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); playClick(); viewModel.showOverlayShortcutDialog() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_app_settings), contentDescription = null, tint = theme.text, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(if (isEn) "Open Sidebar" else "Abrir Overlay", color = theme.text, fontFamily = theme.fontFamily)
                }
                Text(getDisplayName(uiState.overlayShortcutKeyCode), color = theme.primary, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily)
            }
        }

        // Antes: "Toque único no Home" -- já existe nas definições da própria AYN. No lugar dele,
        // gestos configuráveis do Home (screenshot, gravação de ecrã, overlay...).
        HomeGesturesCard(config = uiState.homeGestures, theme = theme, isEn = isEn, onChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateHomeGestures(it); playClick() })

        // Só em aparelhos com botões traseiros remapeáveis pelo firmware (família Odin; nos
        // Retroid a chave remap_custom_to_mX_value pode não existir -- ver DeviceCapabilities).
        if (uiState.hasBackButtons) {
        ConsoleSectionHeader(if (isEn) "Hardware Mapping" else "Mapeamento Físico", theme)

        ConsoleCard(if (isEn) "Back Buttons (M1/M2)" else "Botões Traseiros (M1/M2)", if (isEn) "Native remap, or macros / combos" else "Mapeamento nativo, ou macros / combos", theme, playClick = null) {
            Row(modifier = Modifier.fillMaxWidth().clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); playClick(); viewModel.remapButtonClicked(SettingsRepo.KEY_CUSTOM_M1_VALUE) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_gamepad), contentDescription = null, tint = theme.text, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(if (isEn) "M1 Button" else "Botão M1", color = theme.text, fontFamily = theme.fontFamily)
                    Text(if (uiState.m1MacroEnabled) (if (isEn) "Macro active — native remap replaced" else "Macro ativa — substitui o mapeamento nativo") else (if (isEn) "Remap to any key — press to choose" else "Mapear para qualquer tecla — toque para escolher"), color = theme.text.copy(alpha=0.6f), fontSize = 12.sp, fontFamily = theme.fontFamily)
                }
            }
            MacroEntryRow("M1", uiState.m1MacroEnabled, uiState.m1MacroSteps.size, theme, isEn) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); playClick(); viewModel.openMacroEditor("m1") }
            HorizontalDivider(color = theme.text.copy(alpha = 0.1f), thickness = 1.dp)
            Row(modifier = Modifier.fillMaxWidth().clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); playClick(); viewModel.remapButtonClicked(SettingsRepo.KEY_CUSTOM_M2_VALUE) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_gamepad), contentDescription = null, tint = theme.text, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(if (isEn) "M2 Button" else "Botão M2", color = theme.text, fontFamily = theme.fontFamily)
                    Text(if (uiState.m2MacroEnabled) (if (isEn) "Macro active — native remap replaced" else "Macro ativa — substitui o mapeamento nativo") else (if (isEn) "Remap to any key — press to choose" else "Mapear para qualquer tecla — toque para escolher"), color = theme.text.copy(alpha=0.6f), fontSize = 12.sp, fontFamily = theme.fontFamily)
                }
            }
            MacroEntryRow("M2", uiState.m2MacroEnabled, uiState.m2MacroSteps.size, theme, isEn) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); playClick(); viewModel.openMacroEditor("m2") }
        }
        }
    }
}

@Composable
fun SystemPanel(
    uiState: MainUiModel, viewModel: MainViewModel, theme: ConsoleTheme, isEn: Boolean, amoledBlack: Boolean,
    liveWallpaperType: String, blurEnabled: Boolean, blurIntensity: Float, wallpaperOpacity: Float, selectedWallpaperName: String,
    currentThemeIndex: Int, currentLanguage: String, onThemeChange: (Int) -> Unit, onLanguageChange: (String) -> Unit,
    onAmoledToggle: (Boolean) -> Unit, onLiveWallpaperTypeChange: (String) -> Unit, onBlurToggle: (Boolean) -> Unit,
    onBlurIntensityChange: (Float) -> Unit, onWallpaperOpacityChange: (Float) -> Unit, onPresetStaticSelected: (Int, String) -> Unit,
    onPresetVideoSelected: () -> Unit, onCustomUriSelected: (Uri, String) -> Unit, onReplayBoot: () -> Unit,
    bgmEnabled: Boolean, bgmVolume: Float, sfxEnabled: Boolean, sfxVolume: Float,
    onBgmToggle: (Boolean) -> Unit, onBgmVolume: (Float) -> Unit, onSfxToggle: (Boolean) -> Unit, onSfxVolume: (Float) -> Unit,
    playClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var expandedLang by rememberSaveable { mutableStateOf(false) }
    var expandedTheme by rememberSaveable { mutableStateOf(false) }
    var expandedWallType by rememberSaveable { mutableStateOf(false) }

    // OpenDocument + permissão persistente: com GetContent o acesso ao ficheiro escolhido
    // expirava com o processo e o fundo personalizado ficava em branco no arranque seguinte.
    fun keepAccess(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { keepAccess(it); onCustomUriSelected(it, if (isEn) "Custom image" else "Imagem personalizada") } }
    val videoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { keepAccess(it); onCustomUriSelected(it, if (isEn) "Custom video" else "Vídeo personalizado") } }

    val losslessPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val success = viewModel.importLosslessDll(it)
            Toast.makeText(context, if (success) (if (isEn) "Lossless.dll imported" else "Lossless.dll importada com sucesso!") else (if (isEn) "Import failed" else "Falha na importação"), Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConsoleSectionHeader(if (isEn) "Overlay & Integration" else "Overlay e Integração", theme)
        ConsoleCard(if (isEn) "Side Menu / Gaming Overlay" else "Menu Lateral / Overlay de Jogo", if (isEn) "Live side bar shown only inside games" else "Barra lateral em tempo real, só dentro dos jogos", theme) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Enable sidebar" else "Ativar sidebar", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = uiState.overlayEnabled, theme = theme, onCheckedChange = {
                        if (!Settings.canDrawOverlays(context)) {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            context.startActivity(intent)
                            Toast.makeText(context, if (isEn) "Grant the overlay permission" else "Conceda a permissão de sobreposição", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.toggleOverlay(it)
                        }
                        playClick()
                    })
                }
                Spacer(Modifier.height(16.dp))
                // "Opacidade do Fundo" removida daqui de propósito: era um controlo duplicado e
                // não-funcional em relação ao painel de Settings. A aba "Visuals" do próprio
                // overlay (QuickAccessContent.kt) já tem o slider real que controla
                // prefs.overlayPanelOpacity em tempo real durante o jogo -- este aqui só
                // escrevia a mesma preferência sem qualquer feedback visual imediato, daí
                // parecer "não fazer nada". A preferência e updateOverlayPanelOpacity() continuam
                // a existir; só a linha duplicada da UI foi removida.
                Text((if (isEn) "Handle opacity: " else "Opacidade do puxador: ") + "${(uiState.overlayHandleOpacity * 100).toInt()}%", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                Slider(value = uiState.overlayHandleOpacity, onValueChange = { viewModel.updateHandleOpacity(it) }, valueRange = 0.1f..1.0f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
                Spacer(Modifier.height(8.dp))
                Text((if (isEn) "Handle width: " else "Espessura do puxador: ") + "${uiState.overlayHandleWidth} dp", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                Slider(value = uiState.overlayHandleWidth.toFloat(), onValueChange = { viewModel.updateHandleWidth(it.toInt()) }, valueRange = 16f..36f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))

                Spacer(Modifier.height(16.dp))
                // O puxador real só existe DENTRO de um jogo, por isso configurar a opacidade e a
                // espessura aqui era às cegas -- o utilizador mexia nos sliders sem nunca ver o
                // efeito. Esta pré-visualização desenha o puxador exatamente como ele aparece
                // sobre o jogo (mesma forma, mesma cor do tema, mesmos valores), encostado à
                // direita como no aparelho.
                OverlayHandlePreview(
                    theme = theme,
                    handleOpacity = uiState.overlayHandleOpacity,
                    handleWidthDp = uiState.overlayHandleWidth,
                    isEn = isEn,
                )
            }
        }

        // HUD de desempenho (FPS, temperaturas, frequências...), ao lado do overlay lateral.
        val hudConfig by viewModel.hudConfig.collectAsState()
        val sensorReport by viewModel.sensorReport.collectAsState()
        HudSettingsCard(
            config = hudConfig,
            theme = theme,
            isEn = isEn,
            hasOverlayPermission = { Settings.canDrawOverlays(context) },
            onRequestPermission = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                Toast.makeText(context, if (isEn) "Grant the overlay permission" else "Conceda a permissão de sobreposição", Toast.LENGTH_LONG).show()
            },
            onChange = { viewModel.updateHudConfig(it) },
            onShowAgain = { viewModel.showHudAgain(); playClick() },
            sensorReport = sensorReport,
            onRunDiagnostics = { viewModel.runSensorDiagnostics(); playClick() },
            onCloseDiagnostics = { viewModel.closeSensorDiagnostics() },
        )

        ConsoleSectionHeader(if (isEn) "Audio" else "Áudio", theme)
        ConsoleCard(if (isEn) "Background Music" else "Música de Fundo", if (isEn) "Loops while the app is open" else "Toca em loop enquanto a app está aberta", theme) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Enable" else "Ativar", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = bgmEnabled, theme = theme, onCheckedChange = { onBgmToggle(it) })
                }
                Spacer(Modifier.height(12.dp))
                Text(if (isEn) "Volume: ${(bgmVolume * 100).toInt()}%" else "Volume: ${(bgmVolume * 100).toInt()}%", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                Slider(value = bgmVolume, onValueChange = onBgmVolume, enabled = bgmEnabled, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
            }
        }
        ConsoleCard(if (isEn) "UI Sound Effects" else "Efeitos Sonoros da Interface", if (isEn) "Clicks and navigation feedback" else "Cliques e feedback de navegação", theme) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Enable" else "Ativar", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = sfxEnabled, theme = theme, onCheckedChange = { onSfxToggle(it) })
                }
                Spacer(Modifier.height(12.dp))
                Text(if (isEn) "Volume: ${(sfxVolume * 100).toInt()}%" else "Volume: ${(sfxVolume * 100).toInt()}%", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                Slider(value = sfxVolume, onValueChange = onSfxVolume, enabled = sfxEnabled, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
            }
        }

        if (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) {
        ConsoleSectionHeader(if (isEn) "Lossless Scaling Engine" else "Motor Lossless Scaling", theme)
        ConsoleCard(if (isEn) "Lossless.dll Integration" else "Integração Lossless.dll", if (uiState.isDllImported) (if (isEn) "✅ File imported" else "✅ Ficheiro importado") else (if (isEn) "❌ File missing" else "❌ Ficheiro ausente"), theme) {
            Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); losslessPickerLauncher.launch("*/*"); playClick() }, modifier = Modifier.padding(16.dp).fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) {
                Text(if (uiState.isDllImported) (if (isEn) "Replace Lossless.dll..." else "Substituir Lossless.dll...") else (if (isEn) "Import Lossless.dll from Steam..." else "Importar Lossless.dll da Steam..."), color = theme.onPrimary, fontFamily = theme.fontFamily)
            }
        }
        }

        ConsoleSectionHeader(if (isEn) "Language & Region" else "Idioma e Região", theme)
        ConsoleCard(if (isEn) "System Language" else "Idioma do Sistema", currentLanguage, theme, playClick = { expandedLang = true; playClick() }) {
            DropdownMenu(expanded = expandedLang, onDismissRequest = { expandedLang = false }, modifier = Modifier.background(theme.surface)) {
                listOf("Português (PT-BR)", "English (US)").forEach { lang -> DropdownMenuItem(text = { Text(lang, color = theme.text, fontFamily = theme.fontFamily) }, onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onLanguageChange(lang); expandedLang = false; playClick() }) }
            }
        }

        ConsoleSectionHeader(if (isEn) "UI Customization" else "Personalização UI", theme)
        ConsoleCard(if (isEn) "Console Theme" else "Tema do Console", AvailableThemes.getOrElse(currentThemeIndex) { AvailableThemes[0] }.name, theme, playClick = { expandedTheme = true; playClick() }) {
            DropdownMenu(expanded = expandedTheme, onDismissRequest = { expandedTheme = false }, modifier = Modifier.background(theme.surface)) {
                AvailableThemes.forEachIndexed { index, consoleTheme -> DropdownMenuItem(text = { Text(consoleTheme.name, color = if (currentThemeIndex == index) theme.primary else theme.text, fontFamily = theme.fontFamily) }, onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onThemeChange(index); expandedTheme = false; playClick() }) }
            }
        }
        ConsoleCard(if (isEn) "AMOLED Black" else "Preto AMOLED", if (isEn) "Absolute dark background" else "Fundo escuro absoluto (Adaptativo)", theme, playClick = playClick) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (isEn) "Force AMOLED Black" else "Forçar Preto AMOLED", color = theme.text, fontFamily = theme.fontFamily)
                ConsoleToggle(checked = amoledBlack, theme = theme, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAmoledToggle(it); playClick() })
            }
        }
        // Vidro: vale para TODOS os temas (cartões, overlay lateral e HUD de desempenho).
        ConsoleCard(if (isEn) "Glass Appearance" else "Aparência de Vidro", if (isEn) "Translucent, Liquid Glass–style surfaces on any theme" else "Superfícies translúcidas, ao estilo Liquid Glass, em qualquer tema", theme, playClick = playClick) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isEn) "Glass mode" else "Modo vidro", color = theme.text, fontFamily = theme.fontFamily)
                    Text(
                        if (isEn) "Best with a wallpaper. The overlay blurs the game behind it when the system allows."
                        else "Fica melhor com um wallpaper. O overlay desfoca o jogo por trás quando o sistema permite.",
                        color = theme.secondaryText, fontFamily = theme.fontFamily, fontSize = 10.sp,
                    )
                }
                ConsoleToggle(checked = uiState.useGlass, theme = theme, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateGlass(it); playClick() })
            }
        }

        ConsoleSectionHeader(if (isEn) "Live Wallpaper & Background" else "Live Wallpaper & Fundo", theme)
        // Os valores guardados continuam "Static"/"Live (MP4)"; só o texto mostrado é traduzido.
        fun wallTypeLabel(type: String) = if (type == "Static") (if (isEn) "Static" else "Estático") else (if (isEn) "Live (MP4)" else "Animado (MP4)")
        ConsoleCard(if (isEn) "Wallpaper Type" else "Tipo de Wallpaper", wallTypeLabel(liveWallpaperType), theme, playClick = { expandedWallType = true; playClick() }) {
            DropdownMenu(expanded = expandedWallType, onDismissRequest = { expandedWallType = false }, modifier = Modifier.background(theme.surface)) {
                listOf("Static", "Live (MP4)").forEach { type -> DropdownMenuItem(text = { Text(wallTypeLabel(type), color = theme.text, fontFamily = theme.fontFamily) }, onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onLiveWallpaperTypeChange(type); expandedWallType = false; playClick() }) }
            }
        }
        ConsoleCard(if (isEn) "Blur & Opacity Adjustments" else "Ajustes de Blur e Opacidade", if (isEn) "Control background visibility" else "Controlar visibilidade do fundo", theme, playClick = null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Enable Blur Effect" else "Habilitar Efeito de Desfoque", color = theme.text, fontFamily = theme.fontFamily)
                    ConsoleToggle(checked = blurEnabled, theme = theme, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onBlurToggle(it); playClick() })
                }
                Text(if (isEn) "Blur Intensity" else "Intensidade do Desfoque", color = theme.text.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Slider(value = blurIntensity, onValueChange = { onBlurIntensityChange(it) }, enabled = blurEnabled, valueRange = 0.0f..1.0f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
                Text(if (isEn) "Wallpaper Opacity" else "Opacidade do Wallpaper", color = theme.text.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Slider(value = wallpaperOpacity, onValueChange = { onWallpaperOpacityChange(it) }, valueRange = 0.0f..1.0f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
            }
        }
        ConsoleCard(if (isEn) "Wallpaper Selection" else "Seleção de Wallpaper", selectedWallpaperName, theme, playClick = playClick) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (liveWallpaperType == "Static") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPresetStaticSelected(R.drawable.static_wallpaper_1, if (isEn) "Preset 1" else "Predefinição 1"); playClick() }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("Wallpaper 1", color = theme.onPrimary, fontFamily = theme.fontFamily) }
                        Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPresetStaticSelected(R.drawable.static_wallpaper_2, if (isEn) "Preset 2" else "Predefinição 2"); playClick() }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("Wallpaper 2", color = theme.onPrimary, fontFamily = theme.fontFamily) }
                    }
                } else {
                    Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPresetVideoSelected(); playClick() }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text(if (isEn) "Default Live Wallpaper" else "Live Wallpaper Padrão", color = theme.onPrimary, fontFamily = theme.fontFamily) }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); if (liveWallpaperType == "Static") imagePickerLauncher.launch(arrayOf("image/*")) else videoPickerLauncher.launch(arrayOf("video/*")); playClick() }, colors = ButtonDefaults.buttonColors(containerColor = theme.surface)) { Text(if (isEn) "Choose from device..." else "Escolher do dispositivo...", color = theme.text, fontFamily = theme.fontFamily) }
            }
        }
        ConsoleSectionHeader(if (isEn) "About" else "Sobre", theme)
        AboutCard(theme = theme, isEn = isEn, playClick = playClick, onReplayBoot = onReplayBoot, deviceLine = "${uiState.device.name} · ${uiState.device.soc.label}", onCheckUpdates = { viewModel.checkForUpdates() })
    }
}

/**
 * Cartão "Sobre": o que é o projeto, quem o faz, que versão está instalada e para onde ir
 * (repositório / atualizações).
 *
 * A versão sai do [AppInfo] (ou seja, do `versionName` do Gradle). Antes estava escrita à mão no
 * subtítulo do cartão como "Versão 0.5", o que significa que ficava errada assim que uma release
 * nova fosse publicada sem alguém se lembrar de editar esta linha.
 */
@Composable
private fun AboutCard(
    theme: ConsoleTheme,
    isEn: Boolean,
    playClick: () -> Unit,
    onReplayBoot: () -> Unit,
    deviceLine: String,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    fun openUrl(url: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        playClick()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(
                    context,
                    if (isEn) "No browser available" else "Nenhum navegador disponível",
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    ConsoleCard(AppInfo.NAME, "v${AppInfo.version} (build ${AppInfo.versionCode})", theme, playClick = playClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (isEn) AppInfo.TAGLINE_EN else AppInfo.TAGLINE_PT,
                color = theme.text.copy(alpha = 0.75f),
                fontFamily = theme.fontFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            Spacer(Modifier.height(14.dp))
            AboutRow(if (isEn) "Developer" else "Desenvolvedor", AppInfo.DEVELOPER, theme)
            AboutRow(if (isEn) "Version" else "Versão", "${AppInfo.version} (${AppInfo.versionCode})", theme)
            AboutRow(if (isEn) "Device" else "Aparelho", deviceLine, theme)
            AboutRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", theme)

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    // Antes abria /releases/latest no browser -- 404 enquanto não há release
                    // publicada. Agora verifica dentro da app e oferece a instalação.
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); playClick(); onCheckUpdates() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                ) {
                    Text(
                        if (isEn) "Check for updates" else "Procurar atualizações",
                        color = theme.onPrimary,
                        fontFamily = theme.fontFamily,
                        fontSize = 12.sp,
                    )
                }
                Button(
                    onClick = { openUrl(AppInfo.GITHUB_URL) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.surface),
                ) {
                    Text("GitHub", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (isEn) "Replay welcome screen" else "Rever Tela de Boas-Vindas",
                color = theme.primary,
                fontFamily = theme.fontFamily,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onReplayBoot()
                },
            )

            Spacer(Modifier.height(14.dp))
            // Atribuição do projeto base. A licença MIT do código de origem exige que o aviso de
            // copyright seja preservado nas redistribuições -- ver o ficheiro LICENSE.
            Text(
                if (isEn) {
                    "Built on open-source work from OdinTools, P.U.L.S.E. and ClusterTune. " +
                        "See LICENSE for details."
                } else {
                    "Construído sobre trabalho open-source do OdinTools, P.U.L.S.E. e ClusterTune. " +
                        "Ver LICENSE para detalhes."
                },
                color = theme.text.copy(alpha = 0.4f),
                fontFamily = theme.fontFamily,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, theme: ConsoleTheme) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 12.sp)
        Text(value, color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ConsoleSectionHeader(title: String, theme: ConsoleTheme) {
    Text(title.uppercase(), fontSize = 14.sp, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold, color = theme.secondaryText, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp).padding(top = 8.dp))
}

@Composable
fun ConsoleCard(title: String, subtitle: String, theme: ConsoleTheme, enabled: Boolean = true, playClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(isFocused) { if (isFocused && enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    val scale by animateFloatAsState(targetValue = if (isFocused && enabled) 1.02f else 1.0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "cardScale")
    val glow by animateDpAsState(targetValue = if (isFocused && enabled) 16.dp else 0.dp, animationSpec = tween(200), label = "cardGlow")

    // Vidro fosco: superfície mais opaca (ver ConsoleTheme.glass) com um brilho no topo, para o
    // texto nunca competir com o wallpaper por trás. Antes a superfície tinha 40% de opacidade
    // e em vários temas mal se liam as letras.
    val glassSurface = theme.glass(enabled)
    val subtleBorder = theme.text.copy(alpha = if (enabled) 0.18f else 0.08f)
    val borderColor by animateColorAsState(targetValue = if (isFocused && enabled) theme.primary else subtleBorder, label = "cardBorder")
    val contentAlpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.5f, label = "contentAlpha")

    val cardShape = RoundedCornerShape(theme.cardRadius.dp)
    // Modo vidro: contorno de luz em degradê (claro em cima) em vez da linha fina uniforme.
    val edge = if (theme.glassMode && !(isFocused && enabled)) {
        Modifier.border(1.dp, Brush.verticalGradient(listOf(theme.glassEdgeTop, theme.glassEdgeBottom)), cardShape)
    } else {
        Modifier.border(1.dp, borderColor, cardShape)
    }
    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = glassSurface),
        modifier = Modifier.fillMaxWidth().scale(scale).shadow(glow, cardShape, spotColor = theme.primary, ambientColor = theme.primary).then(edge).alpha(contentAlpha)
            .then(if (playClick != null && enabled) Modifier.focusable(interactionSource = interactionSource).clickable(interactionSource = interactionSource, indication = null) { playClick() } else Modifier)
    ) {
        // Reflexo subtil no topo do cartão -- o "vidro" do efeito frosted glass.
        Column(modifier = Modifier.background(Brush.verticalGradient(listOf(theme.glassHighlight, Color.Transparent))).padding(vertical = 12.dp)) {
            Text(title, fontSize = 16.sp, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold, color = if (isFocused && enabled) theme.primary else theme.text, modifier = Modifier.padding(horizontal = 16.dp))
            if (subtitle.isNotEmpty()) { Text(subtitle, fontSize = 12.sp, fontFamily = theme.fontFamily, color = theme.secondaryText, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) } else { Spacer(modifier = Modifier.height(8.dp)) }
            HorizontalDivider(color = theme.text.copy(alpha = 0.1f), thickness = 1.dp)
            Column(modifier = Modifier.fillMaxWidth().background(theme.scrim)) { content() }
        }
    }
}

@Composable
fun ConsoleToggle(checked: Boolean, theme: ConsoleTheme, onCheckedChange: (Boolean) -> Unit) {
    val thumbOffset by animateDpAsState(targetValue = if (checked) 24.dp else 4.dp, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "toggleMove")
    val bgColor by animateColorAsState(targetValue = if (checked) theme.primary.copy(alpha = 0.3f) else theme.background, label = "toggleBg")
    val thumbColor by animateColorAsState(targetValue = if (checked) theme.primary else theme.text.copy(alpha = 0.5f), label = "toggleThumb")

    Box(
        modifier = Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).background(bgColor).border(1.dp, thumbColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(modifier = Modifier.padding(start = thumbOffset).size(16.dp).clip(RoundedCornerShape(4.dp)).background(thumbColor))
    }
}

@Composable
fun ConsoleRemapDialog(initialValue: Int, theme: ConsoleTheme, isEn: Boolean, onCancel: () -> Unit, onReset: () -> Unit, onSave: (Int) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    // Chaveado no valor inicial: o ViewModel abre o diálogo antes de a leitura do mapeamento atual
    // chegar (-1 = a ler). Sem a chave, o diálogo ficava com o código do botão ANTERIOR e um
    // "Salvar" sem carregar em nada remapeava M2 para a tecla do M1 (ou para 0).
    var userValue by remember(initialValue) { mutableIntStateOf(initialValue) }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface, modifier = Modifier.focusRequester(focusRequester).focusable().onKeyEvent { if (it.type == KeyEventType.KeyUp) { userValue = it.nativeKeyEvent.keyCode }; true }) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isEn) "Remap Button" else "Mapear Botão", color = theme.primary, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFamily = theme.fontFamily)
                Spacer(Modifier.height(16.dp))
                Text(if (isEn) "Press the new button..." else "Pressione o novo botão...", color = theme.text.copy(alpha = 0.7f), fontFamily = theme.fontFamily)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (userValue < 0) (if (isEn) "Reading…" else "Lendo…") else KeyEvent.keyCodeToString(userValue).replace("KEYCODE_", ""),
                    color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily,
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = theme.background)) { Text(if (isEn) "Default" else "Padrão", color = theme.text, fontFamily = theme.fontFamily) }
                    Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = theme.background)) { Text(if (isEn) "Cancel" else "Cancelar", color = theme.text, fontFamily = theme.fontFamily) }
                    Button(onClick = { if (userValue >= 0) onSave(userValue) }, enabled = userValue >= 0, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text(if (isEn) "Save" else "Salvar", color = theme.onPrimary, fontFamily = theme.fontFamily) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}