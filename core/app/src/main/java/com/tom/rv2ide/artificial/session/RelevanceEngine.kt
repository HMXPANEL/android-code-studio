/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.session

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln

/**
 * Configuration for relevance engine.
 */
data class RelevanceConfig(
    /** Maximum files to consider. */
    val maxCandidates: Int = 100,
    /** Maximum files to return. */
    val maxResults: Int = 10,
    /** Maximum characters to read per file for analysis. */
    val maxReadChars: Int = 5000,
    /** Minimum relevance score to include. */
    val minScore: Double = 0.1,
    /** Weight for path/name matching. */
    val pathWeight: Double = 0.4,
    /** Weight for content matching. */
    val contentWeight: Double = 0.4,
    /** Weight for recency. */
    val recencyWeight: Double = 0.1,
    /** Weight for session history. */
    val sessionWeight: Double = 0.1,
    /** Recency decay half-life (hours). */
    val recencyHalfLifeHours: Double = 24.0
)

/**
 * A scored file candidate.
 */
data class ScoredFile(
    val file: IndexedFile,
    val score: Double,
    val matchReasons: List<String>,
    val excerpt: String? = null
) {
    fun toFileExcerpt(maxChars: Int): FileExcerpt {
        val content = excerpt ?: file.path.take(maxChars)
        return FileExcerpt(
            path = file.relativePath,
            content = if (content.length > maxChars) content.take(maxChars) + "..." else content,
            language = file.language,
            relevanceScore = score,
            tokenEstimate = content.length / 3 // Conservative estimate
        )
    }
}

/**
 * RelevanceEngine finds relevant files for a given query.
 */
class RelevanceEngine(
    private val projectIndexer: ProjectIndexer,
    private val config: RelevanceConfig = RelevanceConfig()
) {

    private val fileContentCache = ConcurrentHashMap<String, String>()

    /**
     * Find relevant files for a query.
     */
    suspend fun findRelevantFiles(
        projectRoot: File,
        query: String,
        sessionHistory: String = "",
        maxFiles: Int = config.maxResults
    ): List<FileExcerpt> {
        val index = projectIndexer.getIndex(projectRoot)
        val queryTerms = extractTerms(query)
        val historyTerms = extractTerms(sessionHistory)

        val candidates = mutableListOf<ScoredFile>()

        // Score all indexed files
        for (file in index.files.values) {
            if (candidates.size >= config.maxCandidates) break

            val score = scoreFile(file, queryTerms, historyTerms, projectRoot)
            if (score >= config.minScore) {
                candidates.add(ScoredFile(file, score, buildMatchReasons(file, queryTerms, historyTerms)))
            }
        }

        // Sort by score descending
        candidates.sortByDescending { it.score }

        // Read top files for excerpts
        val topCandidates = candidates.take(maxFiles)
        val results = mutableListOf<FileExcerpt>()

        for (candidate in topCandidates) {
            val excerpt = readFileExcerpt(projectRoot, candidate.file, config.maxReadChars)
            results.add(candidate.copy(excerpt = excerpt).toFileExcerpt(config.maxReadChars))
        }

        return results
    }

    /**
     * Score a file based on multiple signals.
     */
    private fun scoreFile(
        file: IndexedFile,
        queryTerms: Set<String>,
        historyTerms: Set<String>,
        projectRoot: File
    ): Double {
        var score = 0.0
        val reasons = mutableListOf<String>()

        // 1. Path/name matching
        val pathScore = scorePathMatch(file.relativePath, queryTerms)
        if (pathScore > 0) {
            score += pathScore * config.pathWeight
            reasons.add("path_match:${(pathScore * 100).toInt()}%")
        }

        // 2. Content matching (read file if needed)
        val contentScore = scoreContentMatch(file, queryTerms, projectRoot)
        if (contentScore > 0) {
            score += contentScore * config.contentWeight
            reasons.add("content_match:${(contentScore * 100).toInt()}%")
        }

        // 3. Session history matching
        val sessionScore = scoreSessionMatch(file, historyTerms, projectRoot)
        if (sessionScore > 0) {
            score += sessionScore * config.sessionWeight
            reasons.add("session_match:${(sessionScore * 100).toInt()}%")
        }

        // 4. Recency bonus
        val recencyScore = computeRecencyScore(file.lastModified)
        if (recencyScore > 0) {
            score += recencyScore * config.recencyWeight
            reasons.add("recency:${(recencyScore * 100).toInt()}%")
        }

        // 5. Language relevance (prefer source files over config)
        val languageBonus = languageRelevanceBonus(file.language)
        score += languageBonus

        return score
    }

    private fun scorePathMatch(relativePath: String, queryTerms: Set<String>): Double {
        val pathLower = relativePath.lowercase()
        val fileName = File(relativePath).name.lowercase()

        var matches = 0
        for (term in queryTerms) {
            if (fileName.contains(term) || pathLower.contains(term)) {
                matches++
            }
        }

        if (queryTerms.isEmpty()) return 0.0
        return matches.toDouble() / queryTerms.size
    }

    private fun scoreContentMatch(file: IndexedFile, queryTerms: Set<String>, projectRoot: File): Double {
        if (queryTerms.isEmpty()) return 0.0

        val content = getFileContent(projectRoot, file)
        if (content.isBlank()) return 0.0

        val contentLower = content.lowercase()
        var matches = 0

        for (term in queryTerms) {
            if (contentLower.contains(term)) {
                matches++
            }
        }

        return matches.toDouble() / queryTerms.size
    }

    private fun scoreSessionMatch(file: IndexedFile, historyTerms: Set<String>, projectRoot: File): Double {
        if (historyTerms.isEmpty()) return 0.0

        val content = getFileContent(projectRoot, file)
        if (content.isBlank()) return 0.0

        val contentLower = content.lowercase()
        var matches = 0

        for (term in historyTerms) {
            if (contentLower.contains(term)) {
                matches++
            }
        }

        return matches.toDouble() / historyTerms.size
    }

    private fun computeRecencyScore(lastModified: Long): Double {
        val ageHours = (System.currentTimeMillis() - lastModified) / (1000.0 * 60 * 60)
        if (ageHours <= 0) return 1.0
        // Exponential decay
        return exp(-ageHours * ln(2.0) / config.recencyHalfLifeHours)
    }

    private fun languageRelevanceBonus(language: String?): Double {
        return when (language) {
            "kotlin", "java", "python", "javascript", "typescript", "rust", "go", "cpp", "c", "swift" -> 0.1
            "xml", "json", "yaml", "toml", "gradle", "properties" -> 0.05
            "markdown", "txt" -> 0.02
            null -> 0.0
            else -> 0.01
        }
    }

    private fun buildMatchReasons(
        file: IndexedFile,
        queryTerms: Set<String>,
        historyTerms: Set<String>
    ): List<String> {
        val reasons = mutableListOf<String>()

        val pathLower = file.relativePath.lowercase()
        val fileName = File(file.relativePath).name.lowercase()

        for (term in queryTerms) {
            if (fileName.contains(term)) reasons.add("filename:$term")
            else if (pathLower.contains(term)) reasons.add("path:$term")
        }

        return reasons.distinct()
    }

    /**
     * Get file content with caching.
     */
    private fun getFileContent(projectRoot: File, file: IndexedFile): String {
        val cacheKey = "${projectRoot.canonicalPath}:${file.relativePath}"
        return fileContentCache.getOrPut(cacheKey) {
            try {
                File(file.path).readText()
            } catch (e: Exception) {
                ""
            }
        }
    }

    /**
     * Read file excerpt for context inclusion.
     */
    private fun readFileExcerpt(projectRoot: File, file: IndexedFile, maxChars: Int): String {
        return getFileContent(projectRoot, file).take(maxChars)
    }

    /**
     * Extract search terms from text.
     */
    private fun extractTerms(text: String): Set<String> {
        if (text.isBlank()) return emptySet()

        // Simple term extraction: split on non-word chars, filter stopwords
        val stopwords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
            "been", "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "must", "can", "this",
            "that", "these", "those", "i", "you", "he", "she", "it", "we", "they",
            "my", "your", "his", "her", "its", "our", "their", "me", "him", "us",
            "them", "what", "which", "who", "whom", "whose", "where", "when", "why",
            "how", "all", "each", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very"
        )

        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stopwords }
            .distinct()
            .toSet()
    }

    /**
     * Clear content cache (call on project change).
     */
    fun clearCache(projectRoot: File? = null) {
        if (projectRoot == null) {
            fileContentCache.clear()
        } else {
            val prefix = "${projectRoot.canonicalPath}:"
            fileContentCache.keys.filter { it.startsWith(prefix) }.forEach { fileContentCache.remove(it) }
        }
    }
}