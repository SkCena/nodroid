package dev.nodroid.nodes

import dev.nodroid.model.ConfigFieldSchema
import dev.nodroid.model.ConfigFieldSchema.FieldKind
import dev.nodroid.model.NodeDescriptor
import dev.nodroid.model.NodeDescriptor.NodeCategory
import dev.nodroid.model.Port
import dev.nodroid.model.PortType

/** Every safe node type, its ports and its dynamic config form. No privileged node is listed here. */
object NodeDescriptors {
    private fun f(key: String, label: String, kind: FieldKind, required: Boolean = false, default: String = "", options: List<String> = emptyList(), hint: String = "") =
        ConfigFieldSchema(key, label, kind, required, default, options, hint)
    private fun d(type: String, name: String, cat: NodeCategory, desc: String, trigger: Boolean = false,
                  inputs: List<Port> = listOf(Port("in", "Input")), outputs: List<Port> = listOf(Port("out", "Output")),
                  fields: List<ConfigFieldSchema> = emptyList()) =
        NodeDescriptor(type, name, cat, desc, inputs, outputs, fields, trigger)

    val ALL: List<NodeDescriptor> = listOf(
        d("trigger.manual", "Manual Run", NodeCategory.TRIGGER, "Run the workflow by tapping Run.", trigger = true, inputs = emptyList()),
        d("trigger.tile", "Quick Settings Tile", NodeCategory.TRIGGER, "Run from a Quick Settings tile.", trigger = true, inputs = emptyList()),
        d("trigger.schedule", "Time Schedule", NodeCategory.TRIGGER, "Run on a WorkManager schedule.", trigger = true, inputs = emptyList(),
            fields = listOf(f("intervalMinutes", "Interval (minutes)", FieldKind.NUMBER, true, "60"), f("flexMinutes", "Flex (minutes)", FieldKind.NUMBER, false, "10"))),
        d("trigger.battery", "Battery / Charging", NodeCategory.TRIGGER, "Run when charging state or battery level changes.", trigger = true, inputs = emptyList(),
            fields = listOf(f("minLevel", "Min battery %", FieldKind.NUMBER, false, "20"), f("requireCharging", "Only while charging", FieldKind.BOOLEAN, false, "false"))),
        d("trigger.connectivity", "Connectivity Change", NodeCategory.TRIGGER, "Run when network connectivity changes.", trigger = true, inputs = emptyList(),
            fields = listOf(f("requireWifi", "Only on Wi-Fi", FieldKind.BOOLEAN, false, "false"))),
        d("trigger.share", "Share Sheet", NodeCategory.TRIGGER, "Run when text, a URL or an image is shared to NodRoid.", trigger = true, inputs = emptyList()),
        d("trigger.file_watch", "File Watcher", NodeCategory.TRIGGER, "Run when a file appears in a watched SAF folder.", trigger = true, inputs = emptyList(),
            fields = listOf(f("folderUri", "Folder URI", FieldKind.FILE, true), f("extension", "File extension", FieldKind.TEXT, false, ""))),
        d("logic.if", "If / Else", NodeCategory.LOGIC, "Route to the true or false port by comparing a value.",
            outputs = listOf(Port("true", "True"), Port("false", "False")),
            fields = listOf(f("left", "Left value (template)", FieldKind.TEXT, true, "{{input.value}}"), f("operator", "Operator", FieldKind.SELECT, true, "==", listOf("==", "!=", ">", "<", ">=", "<=", "contains", "isEmpty")), f("right", "Right value", FieldKind.TEXT, false, ""))),
        d("logic.switch", "Switch", NodeCategory.LOGIC, "Route to one of several ports by matching a value.",
            outputs = listOf(Port("case0", "Case 1"), Port("case1", "Case 2"), Port("case2", "Case 3"), Port("default", "Default")),
            fields = listOf(f("value", "Value (template)", FieldKind.TEXT, true, "{{input.value}}"), f("case0", "Case 1", FieldKind.TEXT, false, ""), f("case1", "Case 2", FieldKind.TEXT, false, ""), f("case2", "Case 3", FieldKind.TEXT, false, ""))),
        d("logic.merge", "Merge", NodeCategory.LOGIC, "Combine upstream branches.",
            inputs = listOf(Port("in1", "Input 1"), Port("in2", "Input 2")),
            fields = listOf(f("mode", "Mode", FieldKind.SELECT, true, "wait_all", listOf("wait_all", "pass_first")))),
        d("logic.foreach", "ForEach Batcher", NodeCategory.LOGIC, "Split a list into batches (capped).",
            fields = listOf(f("items", "Items (template)", FieldKind.TEXT, true, "{{input.items}}"), f("batchSize", "Batch size", FieldKind.NUMBER, false, "10"), f("maxIterations", "Max iterations", FieldKind.NUMBER, false, "100"))),
        d("logic.delay", "Delay", NodeCategory.LOGIC, "Pause the run (coroutine delay, max 5 min; longer uses WorkManager).",
            fields = listOf(f("milliseconds", "Delay (ms)", FieldKind.NUMBER, true, "1000"), f("durable", "Durable (WorkManager)", FieldKind.BOOLEAN, false, "false"))),
        d("flow.error_catch", "Error Catcher", NodeCategory.LOGIC, "Catch a failure from the previous node and continue.",
            fields = listOf(f("fallbackValue", "Fallback value", FieldKind.TEXT, false, ""))),
        d("flow.subflow", "Sub-Workflow Call", NodeCategory.LOGIC, "Run another workflow (recursion depth cap 5).",
            fields = listOf(f("workflowId", "Workflow ID", FieldKind.TEXT, true))),
        d("data.regex", "Regex Extractor", NodeCategory.DATA, "Extract values with a regular expression.",
            fields = listOf(f("input", "Input text (template)", FieldKind.TEXT, true, "{{input.text}}"), f("preset", "Preset", FieldKind.SELECT, false, "custom", listOf("custom", "url", "email", "number")), f("pattern", "Pattern", FieldKind.TEXT, false, ""))),
        d("data.jsonpath", "JSON Path", NodeCategory.DATA, "Read a value from JSON with a JSONPath.",
            fields = listOf(f("input", "JSON (template)", FieldKind.TEXT, true, "{{input.json}}"), f("path", "JSONPath", FieldKind.TEXT, true, "$.data"))),
        d("data.html_scraper", "HTML Scraper", NodeCategory.DATA, "Extract text from HTML with a CSS selector (Jsoup).",
            fields = listOf(f("url", "Page URL (optional)", FieldKind.TEXT, false, ""), f("html", "HTML (optional)", FieldKind.MULTILINE_TEXT, false, ""), f("selector", "CSS selector", FieldKind.TEXT, true, "h1"), f("attribute", "Attribute (text/html/attr:name)", FieldKind.TEXT, false, "text"))),
        d("data.template", "Text / CSV Formatter", NodeCategory.DATA, "Render a template with {{expressions}}; CSV mode formats rows.",
            fields = listOf(f("template", "Template", FieldKind.MULTILINE_TEXT, true, "Hello {{input.name}}"), f("csv", "CSV mode", FieldKind.BOOLEAN, false, "false"), f("rows", "Rows (template)", FieldKind.TEXT, false, "{{input.rows}}"))),
        d("data.javascript", "JavaScript Runner", NodeCategory.DATA, "Run sandboxed QuickJS (16 MB heap, 3 s timeout, no network/file access).",
            fields = listOf(f("code", "JavaScript", FieldKind.CODE, true, "input.value * 2"))),
        d("data.kv", "Persistent KV State", NodeCategory.DATA, "Get, set, increment or delete a persistent key.",
            fields = listOf(f("operation", "Operation", FieldKind.SELECT, true, "get", listOf("get", "set", "increment", "delete")), f("key", "Key", FieldKind.TEXT, true), f("value", "Value", FieldKind.TEXT, false, ""))),
        d("net.http", "HTTP Request", NodeCategory.NETWORK, "OkHttp REST call with retries.",
            fields = listOf(f("url", "URL", FieldKind.TEXT, true, "https://example.com"), f("method", "Method", FieldKind.SELECT, true, "GET", listOf("GET", "POST", "PUT", "DELETE")), f("headers", "Headers (JSON)", FieldKind.MULTILINE_TEXT, false, "{}"), f("body", "Body", FieldKind.MULTILINE_TEXT, false, ""), f("retries", "Retries", FieldKind.NUMBER, false, "2"))),
        d("net.webhook", "JSON Webhook", NodeCategory.NETWORK, "POST a JSON payload to a webhook URL.",
            fields = listOf(f("url", "Webhook URL", FieldKind.TEXT, true), f("payload", "Payload (template)", FieldKind.MULTILINE_TEXT, true, "{\"event\":\"nodroid\"}"))),
        d("net.llm", "LLM Chat", NodeCategory.NETWORK, "OpenAI-compatible chat (OpenRouter / Gemini / local Ollama). Key from the vault.",
            fields = listOf(f("baseUrl", "Base URL", FieldKind.TEXT, true, "https://openrouter.ai/api/v1"), f("apiKey", "API key ({{vault.KEY}})", FieldKind.SECRET, false, "{{vault.OPENAI_KEY}}"), f("model", "Model", FieldKind.TEXT, true, "openai/gpt-4o-mini"), f("prompt", "Prompt (template)", FieldKind.MULTILINE_TEXT, true, "{{input.text}}"), f("temperature", "Temperature", FieldKind.NUMBER, false, "0.7"))),
        d("ai.ocr", "Offline OCR", NodeCategory.AI, "On-device ML Kit text recognition (Latin + Devanagari).",
            fields = listOf(f("imageUri", "Image URI (template)", FieldKind.TEXT, true, "{{input.imageUri}}"), f("script", "Script", FieldKind.SELECT, true, "latin", listOf("latin", "devanagari")))),
        d("ai.image_resize", "Image Resizer", NodeCategory.AI, "Offline resize / compress an image.",
            fields = listOf(f("imageUri", "Image URI (template)", FieldKind.TEXT, true, "{{input.imageUri}}"), f("maxDimension", "Max dimension (px)", FieldKind.NUMBER, false, "1280"), f("quality", "JPEG quality", FieldKind.NUMBER, false, "85"), f("format", "Format", FieldKind.SELECT, false, "JPEG", listOf("JPEG", "PNG", "WEBP")))),
        d("local.notify", "User Alert", NodeCategory.LOCAL, "Show a local notification.",
            fields = listOf(f("title", "Title (template)", FieldKind.TEXT, true, "NodRoid"), f("message", "Message (template)", FieldKind.MULTILINE_TEXT, true, "{{input.text}}"))),
        d("local.file_read", "File Read", NodeCategory.LOCAL, "Read a SAF file as text.",
            fields = listOf(f("uri", "File URI (template)", FieldKind.TEXT, true, "{{input.fileUri}}"))),
        d("local.file_write", "File Write / Append", NodeCategory.LOCAL, "Write or append text to a SAF file.",
            fields = listOf(f("uri", "File URI (template)", FieldKind.TEXT, true, "{{input.fileUri}}"), f("content", "Content (template)", FieldKind.MULTILINE_TEXT, true, "{{input.text}}"), f("append", "Append", FieldKind.BOOLEAN, false, "false"))),
        d("local.clipboard", "Clipboard Copy", NodeCategory.LOCAL, "Copy text to the clipboard.",
            fields = listOf(f("text", "Text (template)", FieldKind.TEXT, true, "{{input.text}}"))),
        d("local.intent", "Intent / URL Opener", NodeCategory.LOCAL, "Open a URL or app intent.",
            fields = listOf(f("url", "URL (template)", FieldKind.TEXT, true, "https://example.com"))),
    )

    val BY_TYPE: Map<String, NodeDescriptor> = ALL.associateBy { it.type }
}
