package com.dpdpxray.core.apk

import java.util.Locale

/** How installing an APK file relates to an app already on the phone with the same package name. */
enum class InstallRelation {
    /** No app with this package name is installed: installing it adds a new app. */
    NEW_INSTALL,

    /** Signed with exactly the installed app's certificate(s): Android treats the file as an update. */
    SAME_SIGNER,

    /**
     * Signed with a newer key whose signing history (APK Signature Scheme v3 key rotation) contains the installed app's
     * key, so Android treats the file as an update.
     */
    ROTATED_SIGNER,

    /**
     * Signed with a different certificate and no shared history. A real update is signed with the same key, and
     * Android refuses to install this file over the installed app.
     */
    DIFFERENT_SIGNER,

    /** A certificate could not be read on one side, so how the two relate is not established. */
    SIGNER_UNKNOWN,
}

/**
 * The signer-mismatch detector. It compares an APK file's signing certificates with those of the installed app that has
 * the same package name, the way Android decides whether a file may update an app: the whole signer set must match, or
 * a single rotated signer's history must contain the installed signer. Pure: certificate fingerprints in, a relation out.
 */
object SignerCheck {
    fun relation(fileSigners: List<String>, fileHistory: List<String>, installed: InstalledTwin?): InstallRelation {
        if (installed == null) return InstallRelation.NEW_INSTALL
        val mine = normalise(fileSigners)
        val theirs = normalise(installed.certSha256)
        if (mine.isEmpty() || theirs.isEmpty()) return InstallRelation.SIGNER_UNKNOWN
        if (mine == theirs) return InstallRelation.SAME_SIGNER
        // Key rotation is defined for a single signer only: the file's one signer descends from the installed one.
        if (mine.size == 1 && theirs.size == 1 && theirs.single() in normalise(fileHistory)) return InstallRelation.ROTATED_SIGNER
        return InstallRelation.DIFFERENT_SIGNER
    }

    /** The big red flag: a file that claims an installed app's package name but is signed by someone else's key. */
    fun isMismatch(relation: InstallRelation): Boolean = relation == InstallRelation.DIFFERENT_SIGNER

    /**
     * True when the file's version code is lower than the installed app's (Android refuses such a downgrade), false when
     * it is not, null when either version is unknown or nothing is installed.
     */
    fun isDowngrade(fileVersionCode: Long?, installed: InstalledTwin?): Boolean? {
        val installedVersion = installed?.versionCode ?: return null
        val fileVersion = fileVersionCode ?: return null
        return fileVersion < installedVersion
    }

    private fun normalise(certs: List<String>): Set<String> =
        certs.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
}
