package dev.nodroid.nodes

import android.content.Context
import dev.nodroid.engine.NodeExecutor
import okhttp3.OkHttpClient

/** Assembles every safe executor by node type. Triggers are passthrough executors — the actual
 *  firing (WorkManager, tile, share intent, file watcher) lives in :feature-runtime. */
object NodeRegistry {
    fun create(context: Context, kvStore: KvStore, httpClient: OkHttpClient = HttpSupport.sharedClient): Map<String, NodeExecutor> {
        val android = AndroidNodeExecutors(context)
        val pure = PureNodeExecutors
        return mapOf(
            "trigger.manual" to pure.triggerPassthrough,
            "trigger.tile" to pure.triggerPassthrough,
            "trigger.schedule" to pure.triggerPassthrough,
            "trigger.battery" to pure.triggerPassthrough,
            "trigger.connectivity" to pure.triggerPassthrough,
            "trigger.share" to pure.triggerPassthrough,
            "trigger.file_watch" to pure.triggerPassthrough,
            "logic.if" to pure.ifElse,
            "logic.switch" to pure.switch,
            "logic.merge" to pure.merge,
            "logic.foreach" to pure.forEach,
            "logic.delay" to pure.delayNode,
            "flow.error_catch" to pure.errorCatch,
            "flow.subflow" to pure.subflow,
            "data.regex" to pure.regexExtractor,
            "data.jsonpath" to pure.jsonPath,
            "data.html_scraper" to pure.htmlScraper,
            "data.template" to pure.templateFormatter,
            "data.javascript" to pure.javascript,
            "data.kv" to pure.kvExecutor(kvStore),
            "net.http" to pure.httpExecutor(httpClient),
            "net.webhook" to pure.webhookExecutor(httpClient),
            "net.llm" to pure.llmExecutor(httpClient),
            "ai.ocr" to android.ocr,
            "ai.image_resize" to android.imageResize,
            "local.notify" to android.notify,
            "local.file_read" to android.fileRead,
            "local.file_write" to android.fileWrite,
            "local.clipboard" to android.clipboard,
            "local.intent" to android.intentOpener,
        )
    }
}
