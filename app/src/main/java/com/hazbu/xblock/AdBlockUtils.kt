package com.hazbu.xblock

import android.content.Context
import com.hazbu.xblock.Constants.PREFS_NAME
import org.json.JSONObject
import java.io.File
import java.io.BufferedWriter
import java.io.FileWriter

object AdBlockUtils {
    const val FILTER_URL = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
    const val EXODUS_URL = "https://reports.exodus-privacy.eu.org/api/trackers"

    fun parseAdGuardFilter(reader: java.io.Reader): Set<String> {
        val domains = mutableSetOf<String>()
        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("||")) {
                val caretIndex = trimmed.indexOf("^")
                if (caretIndex != -1) {
                    val domain = trimmed.substring(2, caretIndex)
                    if (domain.isNotEmpty() && !domain.contains("/")) {
                        domains.add(domain)
                    }
                }
            }
        }
        return domains
    }

    fun parseExodusFilter(reader: java.io.Reader): Set<String> {
        val packages = mutableSetOf<String>()
        try {
            val json = reader.readText()
            val root = JSONObject(json)
            val trackers = root.optJSONObject("trackers") ?: return emptySet()

            val keys = trackers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val tracker = trackers.getJSONObject(key)
                val signature = tracker.optString("code_signature", "")
                if (signature.isNotEmpty()) {
                    signature.split("|").forEach { pkg ->
                        val cleaned = pkg.trim().trimEnd('.')
                        if (cleaned.isNotEmpty()) {
                            packages.add(cleaned)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return packages
    }

    fun isAdClass(className: String, adPackages: Set<String>): Boolean {
        val lowerName = className.lowercase()
        return adPackages.any { lowerName.startsWith(it.lowercase()) }
    }

    fun domainsFile(context: Context): File = File(context.filesDir, Constants.DOMAINS_FILE)

    fun domainCount(context: Context): Int {
        val file = domainsFile(context)
        if (!file.isFile) return 0
        return file.useLines { lines -> lines.count { it.isNotBlank() } }
    }

    fun writeDomainsFile(context: Context, domains: Set<String>) {
        val target = domainsFile(context)
        val temp = File(context.filesDir, "${Constants.DOMAINS_FILE}.tmp")
        BufferedWriter(FileWriter(temp)).use { writer ->
            domains.asSequence().filter { it.isNotBlank() }.sorted().forEach {
                writer.appendLine(it)
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun deleteDomainsFile(context: Context) {
        domainsFile(context).delete()
    }

    fun fixPermissions(context: Context) {
        try {
            val dataDir = File(context.applicationInfo.dataDir)
            dataDir.setExecutable(true, false)

            val prefsDir = File(dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false)
                @Suppress("SetWorldReadable")
                prefsDir.setReadable(true, false)
            }

            val prefFile = File(prefsDir, "$PREFS_NAME.xml")
            if (prefFile.exists()) {
                @Suppress("SetWorldReadable")
                prefFile.setReadable(true, false)
            }
        } catch (_: Exception) {}
    }
}
