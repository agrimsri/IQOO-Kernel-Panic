package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Static contract for the assistant shortcuts and the wake-word service; parsed from the source XML. */
class HeyTraceVeilManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun parse(relative: String): org.w3c.dom.Document {
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun elements(doc: org.w3c.dom.Document, tag: String): List<Element> =
        doc.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.a(name: String): String = getAttributeNS(ns, name)
    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private val manifest = parse("src/main/AndroidManifest.xml")
    private val shortcuts = parse("src/main/res/xml/shortcuts.xml")

    @Test fun `the wake service is private, microphone-typed and has its permission`() {
        val permissions = elements(manifest, "uses-permission").map { it.a("name") }.toSet()
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in permissions)
        val service = elements(manifest, "service").single { it.a("name") == ".voice.wake.HeyTraceVeilService" }
        assertEquals("false", service.a("exported"))
        assertEquals("microphone", service.a("foregroundServiceType"))
        assertTrue("no other app may start it through an intent filter", service.children("intent-filter").isEmpty())
    }

    @Test fun `google stays the default assistant and no covert or full-screen route is requested`() {
        val permissions = elements(manifest, "uses-permission").map { it.a("name") }.toSet()
        listOf(
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.CAPTURE_AUDIO_HOTWORD",
            "android.permission.BIND_VOICE_INTERACTION",
            "android.permission.MANAGE_VOICE_KEYPHRASES",
        ).forEach { assertFalse("$it must not be requested", it in permissions) }
        val actions = elements(manifest, "action").map { it.a("name") }.toSet()
        listOf("android.intent.action.ASSIST", "android.intent.action.VOICE_COMMAND", "android.service.voice.VoiceInteractionService")
            .forEach { assertFalse("$it would compete with the default assistant", it in actions) }
    }

    @Test fun `the launcher activity publishes the static shortcuts`() {
        val main = elements(manifest, "activity").single { it.a("name") == ".MainActivity" }
        val meta = main.children("meta-data").singleOrNull { it.a("name") == "android.app.shortcuts" }
        assertNotNull(meta)
        assertEquals("@xml/shortcuts", meta!!.a("resource"))
    }

    @Test fun `the open-app-feature capability sends the feature to MainActivity`() {
        val capability = elements(shortcuts, "capability").single { it.a("name") == "actions.intent.OPEN_APP_FEATURE" }
        val intent = capability.children("intent").single()
        assertEquals("com.dpdpxray.app.MainActivity", intent.a("targetClass"))
        assertEquals("com.dpdpxray.app", intent.a("targetPackage"))
        val parameter = intent.children("parameter").single()
        assertEquals("feature", parameter.a("name"))
        assertEquals(AssistantFeatures.EXTRA_FEATURE, parameter.a("key"))
    }

    @Test fun `every static shortcut opens a feature the app understands`() {
        val list = elements(shortcuts, "shortcut")
        assertEquals(setOf("check_app", "check_apk", "ask_last_check"), list.map { it.a("shortcutId") }.toSet())
        list.forEach { s ->
            val id = s.a("shortcutId")
            val intent = s.children("intent").single()
            assertEquals("com.dpdpxray.app.MainActivity", intent.a("targetClass"))
            val extra = intent.children("extra").single { it.a("name") == AssistantFeatures.EXTRA_FEATURE }
            assertEquals(id, extra.a("value"))
            assertNotNull("$id must map to a feature", AssistantFeatures.parse(id))
            val binding = s.children("capability-binding").single()
            assertEquals("actions.intent.OPEN_APP_FEATURE", binding.a("key"))
            assertTrue(s.a("shortcutShortLabel").startsWith("@string/"))
        }
    }
}
