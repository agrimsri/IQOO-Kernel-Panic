package com.dpdpxray.core.assistant

import com.dpdpxray.core.copilot.CopilotQuestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {
    private fun cmd(text: String) = VoiceCommandParser.parse(text).command
    private fun hindi(text: String) = VoiceCommandParser.parse(text).hindi

    @Test fun `check an app in English with different verbs`() {
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("check Instagram"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("Audit Instagram."))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("scan instagram please"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("test the Instagram app"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("can you run an audit of Instagram"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("run a check on instagram now"))
        assertEquals(VoiceCommand.CheckApp("google play store"), cmd("check Google Play Store"))
        assertEquals(VoiceCommand.CheckApp("leaky shop"), cmd("check leaky shop"))
    }

    @Test fun `the wake phrase in front of a command is ignored`() {
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("Hey TraceVeil, check Instagram"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("hey trace veil check instagram"))
    }

    @Test fun `check an app in Hinglish and Devanagari`() {
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("Instagram ko check karo"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("instagram check kar do"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("Instagram की जाँच करो"))
        assertEquals(VoiceCommand.CheckApp("instagram"), cmd("Instagram की जांच कीजिए"))
        assertEquals(VoiceCommand.CheckApp("इंस्टाग्राम"), cmd("इंस्टाग्राम की जाँच करो"))
        assertEquals(VoiceCommand.CheckApp("व्हाट्सऐप"), cmd("व्हाट्सऐप को चेक करो"))
        assertTrue(hindi("Instagram ko check karo"))
        assertTrue(hindi("इंस्टाग्राम की जाँच करो"))
        assertFalse(hindi("check Instagram"))
    }

    @Test fun `a check without an app name asks which app`() {
        assertEquals(VoiceCommand.CheckApp(null), cmd("check an app"))
        assertEquals(VoiceCommand.CheckApp(null), cmd("audit"))
        assertEquals(VoiceCommand.CheckApp(null), cmd("ek app check karo"))
    }

    @Test fun `check a file or an apk`() {
        listOf("check a file", "check this APK", "scan an apk file", "APK check karo", "फ़ाइल की जाँच करो", "check apk").forEach {
            assertEquals(it, VoiceCommand.CheckFile, cmd(it))
        }
    }

    @Test fun `phone checkup`() {
        listOf(
            "check my phone", "phone checkup", "check my apps", "check all my apps", "scan my phone", "check up my phone",
            "mera phone check karo", "मेरे फ़ोन की जाँच करो", "phone ki jaanch karo",
        ).forEach { assertEquals(it, VoiceCommand.PhoneCheckup, cmd(it)) }
    }

    @Test fun `last result`() {
        listOf(
            "what did you find", "What did you find?", "read my last result", "last result", "read the latest report",
            "check my last result", "what was the result", "kya mila", "result batao", "पिछला नतीजा बताओ", "summary",
        ).forEach { assertEquals(it, VoiceCommand.LastResult, cmd(it)) }
    }

    @Test fun `open screens`() {
        assertEquals(VoiceCommand.Open(OpenTarget.REPORTS), cmd("open reports"))
        assertEquals(VoiceCommand.Open(OpenTarget.REPORTS), cmd("show my reports"))
        assertEquals(VoiceCommand.Open(OpenTarget.SETTINGS), cmd("open settings"))
        assertEquals(VoiceCommand.Open(OpenTarget.SETTINGS), cmd("settings kholo"))
        assertEquals(VoiceCommand.Open(OpenTarget.SETTINGS), cmd("सेटिंग्स खोलो"))
        assertEquals(VoiceCommand.Open(OpenTarget.HOME), cmd("go home"))
        assertEquals(VoiceCommand.Open(OpenTarget.HOME), cmd("take me to the home screen"))
        assertEquals(VoiceCommand.Open(OpenTarget.TRUST), cmd("open the trust map"))
        assertEquals(VoiceCommand.Open(OpenTarget.REPORTS), cmd("reports"))
        assertEquals(VoiceCommand.Open(null), cmd("open instagram"))
    }

    @Test fun `help`() {
        listOf("what can you do", "What can you do?", "help", "how do I use this", "tum kya kar sakte ho", "आप क्या कर सकते हैं", "madad").forEach {
            assertEquals(it, VoiceCommand.Help, cmd(it))
        }
    }

    @Test fun `stop listening`() {
        listOf("stop listening", "stop", "go to sleep", "be quiet", "sunna band karo", "band karo", "बंद करो", "सुनना बंद करो").forEach {
            assertEquals(it, VoiceCommand.Stop, cmd(it))
        }
    }

    @Test fun `every copilot chip stays a question, in both languages`() {
        CopilotQuestions.chips.forEach { q ->
            assertEquals(q.en, VoiceCommand.Question(q.en), cmd(q.en))
            assertEquals(q.hi, VoiceCommand.Question(q.hi), cmd(q.hi))
        }
    }

    @Test fun `questions about the audit are not mistaken for commands`() {
        assertEquals(VoiceCommand.Question("check the score"), cmd("check the score"))
        assertEquals(VoiceCommand.Question("which trackers did it contact"), cmd("which trackers did it contact"))
        assertEquals(VoiceCommand.Question("is this app safe for children"), cmd("is this app safe for children"))
        assertEquals(VoiceCommand.Question("what did you find before consent"), cmd("what did you find before consent"))
    }

    @Test fun `unknown speech becomes a question for the grounded copilot, empty speech is empty`() {
        assertEquals(VoiceCommand.Question("tell me a joke"), cmd("tell me a joke"))
        assertEquals(VoiceCommand.Empty, cmd(""))
        assertEquals(VoiceCommand.Empty, cmd("   ...  "))
        assertEquals(VoiceCommand.Empty, cmd("hey traceveil"))
    }
}
