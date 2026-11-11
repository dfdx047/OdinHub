package com.dfdx047.odinhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.ThemesByGroup
import com.dfdx047.odinhub.ui.theme.onPrimary

/**
 * Seletor de temas por grupos, com uma miniatura de cada tema (fundo, cartão, destaque) em vez de
 * uma lista de nomes. [theme] é o tema ATUAL (para as cores do próprio seletor).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemePicker(
    theme: ConsoleTheme,
    isEn: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemesByGroup.forEach { (group, themes) ->
            if (themes.isEmpty()) return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.label(isEn).uppercase(), color = theme.text.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = theme.fontFamily)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    themes.forEach { (index, t) ->
                        ThemeSwatch(t, isEn, selected = index == selectedIndex, current = theme) { onSelect(index) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(t: ConsoleTheme, isEn: Boolean, selected: Boolean, current: ConsoleTheme, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier.width(96.dp).clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) current.primary else current.text.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick),
    ) {
        // Miniatura: fundo do tema, um "cartão" e os dois destaques.
        Box(
            modifier = Modifier.fillMaxWidth().height(58.dp).background(
                if (t.isDynamic) Brush.linearGradient(listOf(Color(0xFF6750A4), Color(0xFF00796B), Color(0xFFB3261E)))
                else Brush.verticalGradient(listOf(t.background, t.surface)),
            ),
        ) {
            if (!t.isDynamic) {
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).width(54.dp).height(18.dp)
                        .clip(RoundedCornerShape(5.dp)).background(t.surface),
                ) {
                    Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 5.dp).width(26.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.text.copy(alpha = 0.7f)))
                }
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(t.primary))
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(t.accent2))
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp).clip(CircleShape).background(current.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", color = current.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.Black) }
            }
        }
        Text(
            t.label(isEn),
            color = if (selected) current.primary else current.text,
            fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontFamily = current.fontFamily,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(current.surface.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
    Spacer(modifier = Modifier.height(0.dp))
}
