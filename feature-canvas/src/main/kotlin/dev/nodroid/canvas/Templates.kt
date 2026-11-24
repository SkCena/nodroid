package dev.nodroid.canvas

import dev.nodroid.model.Edge
import dev.nodroid.model.Node
import dev.nodroid.model.WorkflowGraph
import kotlinx.serialization.json.JsonPrimitive

/** Five starter workflow templates (safe nodes only). */
object StarterTemplates {
    private fun node(id: String, type: String, label: String, vararg config: Pair<String, String>, x: Float = 0f, y: Float = 0f) =
        Node(id, type, label, config.associate { it.first to JsonPrimitive(it.second) }, x, y)
    private fun edge(id: String, from: String, to: String, fromPort: String = "out", toPort: String = "in") =
        Edge(id, from, fromPort, to, toPort)

    val priceChecker = WorkflowGraph(id = "tpl-price", name = "Price / Uptime Checker", nodes = listOf(
        node("t1", "trigger.schedule", "Every hour", "intervalMinutes" to "60", x = 0f, y = 0f),
        node("h1", "net.http", "Check price page", "url" to "https://example.com/product", "method" to "GET", x = 280f, y = 0f),
        node("r1", "data.regex", "Extract price", "preset" to "number", "input" to "{{input.body}}", x = 560f, y = 0f),
        node("i1", "logic.if", "Price dropped?", "left" to "{{input.first}}", "operator" to "<", "right" to "499", x = 840f, y = 0f),
        node("n1", "local.notify", "Alert me", "title" to "Price dropped", "message" to "Price is now {{input.first}}", x = 1120f, y = 0f),
    ), edges = listOf(edge("e1", "t1", "h1"), edge("e2", "h1", "r1"), edge("e3", "r1", "i1"), Edge("e4", "i1", "true", "n1", "in")))

    val ocrToNotes = WorkflowGraph(id = "tpl-ocr", name = "Offline Image OCR to Notes", nodes = listOf(
        node("t1", "trigger.manual", "Manual", x = 0f, y = 0f),
        node("o1", "ai.ocr", "Read image text", "imageUri" to "{{input.imageUri}}", "script" to "latin", x = 280f, y = 0f),
        node("f1", "local.file_write", "Append to notes", "uri" to "{{input.notesUri}}", "content" to "{{input.text}}\n", "append" to "true", x = 560f, y = 0f),
    ), edges = listOf(edge("e1", "t1", "o1"), edge("e2", "o1", "f1")))

    val articleSummarizer = WorkflowGraph(id = "tpl-summary", name = "AI Article Summarizer", nodes = listOf(
        node("t1", "trigger.share", "Share article", x = 0f, y = 0f),
        node("s1", "data.html_scraper", "Extract article", "url" to "{{input.text}}", "selector" to "article p", "attribute" to "text", x = 280f, y = 0f),
        node("l1", "net.llm", "Summarize", "prompt" to "Summarize in 5 bullets:\n{{input.items}}", "model" to "openai/gpt-4o-mini", x = 560f, y = 0f),
        node("n1", "local.notify", "Show summary", "title" to "Summary ready", "message" to "{{input.content}}", x = 840f, y = 0f),
    ), edges = listOf(edge("e1", "t1", "s1"), edge("e2", "s1", "l1"), edge("e3", "l1", "n1")))

    val batteryAlert = WorkflowGraph(id = "tpl-battery", name = "Battery Charge Alert", nodes = listOf(
        node("t1", "trigger.battery", "Charging watch", "minLevel" to "90", "requireCharging" to "true", x = 0f, y = 0f),
        node("i1", "logic.if", "Charged enough?", "left" to "{{input.level}}", "operator" to ">=", "right" to "90", x = 280f, y = 0f),
        node("n1", "local.notify", "Unplug alert", "title" to "Battery 90%", "message" to "Unplug the charger to protect battery health.", x = 560f, y = 0f),
    ), edges = listOf(edge("e1", "t1", "i1"), Edge("e2", "i1", "true", "n1", "in")))

    val csvLogger = WorkflowGraph(id = "tpl-csv", name = "Quick CSV Logger", nodes = listOf(
        node("t1", "trigger.manual", "Manual", x = 0f, y = 0f),
        node("f1", "data.template", "Format row", "template" to "{{input.date}},{{input.label}},{{input.amount}}", "csv" to "false", x = 280f, y = 0f),
        node("w1", "local.file_write", "Append to CSV", "uri" to "{{input.csvUri}}", "content" to "{{input.text}}\n", "append" to "true", x = 560f, y = 0f),
    ), edges = listOf(edge("e1", "t1", "f1"), edge("e2", "f1", "w1")))

    val ALL: List<WorkflowGraph> = listOf(priceChecker, ocrToNotes, articleSummarizer, batteryAlert, csvLogger)
}
