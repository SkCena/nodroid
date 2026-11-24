package dev.nodroid.nodes

/** Minimal JSONPath evaluator for paths like `$.user.name`, `$.items[0].price`, `$.a["b c"]`. */
object JsonPath {
    fun eval(data: Any?, path: String): Any? {
        var current: Any? = data
        val trimmed = path.trim()
        require(trimmed.startsWith("$")) { "JSONPath must start with \$" }
        var rest = trimmed.removePrefix("$")
        if (rest.startsWith(".")) rest = rest.substring(1)
        if (rest.isEmpty()) return current
        for (step in parseSteps(rest)) {
            current = when (step) {
                is Step.Field -> (current as? Map<*, *>)?.get(step.name)
                is Step.Key -> (current as? Map<*, *>)?.get(step.key)
                is Step.Index -> when (current) {
                    is List<*> -> current.getOrNull(step.index)
                    is String -> current.getOrNull(step.index)?.toString()
                    else -> null
                }
            } ?: return null
        }
        return current
    }

    private sealed interface Step {
        data class Field(val name: String) : Step
        data class Key(val key: String) : Step
        data class Index(val index: Int) : Step
    }

    /** Tokenises `user.tags[0]`, `a["b c"]`, `items[2].price` into Field/Key/Index steps. */
    private fun parseSteps(rest: String): List<Step> {
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < rest.length) {
            when {
                rest[i] == '.' -> i++
                rest[i] == '[' -> {
                    val close = rest.indexOf(']', i)
                    require(close > i) { "Unclosed [ in JSONPath" }
                    val inner = rest.substring(i + 1, close).trim()
                    when {
                        inner.startsWith("\"") && inner.endsWith("\"") -> steps.add(Step.Key(inner.trim('"')))
                        inner.startsWith("'") && inner.endsWith("'") -> steps.add(Step.Key(inner.trim('\'')))
                        else -> steps.add(Step.Index(inner.toIntOrNull() ?: throw IllegalArgumentException("Bad index: " + inner)))
                    }
                    i = close + 1
                }
                else -> {
                    val start = i
                    while (i < rest.length && rest[i] != '.' && rest[i] != '[') i++
                    steps.add(Step.Field(rest.substring(start, i)))
                }
            }
        }
        return steps
    }
}
