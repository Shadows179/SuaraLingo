package com.example.updater

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AppVersionComparator {

    private val SEGMENT_REGEX = Regex("^[A-Za-z0-9._-]+$")
    private val VERSION_SPLIT_REGEX = Regex("[-+]")

    /**
     * Validates and normalizes a GitHub repository input such as:
     * - "username/SuaraLingo"
     * - "https://github.com/username/SuaraLingo"
     * - "https://github.com/username/SuaraLingo.git"
     * Returns canonical "owner/repo" or null if invalid or non-GitHub.
     */
    fun normalizeRepoSlug(rawInput: String): String? {
        val trimmed = rawInput.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val parts: List<String> = if (trimmed.contains("://")) {
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            if (!parsed.isHttps) return null
            if (!parsed.host.equals("github.com", ignoreCase = true)) return null
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            listOf(segments[0], segments[1].removeSuffix(".git"))
        } else {
            val split = trimmed.split('/').filter { it.isNotEmpty() }
            if (split.size != 2) return null
            listOf(split[0], split[1].removeSuffix(".git"))
        }

        val owner = parts[0]
        val repo = parts[1]
        if (owner == "." || owner == ".." || repo == "." || repo == "..") return null
        if (owner.contains("..") || repo.contains("..")) return null
        if (!SEGMENT_REGEX.matches(owner) || !SEGMENT_REGEX.matches(repo)) return null
        return "$owner/$repo"
    }

    fun isTrustedGitHubUrl(url: HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443) return false
        val host = url.host.lowercase()
        return host == "api.github.com" ||
            host == "github.com" ||
            host.endsWith(".githubusercontent.com")
    }

    /**
     * Compares semantic version strings like "1.0" vs "v1.0.1" or "v2.1.0-beta1".
     * Returns true strictly when remoteTag represents a newer version than currentVersion.
     */
    fun isRemoteVersionNewer(currentVersion: String, remoteTag: String): Boolean {
        val current = parseVersion(currentVersion)
        val remote = parseVersion(remoteTag)
        if (current.numbers.isEmpty() || remote.numbers.isEmpty()) {
            val normCur = currentVersion.trim().removePrefix("v").removePrefix("V")
            val normRem = remoteTag.trim().removePrefix("v").removePrefix("V")
            return normRem.isNotEmpty() && normRem != normCur
        }

        val maxLen = maxOf(current.numbers.size, remote.numbers.size)
        for (i in 0 until maxLen) {
            val curPart = current.numbers.getOrElse(i) { 0 }
            val remPart = remote.numbers.getOrElse(i) { 0 }
            if (remPart != curPart) {
                return remPart > curPart
            }
        }

        // Numeric parts are equal: stable release (empty prerelease) > prerelease tag
        return when {
            current.preRelease.isNotEmpty() && remote.preRelease.isEmpty() -> true
            current.preRelease.isEmpty() && remote.preRelease.isNotEmpty() -> false
            else -> comparePreReleaseNatural(remote.preRelease, current.preRelease) > 0
        }
    }

    private fun comparePreReleaseNatural(a: String, b: String): Int {
        if (a == b) return 0
        val tokensA = tokenizeNatural(a)
        val tokensB = tokenizeNatural(b)
        val len = maxOf(tokensA.size, tokensB.size)
        for (i in 0 until len) {
            if (i >= tokensA.size) return -1
            if (i >= tokensB.size) return 1
            val ta = tokensA[i]
            val tb = tokensB[i]
            val numA = ta.toLongOrNull()
            val numB = tb.toLongOrNull()
            val cmp = when {
                numA != null && numB != null -> numA.compareTo(numB)
                else -> ta.compareTo(tb)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun tokenizeNatural(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var currentIsDigit = input[0].isDigit()
        for (ch in input) {
            val isDigit = ch.isDigit()
            if (ch == '.' || ch == '-' || ch == '_') {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
                continue
            }
            if (current.isNotEmpty() && isDigit != currentIsDigit) {
                result.add(current.toString())
                current.clear()
            }
            currentIsDigit = isDigit
            current.append(ch)
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private data class ParsedVersion(
        val numbers: List<Int>,
        val preRelease: String
    )

    private fun parseVersion(raw: String): ParsedVersion {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
            .trim()
        if (cleaned.isEmpty()) return ParsedVersion(emptyList(), "")

        val dashIndex = cleaned.indexOf('-')
        val numericPart = if (dashIndex >= 0) cleaned.substring(0, dashIndex) else cleaned
        val preRelease = if (dashIndex >= 0) cleaned.substring(dashIndex + 1).lowercase() else ""

        val numbers = numericPart.split('.')
            .mapNotNull { part ->
                val digits = part.takeWhile { it.isDigit() }
                digits.toIntOrNull()
            }
        return ParsedVersion(numbers, preRelease)
    }
}
