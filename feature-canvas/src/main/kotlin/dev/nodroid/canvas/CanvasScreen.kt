package dev.nodroid.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nodroid.model.Node
import dev.nodroid.model.NodeDescriptor
import dev.nodroid.nodes.NodeDescriptors
import kotlin.math.roundToInt

/** AMOLED canvas: pure black, zoom-adaptive dot grid, Bezier wires, smoked-glass node cards. */
@Composable
fun CanvasScreen(viewModel: CanvasViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    var dragNodeId by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NodRoidTheme.CanvasBackground)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val current = viewModel.state.value
                    viewModel.setViewport(
                        current.offsetX + pan.x,
                        current.offsetY + pan.y,
                        current.scale * zoom,
                    )
                }
            },
    ) {
        // Dot grid + wires, drawn in screen space.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = (28f * state.scale).coerceAtLeast(14f)
            var x = (state.offsetX % spacing)
            while (x < size.width) {
                var y = (state.offsetY % spacing)
                while (y < size.height) {
                    drawCircle(color = NodRoidTheme.DotGrid, radius = 1.6f, center = Offset(x, y))
                    y += spacing
                }
                x += spacing
            }
            val nodeById = state.graph.nodes.associateBy { it.id }
            for (edge in state.graph.edges) {
                val from = nodeById[edge.fromNodeId] ?: continue
                val to = nodeById[edge.toNodeId] ?: continue
                val (_, fromAnchor) = CanvasMath.portAnchors(from)
                val (toAnchor, _) = CanvasMath.portAnchors(to)
                val p0 = Offset((fromAnchor.x * state.scale) + state.offsetX, (fromAnchor.y * state.scale) + state.offsetY)
                val p3 = Offset((toAnchor.x * state.scale) + state.offsetX, (toAnchor.y * state.scale) + state.offsetY)
                val active = edge.fromNodeId in state.runningNodeIds
                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    val dx = kotlin.math.abs(p3.x - p0.x).coerceAtLeast(80f)
                    cubicTo(p0.x + dx, p0.y, p3.x - dx, p3.y, p3.x, p3.y)
                }
                drawPath(path, color = if (active) NodRoidTheme.WireActive else NodRoidTheme.WireIdle,
                    style = Stroke(width = if (active) 3f else 2f))
            }
        }
        // Node cards with viewport culling.
        val viewport = CanvasMath.Viewport(state.offsetX, state.offsetY, state.scale, 2000f, 2000f)
        for (node in state.graph.nodes) {
            if (!CanvasMath.isVisible(node.x, node.y, 220f, 96f, viewport)) continue
            NodeCard(
                node = node,
                selected = node.id == state.selectedNodeId,
                running = node.id in state.runningNodeIds,
                timingMs = state.nodeTimingsMs[node.id],
                offsetX = state.offsetX,
                offsetY = state.offsetY,
                scale = state.scale,
                onClick = { viewModel.select(node.id) },
                onDragStart = { dragNodeId = node.id; viewModel.beginMove(node.id) },
                onDrag = { dx, dy ->
                    if (dragNodeId == node.id) {
                        viewModel.moveNode(node.id, node.x + dx / state.scale, node.y + dy / state.scale)
                    }
                },
                onDragEnd = { dragNodeId = null },
            )
        }
    }
}

@Composable
fun NodeCard(
    node: Node,
    selected: Boolean,
    running: Boolean,
    timingMs: Long?,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val descriptor = NodeDescriptors.BY_TYPE[node.type]
    val accent = descriptor?.let { NodRoidTheme.categoryColor(it.category) } ?: Color.Gray
    Box(
        modifier = Modifier
            .offset { IntOffset(((node.x * scale) + offsetX).roundToInt(), ((node.y * scale) + offsetY).roundToInt()) }
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
            .size(220.dp, 96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(NodRoidTheme.CardBackground)
            .border(1.dp, if (selected) accent else NodRoidTheme.CardBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .pointerInput(node.id) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                )
            }
            .padding(10.dp),
    ) {
        Column {
            Row {
                Box(modifier = Modifier.size(8.dp).background(accent, RoundedCornerShape(4.dp)))
                Text(
                    text = node.label,
                    color = NodRoidTheme.TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = descriptor?.displayName ?: node.type,
                color = NodRoidTheme.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = if (running) "RUNNING" else if (timingMs != null) timingMs.toString() + " ms" else node.type,
                color = if (running) NodRoidTheme.WireActive else NodRoidTheme.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 48dp invisible port hit-zones (left input, right output).
        Box(modifier = Modifier.size(48.dp).offset((-24).dp, 24.dp))
        Box(modifier = Modifier.size(48.dp).offset(196.dp, 24.dp))
    }
}

/** Live Execution Inspector: input/output JSON + timing for the selected node. */
@Composable
fun InspectorSheet(
    node: Node?,
    inputJson: String,
    outputJson: String,
    timingMs: Long?,
    onClose: () -> Unit,
) {
    if (node == null) return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NodRoidTheme.CardBackground)
            .padding(16.dp),
    ) {
        Text(text = "INSPECTOR — " + node.label, color = NodRoidTheme.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(text = "type: " + node.type, color = NodRoidTheme.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (timingMs != null) Text(text = "time: " + timingMs + " ms", color = NodRoidTheme.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(text = "INPUT", color = NodRoidTheme.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        Text(text = inputJson, color = NodRoidTheme.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(text = "OUTPUT", color = NodRoidTheme.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        Text(text = outputJson, color = NodRoidTheme.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(text = "Tap outside or press back to close.", color = NodRoidTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp).clickable(onClick = onClose))
    }
}
