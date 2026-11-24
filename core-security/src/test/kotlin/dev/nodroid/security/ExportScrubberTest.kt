package dev.nodroid.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportScrubberTest {
    @Test fun `detects vault reference`() {
        assertTrue(ExportScrubber.containsVaultReference("Bearer {{vault.OPENAI_KEY}}"))
        assertFalse(ExportScrubber.containsVaultReference("Bearer abc"))
    }
    @Test fun `scrubs secret keys from json`() {
        val out = ExportScrubber.scrubJson("{\"name\":\"x\",\"apiKey\":\"sekret\",\"url\":\"https://example.com\"}")
        assertFalse(out.contains("sekret"))
        assertFalse(out.contains("apiKey"))
        assertTrue(out.contains("example.com"))
    }
    @Test fun `scrubs vault placeholder values`() {
        val out = ExportScrubber.scrubJson("{\"auth\":\"Bearer {{vault.KEY}}\",\"name\":\"ok\"}")
        assertFalse(out.contains("vault"))
        assertTrue(out.contains("ok"))
    }
    @Test fun `scrubs nested objects and arrays`() {
        val out = ExportScrubber.scrubJson("{\"nodes\":[{\"password\":\"p\",\"label\":\"n\"}]}")
        assertFalse(out.contains("password"))
        assertTrue(out.contains("n"))
    }
    @Test fun `invalid json returned unchanged`() {
        assertEquals("not json", ExportScrubber.scrubJson("not json"))
    }
    @Test fun `clean json unchanged`() {
        val input = "{\"a\":1,\"b\":\"hello\"}"
        assertEquals(input, ExportScrubber.scrubJson(input))
    }
}
