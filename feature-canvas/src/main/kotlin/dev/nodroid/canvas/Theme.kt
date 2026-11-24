package dev.nodroid.canvas

import androidx.compose.ui.graphics.Color
import dev.nodroid.model.NodeDescriptor

/** AMOLED Technical-Glass palette: pure black canvas, smoked-glass cards, monospace metadata. */
object NodRoidTheme {
    val CanvasBackground = Color(0xFF000000)
    val DotGrid = Color(0xFF1A2230)
    val CardBackground = Color(0xFF0B0F17).copy(alpha = 0.86f)
    val CardBorder = Color(0xFFFFFFFF).copy(alpha = 0.08f)
    val SpecularTop = Color(0xFFFFFFFF).copy(alpha = 0.14f)
    val TextPrimary = Color(0xFFE8ECF1)
    val TextMuted = Color(0xFF8A94A6)
    val WireIdle = Color(0xFF3A4556)
    val WireActive = Color(0xFF22D3EE)

    fun categoryColor(category: NodeDescriptor.NodeCategory): Color = when (category) {
        NodeDescriptor.NodeCategory.TRIGGER -> Color(0xFF10B981) // Emerald
        NodeDescriptor.NodeCategory.LOGIC -> Color(0xFFF59E0B) // Amber
        NodeDescriptor.NodeCategory.DATA -> Color(0xFF22D3EE) // Cyan
        NodeDescriptor.NodeCategory.NETWORK -> Color(0xFF8B5CF6) // Violet
        NodeDescriptor.NodeCategory.AI -> Color(0xFF8B5CF6) // Violet
        NodeDescriptor.NodeCategory.LOCAL -> Color(0xFFFF6B5E) // Coral
    }
}
