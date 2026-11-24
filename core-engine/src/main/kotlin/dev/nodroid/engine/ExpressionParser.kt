package dev.nodroid.engine

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Recursive-descent parser/evaluator for templates such as:
 *   {{user.name | trim | upper}}
 *   {{items[0].price ?? "0" | number}}
 *   {{text | regexExtract("[0-9]+")}}
 *   {{ts | dateFormat("yyyy-MM-dd")}}
 * Supported filters: trim, lower, upper, number, round, jsonStringify, jsonParse,
 * base64Encode, base64Decode, urlEncode, regexExtract, dateFormat.
 * A template with no {{ }} is returned unchanged. Unknown filters throw [ExpressionException].
 */
class ExpressionException(message: String) : IllegalArgumentException(message)

sealed interface Expr {
    data class Literal(val value: Any?) : Expr
    data class Path(val root: String, val segments: List<Segment>) : Expr
    data class Default(val left: Expr, val fallback: Expr) : Expr
    data class Filter(val inner: Expr, val name: String, val args: List<Any?>) : Expr
    sealed interface Segment {
        data class Field(val name: String) : Segment
        data class Index(val index: Int) : Segment
        data class Key(val key: String) : Segment
    }
}

object ExpressionParser {
    private val templateRegex = Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL)

    /** Renders a whole template string against [context] (nodeId -> node output map). */
    fun render(template: String, context: Map<String, Any?>): String {
        if (!template.contains("{{")) return template
        return templateRegex.replace(template) { match ->
            val value = evaluate(match.groupValues[1].trim(), context)
            stringify(value)
        }
    }

    fun evaluate(expression: String, context: Map<String, Any?>): Any? {
        val parser = Parser(Lexer(expression).tokenize())
        val expr = parser.parseExpression()
        parser.expectEnd()
        return eval(expr, context)
    }

    fun stringify(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> Json.encodeToString(JsonElement.serializer(), anyToJson(value))
        is List<*> -> Json.encodeToString(JsonElement.serializer(), anyToJson(value))
        else -> value.toString()
    }

    fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            for ((k, v) in value) put(k.toString(), anyToJson(v))
        }
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { anyToJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    fun jsonToAny(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            element.content.contains('.') -> element.content.toDoubleOrNull() ?: element.content
            else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
        }
        is kotlinx.serialization.json.JsonObject -> element.jsonObject.mapValues { jsonToAny(it.value) }
        is kotlinx.serialization.json.JsonArray -> element.map { jsonToAny(it) }
    }

    private fun eval(expr: Expr, context: Map<String, Any?>): Any? = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.Path -> resolvePath(expr, context)
        is Expr.Default -> {
            val left = eval(expr.left, context)
            if (left == null || (left is String && left.isEmpty())) eval(expr.fallback, context) else left
        }
        is Expr.Filter -> applyFilter(expr.name, eval(expr.inner, context), expr.args)
    }

    private fun resolvePath(expr: Expr.Path, context: Map<String, Any?>): Any? {
        var current: Any? = context[expr.root] ?: return null
        for (segment in expr.segments) {
            current = when (segment) {
                is Expr.Segment.Field -> (current as? Map<*, *>)?.get(segment.name)
                is Expr.Segment.Key -> (current as? Map<*, *>)?.get(segment.key)
                is Expr.Segment.Index -> when (current) {
                    is List<*> -> current.getOrNull(segment.index)
                    is String -> current.getOrNull(segment.index)?.toString()
                    else -> null
                }
            } ?: return null
        }
        return current
    }

    private fun applyFilter(name: String, value: Any?, args: List<Any?>): Any? {
        return when (name) {
            "trim" -> stringify(value).trim()
            "lower" -> stringify(value).lowercase()
            "upper" -> stringify(value).uppercase()
            "number" -> stringify(value).toDoubleOrNull()
                ?: throw ExpressionException("number filter: '" + stringify(value) + "' is not a number")
            "round" -> {
                val digits = (args.firstOrNull() as? Number)?.toInt() ?: 0
                val num = stringify(value).toDoubleOrNull()
                    ?: throw ExpressionException("round filter: value is not a number")
                val factor = Math.pow(10.0, digits.toDouble())
                Math.round(num * factor) / factor
            }
            "jsonStringify" -> Json.encodeToString(JsonElement.serializer(), anyToJson(value))
            "jsonParse" -> try {
                jsonToAny(Json.parseToJsonElement(stringify(value)))
            } catch (e: Exception) {
                throw ExpressionException("jsonParse failed: " + e.message)
            }
            "base64Encode" -> Base64.getEncoder().encodeToString(stringify(value).toByteArray(Charsets.UTF_8))
            "base64Decode" -> try {
                String(Base64.getDecoder().decode(stringify(value)), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw ExpressionException("base64Decode failed: invalid base64")
            }
            "urlEncode" -> URLEncoder.encode(stringify(value), Charsets.UTF_8)
            "regexExtract" -> {
                val pattern = args.firstOrNull()?.toString()
                    ?: throw ExpressionException("regexExtract needs a pattern argument")
                val match = Regex(pattern).find(stringify(value)) ?: return null
                if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) match.groupValues[1] else match.value
            }
            "dateFormat" -> {
                val pattern = args.firstOrNull()?.toString() ?: "yyyy-MM-dd HH:mm:ss"
                val instant = when (value) {
                    is Number -> Instant.ofEpochMilli(value.toLong())
                    is String -> value.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
                        ?: try { Instant.parse(value) } catch (e: Exception) { null }
                    else -> null
                } ?: throw ExpressionException("dateFormat: value is not a timestamp")
                DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(instant)
            }
            else -> throw ExpressionException("Unknown filter: " + name)
        }
    }

    // ---- Lexer ----
    private enum class TokKind { IDENT, NUMBER, STRING, DOT, LBRACKET, RBRACKET, PIPE, QUESTION2, LPAREN, RPAREN, COMMA, EOF }
    private data class Token(val kind: TokKind, val text: String)

    private class Lexer(private val input: String) {
        fun tokenize(): List<Token> {
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < input.length) {
                val c = input[i]
                when {
                    c.isWhitespace() -> i++
                    c == '.' -> { tokens.add(Token(TokKind.DOT, ".")); i++ }
                    c == '[' -> { tokens.add(Token(TokKind.LBRACKET, "[")); i++ }
                    c == ']' -> { tokens.add(Token(TokKind.RBRACKET, "]")); i++ }
                    c == '|' -> { tokens.add(Token(TokKind.PIPE, "|")); i++ }
                    c == '(' -> { tokens.add(Token(TokKind.LPAREN, "(")); i++ }
                    c == ')' -> { tokens.add(Token(TokKind.RPAREN, ")")); i++ }
                    c == ',' -> { tokens.add(Token(TokKind.COMMA, ",")); i++ }
                    c == '?' && i + 1 < input.length && input[i + 1] == '?' -> { tokens.add(Token(TokKind.QUESTION2, "??")); i += 2 }
                    c == '"' || c == '\'' -> {
                        val quote = c
                        val sb = StringBuilder()
                        i++
                        while (i < input.length && input[i] != quote) {
                            if (input[i] == '\\' && i + 1 < input.length) { sb.append(input[i + 1]); i += 2 } else { sb.append(input[i]); i++ }
                        }
                        if (i >= input.length) throw ExpressionException("Unterminated string literal")
                        i++
                        tokens.add(Token(TokKind.STRING, sb.toString()))
                    }
                    c.isDigit() || (c == '-' && i + 1 < input.length && input[i + 1].isDigit()) -> {
                        val start = i
                        if (input[i] == '-') i++
                        while (i < input.length && (input[i].isDigit() || input[i] == '.')) i++
                        tokens.add(Token(TokKind.NUMBER, input.substring(start, i)))
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                        tokens.add(Token(TokKind.IDENT, input.substring(start, i)))
                    }
                    else -> throw ExpressionException("Unexpected character: '" + c + "'")
                }
            }
            tokens.add(Token(TokKind.EOF, ""))
            return tokens
        }
    }

    // ---- Parser: expr := primary ('??' primary)? ('|' filter)* ; filter := IDENT ('(' args ')')? ----
    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        private fun peek(): Token = tokens[pos]
        private fun next(): Token = tokens[pos++]
        private fun expect(kind: TokKind): Token {
            val t = next()
            if (t.kind != kind) throw ExpressionException("Expected " + kind + " but got '" + t.text + "'")
            return t
        }
        fun expectEnd() {
            if (peek().kind != TokKind.EOF) throw ExpressionException("Unexpected trailing token '" + peek().text + "'")
        }
        fun parseExpression(): Expr {
            var expr = parsePrimary()
            if (peek().kind == TokKind.QUESTION2) {
                next()
                expr = Expr.Default(expr, parsePrimary())
            }
            while (peek().kind == TokKind.PIPE) {
                next()
                val name = expect(TokKind.IDENT).text
                val args = mutableListOf<Any?>()
                if (peek().kind == TokKind.LPAREN) {
                    next()
                    if (peek().kind != TokKind.RPAREN) {
                        args.add(parseLiteralArg())
                        while (peek().kind == TokKind.COMMA) { next(); args.add(parseLiteralArg()) }
                    }
                    expect(TokKind.RPAREN)
                }
                expr = Expr.Filter(expr, name, args)
            }
            return expr
        }
        private fun parseLiteralArg(): Any? {
            val t = next()
            return when (t.kind) {
                TokKind.STRING -> t.text
                TokKind.NUMBER -> t.text.toDoubleOrNull() ?: t.text.toLongOrNull() ?: t.text
                TokKind.IDENT -> when (t.text) { "true" -> true; "false" -> false; "null" -> null; else -> t.text }
                else -> throw ExpressionException("Expected a literal argument, got '" + t.text + "'")
            }
        }
        private fun parsePrimary(): Expr {
            val t = next()
            return when (t.kind) {
                TokKind.STRING -> Expr.Literal(t.text)
                TokKind.NUMBER -> Expr.Literal(t.text.toDoubleOrNull() ?: t.text.toLongOrNull())
                TokKind.IDENT -> when (t.text) {
                    "true" -> Expr.Literal(true)
                    "false" -> Expr.Literal(false)
                    "null" -> Expr.Literal(null)
                    else -> {
                        val segments = mutableListOf<Expr.Segment>()
                        while (peek().kind == TokKind.DOT || peek().kind == TokKind.LBRACKET) {
                            if (peek().kind == TokKind.DOT) {
                                next()
                                segments.add(Expr.Segment.Field(expect(TokKind.IDENT).text))
                            } else {
                                next()
                                val inner = next()
                                when (inner.kind) {
                                    TokKind.NUMBER -> segments.add(Expr.Segment.Index(inner.text.toInt()))
                                    TokKind.STRING -> segments.add(Expr.Segment.Key(inner.text))
                                    else -> throw ExpressionException("Expected index or key inside [ ]")
                                }
                                expect(TokKind.RBRACKET)
                            }
                        }
                        Expr.Path(t.text, segments)
                    }
                }
                else -> throw ExpressionException("Unexpected token '" + t.text + "'")
            }
        }
    }
}
