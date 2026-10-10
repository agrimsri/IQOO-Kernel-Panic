package com.dpdpxray.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Words APK Guard never uses about an app (shared with the model-card wording test). */
internal val APK_FORBIDDEN_EN = listOf("malicious", "malware", "safe app", "is safe", "app is safe", "fraudulent", "scam", "virus-free", "certified", "harmless", "dangerous app", "trojan")
internal val APK_FORBIDDEN_HI = listOf("सुरक्षित है", "सुरक्षित ऐप", "खतरनाक ऐप", "ख़तरनाक ऐप", "धोखेबाज़ ऐप", "नकली ऐप है")

class ApkRiskRulesTest {
    private val play = InstallOrigin(InstallSource.PLAY_STORE, "com.android.vending", "Google Play")
    private val file = InstallOrigin(InstallSource.FILE, "com.google.android.packageinstaller")
    private val apkFile = InstallOrigin(InstallSource.NOT_INSTALLED)
    private val preinstalled = InstallOrigin(InstallSource.PREINSTALLED)

    private fun facts(
        permissions: Set<String> = emptySet(),
        components: List<DeclaredComponent> = emptyList(),
        origin: InstallOrigin = play,
        label: String = "Notes",
        packageName: String = "org.example.notes",
        granted: Set<String>? = null,
        certs: List<String> = listOf("AB".repeat(32)),
        subjects: List<String> = listOf("CN=Example Ltd, O=Example"),
        launcher: Boolean? = true,
        targetSdk: Int? = 35,
        twin: InstalledTwin? = null,
    ) = ApkFacts(
        packageName = packageName, label = label, versionName = "1.0", versionCode = 1, targetSdk = targetSdk,
        permissions = permissions, grantedPermissions = granted, components = components, origin = origin,
        certSha256 = certs, signerSubjects = subjects, hasLauncherIcon = launcher, installedTwin = twin,
    )

    private fun p(name: String) = "android.permission.$name"
    private fun c(kind: ServiceKind, enabled: Boolean? = null) = DeclaredComponent(kind, "org.example.notes.${kind.name.lowercase()}", enabled)
    private fun ApkGuardReport.signal(id: String) = signals.singleOrNull { it.id == id }
    private fun ApkGuardReport.need(id: String): ApkSignal =
        signal(id) ?: throw AssertionError("expected signal $id in ${signals.map { it.id }}")

    private fun assertSignal(f: ApkFacts, id: String, severity: ApkSeverity, evidence: ApkEvidence = ApkEvidence.OBSERVED): ApkSignal {
        val s = ApkRiskRules.evaluate(f).need(id)
        assertEquals("$id severity", severity, s.severity)
        assertEquals("$id evidence", evidence, s.evidence)
        return s
    }

    // --- single capability signals -------------------------------------------------------------------------------

    @Test fun `reading or receiving SMS is a review carefully signal about OTPs`() {
        listOf("READ_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH").forEach { perm ->
            val s = assertSignal(facts(setOf(p(perm))), "sms_read", ApkSeverity.REVIEW_CAREFULLY)
            assertTrue(s.proof.contains(p(perm)))
            assertTrue(s.detail.en.contains("OTP"))
        }
    }

    @Test fun `sending SMS alone is worth knowing`() {
        val r = ApkRiskRules.evaluate(facts(setOf(p("SEND_SMS"))))
        assertEquals(ApkSeverity.WORTH_KNOWING, r.need("sms_send").severity)
        assertNull(r.signal("sms_read"))
    }

    @Test fun `call log access is worth knowing`() {
        assertSignal(facts(setOf(p("READ_CALL_LOG"))), "call_log", ApkSeverity.WORTH_KNOWING)
    }

    @Test fun `phone state alone is info but placing calls is worth knowing`() {
        assertSignal(facts(setOf(p("READ_PHONE_STATE"))), "phone", ApkSeverity.INFO)
        val calls = assertSignal(facts(setOf(p("READ_PHONE_STATE"), p("CALL_PHONE"))), "phone", ApkSeverity.WORTH_KNOWING)
        assertTrue(calls.proof.containsAll(listOf(p("READ_PHONE_STATE"), p("CALL_PHONE"))))
    }

    @Test fun `declared accessibility and notification listener services need careful review`() {
        assertSignal(facts(components = listOf(c(ServiceKind.ACCESSIBILITY))), "accessibility", ApkSeverity.REVIEW_CAREFULLY)
        assertSignal(facts(components = listOf(c(ServiceKind.NOTIFICATION_LISTENER))), "notification_listener", ApkSeverity.REVIEW_CAREFULLY)
    }

    @Test fun `vpn device admin keyboard and autofill services are reported`() {
        assertSignal(facts(components = listOf(c(ServiceKind.VPN))), "vpn", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(components = listOf(c(ServiceKind.DEVICE_ADMIN))), "device_admin", ApkSeverity.REVIEW_CAREFULLY)
        assertSignal(facts(components = listOf(c(ServiceKind.INPUT_METHOD))), "input_method", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(components = listOf(c(ServiceKind.AUTOFILL))), "autofill", ApkSeverity.WORTH_KNOWING)
    }

    @Test fun `special accesses and sensitive data permissions are reported`() {
        assertSignal(facts(setOf(p("SYSTEM_ALERT_WINDOW"))), "overlay", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("REQUEST_INSTALL_PACKAGES"))), "install_packages", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("READ_CONTACTS"))), "contacts", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("ACCESS_FINE_LOCATION"))), "precise_location", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("CAMERA"))), "camera_microphone", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("RECORD_AUDIO"))), "camera_microphone", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("MANAGE_EXTERNAL_STORAGE"))), "all_files", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("QUERY_ALL_PACKAGES"))), "query_all_packages", ApkSeverity.INFO)
    }

    @Test fun `approximate location alone is not reported as precise location`() {
        assertNull(ApkRiskRules.evaluate(facts(setOf(p("ACCESS_COARSE_LOCATION")))).signal("precise_location"))
    }

    @Test fun `background location is mentioned with precise location`() {
        val s = ApkRiskRules.evaluate(facts(setOf(p("ACCESS_FINE_LOCATION"), p("ACCESS_BACKGROUND_LOCATION")))).need("precise_location")
        assertTrue(s.detail.en.contains("not using it"))
        assertTrue(s.proof.contains(p("ACCESS_BACKGROUND_LOCATION")))
    }

    @Test fun `an app that asks for nothing sensitive gets no capability signals`() {
        val r = ApkRiskRules.evaluate(facts(setOf(p("INTERNET"), p("VIBRATE"))))
        assertTrue(r.signals.none { it.severity != ApkSeverity.INFO })
    }

    // --- current state --------------------------------------------------------------------------------------------

    @Test fun `granted runtime permission is shown as allowed now and a declined one as not allowed`() {
        val allowed = ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), granted = setOf(p("READ_SMS")))).need("sms_read")
        assertEquals("Allowed now", allowed.state?.en)
        val notAllowed = ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), granted = emptySet())).need("sms_read")
        assertEquals("Not allowed now", notAllowed.state?.en)
        assertNull(ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), granted = null)).need("sms_read").state)
    }

    @Test fun `service switched on is reported only when Android tells us`() {
        assertEquals("Switched on now", ApkRiskRules.evaluate(facts(components = listOf(c(ServiceKind.ACCESSIBILITY, true)))).need("accessibility").state?.en)
        assertEquals("Not switched on now", ApkRiskRules.evaluate(facts(components = listOf(c(ServiceKind.ACCESSIBILITY, false)))).need("accessibility").state?.en)
        assertNull(ApkRiskRules.evaluate(facts(components = listOf(c(ServiceKind.NOTIFICATION_LISTENER, null)))).need("notification_listener").state)
    }

    // --- install source ---------------------------------------------------------------------------------------------

    @Test fun `install source is described with an evidence label`() {
        assertSignal(facts(origin = play), "install_source", ApkSeverity.INFO)
        assertSignal(facts(origin = file), "install_source", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(origin = apkFile, launcher = null), "install_source", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(origin = preinstalled), "install_source", ApkSeverity.INFO)
        assertSignal(facts(origin = InstallOrigin(InstallSource.UNKNOWN)), "install_source", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED)
        val other = assertSignal(facts(origin = InstallOrigin(InstallSource.OTHER_APP, "com.dropper.example")), "install_source", ApkSeverity.WORTH_KNOWING)
        assertTrue(other.title.en.contains("com.dropper.example"))
        val store = assertSignal(facts(origin = InstallOrigin(InstallSource.OTHER_STORE, "com.sec.android.app.samsungapps", "Samsung Galaxy Store")), "install_source", ApkSeverity.INFO)
        assertTrue(store.title.en.contains("Samsung Galaxy Store"))
    }

    @Test fun `google play is never presented as a guarantee`() {
        val s = ApkRiskRules.evaluate(facts(origin = play)).need("install_source")
        assertTrue(s.detail.en.contains("not a guarantee"))
    }

    // --- combinations -----------------------------------------------------------------------------------------------

    @Test fun `SMS plus accessibility from outside Play is a combination to review carefully`() {
        val f = facts(setOf(p("READ_SMS")), listOf(c(ServiceKind.ACCESSIBILITY)), origin = file)
        val s = assertSignal(f, "combo_otp_screen_control", ApkSeverity.REVIEW_CAREFULLY)
        assertTrue(s.proof.containsAll(listOf(p("READ_SMS"), "Accessibility service: org.example.notes.accessibility")))
        assertTrue(s.detail.en.contains("does not prove"))
    }

    @Test fun `the same combination from Play or the system image is not flagged as a combination`() {
        listOf(play, preinstalled).forEach { origin ->
            val r = ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), listOf(c(ServiceKind.ACCESSIBILITY)), origin = origin))
            assertNull(r.signal("combo_otp_screen_control"))
            assertNotNull(r.signal("sms_read"))
            assertNotNull(r.signal("accessibility"))
        }
    }

    @Test fun `notification listener plus overlay in an APK file is the same OTP combination`() {
        val f = facts(setOf(p("SYSTEM_ALERT_WINDOW")), listOf(c(ServiceKind.NOTIFICATION_LISTENER)), origin = apkFile, launcher = null)
        assertSignal(f, "combo_otp_screen_control", ApkSeverity.REVIEW_CAREFULLY)
    }

    @Test fun `SMS plus notification listener outside Play is flagged`() {
        val f = facts(setOf(p("RECEIVE_SMS")), listOf(c(ServiceKind.NOTIFICATION_LISTENER)), origin = file)
        assertSignal(f, "combo_sms_and_notifications", ApkSeverity.REVIEW_CAREFULLY)
        assertNull(ApkRiskRules.evaluate(f.copy(origin = play)).signal("combo_sms_and_notifications"))
    }

    @Test fun `installing other apps plus device admin or accessibility outside Play is flagged`() {
        assertSignal(facts(setOf(p("REQUEST_INSTALL_PACKAGES")), listOf(c(ServiceKind.DEVICE_ADMIN)), origin = file), "combo_installer_control", ApkSeverity.REVIEW_CAREFULLY)
        assertSignal(facts(setOf(p("REQUEST_INSTALL_PACKAGES")), listOf(c(ServiceKind.ACCESSIBILITY)), origin = apkFile), "combo_installer_control", ApkSeverity.REVIEW_CAREFULLY)
        assertNull(ApkRiskRules.evaluate(facts(setOf(p("REQUEST_INSTALL_PACKAGES")), listOf(c(ServiceKind.DEVICE_ADMIN)))).signal("combo_installer_control"))
    }

    @Test fun `contacts plus all files outside Play is worth knowing`() {
        assertSignal(facts(setOf(p("READ_CONTACTS"), p("MANAGE_EXTERNAL_STORAGE")), origin = file), "combo_contacts_files", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(setOf(p("READ_CONTACTS"), p("READ_MEDIA_IMAGES")), origin = file), "combo_contacts_files", ApkSeverity.WORTH_KNOWING)
        assertNull(ApkRiskRules.evaluate(facts(setOf(p("READ_CONTACTS"), p("MANAGE_EXTERNAL_STORAGE")))).signal("combo_contacts_files"))
    }

    // --- name resemblance -------------------------------------------------------------------------------------------

    @Test fun `a bank-like name outside Play is a weak inferred signal with careful wording`() {
        val s = assertSignal(facts(label = "SBI YONO KYC Update", packageName = "com.kyc.update", origin = file),
            "name_resembles_service", ApkSeverity.WORTH_KNOWING, ApkEvidence.INFERRED)
        assertTrue(s.title.en.contains("bank"))
        assertTrue(s.advice.en.contains("official website"))
        assertTrue(s.detail.en.contains("cannot tell whether this is the official app"))
    }

    @Test fun `a government-like name in an APK file is flagged as a government service`() {
        val s = assertSignal(facts(label = "RTO e-Challan", packageName = "com.challan.pay", origin = apkFile, launcher = null),
            "name_resembles_service", ApkSeverity.WORTH_KNOWING, ApkEvidence.INFERRED)
        assertTrue(s.title.en.contains("government"))
    }

    @Test fun `the same name installed from Play gets no name signal`() {
        assertNull(ApkRiskRules.evaluate(facts(label = "SBI YONO", packageName = "com.sbi.lotusintouch", origin = play)).signal("name_resembles_service"))
    }

    // --- identity ---------------------------------------------------------------------------------------------------

    @Test fun `official identity and actual behaviour are always not established`() {
        val r = ApkRiskRules.evaluate(facts())
        assertEquals(ApkEvidence.NOT_ESTABLISHED, r.need("official_identity").evidence)
        assertEquals(ApkEvidence.NOT_ESTABLISHED, r.need("behaviour").evidence)
    }

    @Test fun `an APK file signed differently from the installed app needs careful review`() {
        val twin = InstalledTwin("SBI YONO", listOf("CD".repeat(32)))
        assertSignal(facts(origin = apkFile, launcher = null, twin = twin), "same_package_installed", ApkSeverity.REVIEW_CAREFULLY)
        val same = assertSignal(facts(origin = apkFile, launcher = null, twin = twin.copy(certSha256 = listOf("AB".repeat(32)))), "same_package_installed", ApkSeverity.INFO)
        assertTrue(same.detail.en.contains("same certificate"))
    }

    @Test fun `unreadable signer on either side is not established rather than a mismatch`() {
        val twin = InstalledTwin("Notes", emptyList())
        assertSignal(facts(origin = apkFile, launcher = null, twin = twin), "same_package_installed", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED)
    }

    @Test fun `a debug certificate is worth knowing and a missing certificate is not established`() {
        assertSignal(facts(subjects = listOf("C=US, O=Android, CN=Android Debug")), "debug_certificate", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(certs = emptyList(), subjects = emptyList()), "signer_unknown", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED)
    }

    @Test fun `an installed app without a launcher icon is worth knowing unless it is a system app`() {
        assertSignal(facts(launcher = false, origin = file), "no_launcher_icon", ApkSeverity.WORTH_KNOWING)
        assertNull(ApkRiskRules.evaluate(facts(launcher = false, origin = preinstalled)).signal("no_launcher_icon"))
        assertNull(ApkRiskRules.evaluate(facts(launcher = null, origin = apkFile)).signal("no_launcher_icon"))
    }

    @Test fun `old target sdk is reported`() {
        assertSignal(facts(targetSdk = 22), "old_target_sdk", ApkSeverity.WORTH_KNOWING)
        assertSignal(facts(targetSdk = 26), "old_target_sdk", ApkSeverity.INFO)
        assertNull(ApkRiskRules.evaluate(facts(targetSdk = 34)).signal("old_target_sdk"))
        assertNull(ApkRiskRules.evaluate(facts(targetSdk = null)).signal("old_target_sdk"))
    }

    // --- summary and ordering ---------------------------------------------------------------------------------------

    @Test fun `summary counts review carefully items and is never a verdict`() {
        val r = ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), listOf(c(ServiceKind.ACCESSIBILITY)), origin = file))
        assertEquals(3, r.summary.reviewCarefully)
        assertEquals("3 things to review carefully", r.summary.headline.en)
        assertTrue(r.summary.headline.hi.contains("3"))
        assertTrue(r.summary.disclaimer.en.contains("not an antivirus"))
        assertTrue(r.summary.disclaimer.en.contains("Play Protect"))
    }

    @Test fun `summary uses singular for one item`() {
        val r = ApkRiskRules.evaluate(facts(setOf(p("READ_SMS"))))
        assertEquals("1 thing to review carefully", r.summary.headline.en)
    }

    @Test fun `nothing unusual is still not a safety guarantee`() {
        val r = ApkRiskRules.evaluate(facts(setOf(p("INTERNET"))))
        assertEquals(0, r.summary.reviewCarefully)
        assertEquals(0, r.summary.worthKnowing)
        assertEquals("Nothing unusual in what Android shows us — this is not a safety guarantee.", r.summary.headline.en)
    }

    @Test fun `worth knowing items without review items keep the guarantee caveat`() {
        val r = ApkRiskRules.evaluate(facts(setOf(p("CAMERA"), p("READ_CONTACTS"))))
        assertEquals(0, r.summary.reviewCarefully)
        assertEquals(2, r.summary.worthKnowing)
        assertTrue(r.summary.headline.en.contains("not a safety guarantee"))
    }

    @Test fun `signals are ordered by severity then observed before inferred`() {
        val r = ApkRiskRules.evaluate(facts(setOf(p("CAMERA"), p("READ_SMS"), p("QUERY_ALL_PACKAGES")), label = "Bank KYC", origin = file))
        val order = r.signals.map { it.severity.ordinal }
        assertEquals(order.sorted(), order)
        val worth = r.signals.filter { it.severity == ApkSeverity.WORTH_KNOWING }.map { it.evidence.ordinal }
        assertEquals(worth.sorted(), worth)
    }

    // --- language and wording ---------------------------------------------------------------------------------------

    private val everything = facts(
        permissions = setOf(
            "READ_SMS", "SEND_SMS", "READ_CALL_LOG", "READ_PHONE_STATE", "CALL_PHONE", "READ_PHONE_NUMBERS", "ANSWER_PHONE_CALLS",
            "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "READ_CONTACTS", "GET_ACCOUNTS", "ACCESS_FINE_LOCATION",
            "ACCESS_BACKGROUND_LOCATION", "CAMERA", "RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE", "QUERY_ALL_PACKAGES",
        ).map { p(it) }.toSet(),
        components = ServiceKind.entries.map { c(it, true) },
        label = "PM Kisan Aadhaar UPI", packageName = "com.pension.kyc", granted = setOf(p("READ_SMS")),
        subjects = listOf("CN=Android Debug"), launcher = false, targetSdk = 22,
    )

    private val allReports: List<ApkGuardReport> = InstallSource.entries.flatMap { source ->
        val origin = InstallOrigin(source, "com.example.installer", "Example Store")
        listOf(
            ApkRiskRules.evaluate(everything.copy(origin = origin)),
            ApkRiskRules.evaluate(everything.copy(origin = origin, installedTwin = InstalledTwin("Other", listOf("CD".repeat(32))))),
            ApkRiskRules.evaluate(everything.copy(origin = origin, certSha256 = emptyList(), signerSubjects = emptyList())),
            ApkRiskRules.evaluate(facts(origin = origin)),
            ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), origin = origin)),
            ApkRiskRules.evaluate(facts(setOf(p("CAMERA")), origin = origin)),
        )
    }

    private fun ApkGuardReport.texts(): List<Bilingual> =
        signals.flatMap { listOfNotNull(it.title, it.detail, it.advice, it.state, it.severity.label, it.evidence.label) } +
            listOf(summary.headline, summary.disclaimer)

    @Test fun `every signal has plain English and Hindi text`() {
        val devanagari = Regex("[\\u0900-\\u097F]")
        allReports.flatMap { it.texts() }.forEach { t ->
            assertTrue("blank English in $t", t.en.isNotBlank())
            assertTrue("Hindi missing Devanagari: ${t.hi}", devanagari.containsMatchIn(t.hi))
        }
    }

    @Test fun `every signal id fires somewhere in the kitchen sink and carries advice`() {
        val ids = allReports.flatMap { r -> r.signals.map { it.id } }.toSet()
        val expected = setOf(
            "sms_read", "sms_send", "call_log", "phone", "accessibility", "notification_listener", "vpn", "device_admin",
            "input_method", "autofill", "overlay", "install_packages", "contacts", "precise_location", "camera_microphone",
            "all_files", "query_all_packages", "install_source", "combo_otp_screen_control", "combo_sms_and_notifications",
            "combo_installer_control", "combo_contacts_files", "name_resembles_service", "official_identity", "behaviour",
            "same_package_installed", "debug_certificate", "signer_unknown", "no_launcher_icon", "old_target_sdk",
        )
        assertEquals(expected - ids, emptySet<String>())
        allReports.flatMap { it.signals }.forEach { assertTrue("${it.id} advice", it.advice.en.isNotBlank()) }
    }

    @Test fun `no text calls an app malicious safe or fraudulent`() {
        allReports.flatMap { it.texts() }.forEach { t ->
            APK_FORBIDDEN_EN.forEach { w -> assertFalse("'$w' in: ${t.en}", t.en.lowercase().contains(w)) }
            APK_FORBIDDEN_HI.forEach { w -> assertFalse("'$w' in: ${t.hi}", t.hi.contains(w)) }
        }
    }

    @Test fun `evaluation is deterministic and does not depend on app name shortcuts for capability signals`() {
        val a = ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), label = "WhatsApp", packageName = "com.whatsapp"))
        val b = ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), label = "Random", packageName = "org.random.app"))
        assertEquals(a.signals.map { it.id to it.severity }, b.signals.map { it.id to it.severity })
        assertEquals(a, ApkRiskRules.evaluate(facts(setOf(p("READ_SMS")), label = "WhatsApp", packageName = "com.whatsapp")))
    }
}
