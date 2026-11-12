package io.github.jqssun.airplay.update

data class ApkUpdateMetadata(val packageName: String, val versionCode: Long, val signerDigests: Set<String>)

object ApkUpdatePolicy {
    fun rejection(installed: ApkUpdateMetadata, candidate: ApkUpdateMetadata): String? = when {
        candidate.packageName != installed.packageName -> "Package must be ${installed.packageName}"
        candidate.versionCode <= installed.versionCode -> "The APK version must be newer than the installed app"
        installed.signerDigests.isEmpty() || candidate.signerDigests.isEmpty() -> "APK signing certificate is unavailable"
        installed.signerDigests != candidate.signerDigests -> "APK signing certificate differs from the installed app"
        else -> null
    }
}
