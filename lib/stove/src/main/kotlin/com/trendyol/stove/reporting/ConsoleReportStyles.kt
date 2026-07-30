package com.trendyol.stove.reporting

import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightMagenta
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextStyle

internal fun consoleStyleForSystem(system: String): TextStyle {
  val index = (system.lowercase().hashCode() and Int.MAX_VALUE) % CONSOLE_SYSTEM_PALETTE.size
  return CONSOLE_SYSTEM_PALETTE[index]
}

private val CONSOLE_SYSTEM_PALETTE = listOf(brightBlue, brightMagenta, brightCyan, brightGreen, brightYellow)
