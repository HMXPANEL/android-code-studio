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

import com.tom.rv2ide.artificial.tools.builtins.BoundedWalk
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Metadata for an indexed file.
 */
data class IndexedFile(
    val path: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val language: String?,
    val hash: String
)

/**
 * Project index for fast file lookups.
 */
data class ProjectIndex(
    val projectRoot: String,
    val files: Map<String, IndexedFile>,
    val indexedAt: Long = System.currentTimeMillis(),
    val fileCount: Int = files.size,
    val totalSize: Long = files.values.sumOf { it.size }
) {
    fun getFile(relativePath: String): IndexedFile? = files[relativePath]
    fun getFilesByExtension(ext: String): List<IndexedFile> =
        files.values.filter { it.relativePath.endsWith(".$ext") }
    fun getFilesByLanguage(lang: String): List<IndexedFile> =
        files.values.filter { it.language == lang }
}

/**
 * Configuration for project indexing.
 */
data class IndexConfig(
    /** Maximum files to index. */
    val maxFiles: Int = 5000,
    /** Maximum file size to index (bytes). */
    val maxFileSize: Long = 512 * 1024, // 512KB
    /** Maximum directory traversal depth. */
    val maxDepth: Int = 20,
    /** Excluded directory names (any path segment). */
    val excludedDirs: Set<String> = setOf(
        ".git", "build", "out", "target", "dist", "node_modules",
        ".gradle", ".idea", "bin", "obj", ".vs", "__pycache__",
        ".pytest_cache", ".mypy_cache", ".ruff_cache", "coverage",
        ".next", ".nuxt", ".vercel", ".netlify", "vendor"
    ),
    /** Excluded file patterns. */
    val excludedFiles: Set<String> = setOf(
        "*.log", "*.tmp", "*.cache", "*.lock", "*.pid",
        "*.class", "*.jar", "*.war", "*.ear", "*.apk", "*.aab",
        "*.dex", "*.so", "*.dll", "*.dylib", "*.o", "*.a",
        "*.pyc", "*.pyo", "*.pyd", "*.whl", "*.egg",
        "*.min.js", "*.min.css", "*.map",
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
        ".DS_Store", "Thumbs.db"
    ),
    /** Included file extensions (empty = all non-excluded). */
    val includedExtensions: Set<String> = setOf(
        "kt", "java", "xml", "gradle", "kts", "properties",
        "json", "yaml", "yml", "toml", "md", "txt",
        "py", "js", "ts", "jsx", "tsx", "html", "css",
        "cpp", "hpp", "c", "h", "rs", "go", "rb", "php",
        "swift", "m", "mm", "dart", "scala", "clj", "hs",
        "sql", "sh", "bash", "zsh", "fish", "ps1"
    )
)

/**
 * Bounded project indexer with incremental updates.
 * Reuses the existing [BoundedWalk] scanner; no duplicate walker.
 */
class ProjectIndexer(
    private val config: IndexConfig = IndexConfig()
) {

    private val indexCache = ConcurrentHashMap<String, ProjectIndex>()
    private val indexingJobs = ConcurrentHashMap<String, Boolean>()

    suspend fun getIndex(projectRoot: File, forceRefresh: Boolean = false): ProjectIndex {
        val key = canonical(projectRoot)
        if (!forceRefresh) {
            val cached = indexCache[key]
            if (cached != null && !isStale(cached, projectRoot)) {
                return cached
            }
        }
        return buildIndex(projectRoot)
    }

    private suspend fun buildIndex(projectRoot: File): ProjectIndex {
        val key = canonical(projectRoot)

        if (indexingJobs.putIfAbsent(key, true) != null) {
            while (indexingJobs[key] == true) {
                kotlinx.coroutines.delay(100)
            }
            return indexCache[key] ?: buildIndex(projectRoot)
        }

        try {
            val entries = BoundedWalk.list(
                root = projectRoot,
                maxDepth = config.maxDepth,
                maxEntries = config.maxFiles
            ).filter { !it.truncated && it.file.isFile }

            val files = LinkedHashMap<String, IndexedFile>()
            for (entry in entries) {
                val file = entry.file
                if (files.size >= config.maxFiles) break
                if (!shouldIndex(projectRoot, file)) continue
                val relPath = relPath(projectRoot, file) ?: continue
                files[relPath] = IndexedFile(
                    path = canonical(file),
                    relativePath = relPath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    language = detectLanguage(file),
                    hash = "${file.length()}-${file.lastModified()}"
                )
            }

            val index = ProjectIndex(
                projectRoot = key,
                files = files.toMap(),
                indexedAt = System.currentTimeMillis()
            )
            indexCache[key] = index
            return index
        } finally {
            indexingJobs.remove(key)
        }
    }

    private fun isStale(index: ProjectIndex, projectRoot: File): Boolean {
        for (file in index.files.values) {
            val f = File(file.path)
            if (!f.exists() || f.lastModified() != file.lastModified || f.length() != file.size) {
                return true
            }
        }
        // ponytail: cheap count check only; full rescan is explicit via forceRefresh
        return false
    }

    private fun shouldIndex(projectRoot: File, file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() > config.maxFileSize) return false

        val rel = relPath(projectRoot, file) ?: file.name
        val segments = rel.replace('\\', '/').split('/')
        if (segments.any { it in config.excludedDirs }) return false

        val name = file.name.lowercase()
        for (pattern in config.excludedFiles) {
            if (matchesPattern(name, pattern)) return false
        }

        if (config.includedExtensions.isNotEmpty()) {
            if (name.startsWith("dockerfile") || name == "makefile" || name == "cmake") return true
            val ext = file.extension.lowercase()
            if (ext.isEmpty()) return false
            return ext in config.includedExtensions
        }
        return true
    }

    private fun matchesPattern(name: String, pattern: String): Boolean {
        if (pattern.startsWith("*.")) {
            return name.endsWith(pattern.substring(1))
        }
        return name == pattern
    }

    private fun detectLanguage(file: File): String? {
        return when (file.extension.lowercase()) {
            "kt" -> "kotlin"
            "kts" -> "kotlin"
            "java" -> "java"
            "xml" -> "xml"
            "gradle" -> "gradle"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "md" -> "markdown"
            "py" -> "python"
            "js", "mjs", "cjs", "jsx" -> "javascript"
            "ts", "mts", "cts", "tsx" -> "typescript"
            "html", "htm" -> "html"
            "css", "scss", "sass", "less" -> "css"
            "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> "cpp"
            "c", "h" -> "c"
            "rs" -> "rust"
            "go" -> "go"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "m", "mm" -> "objective-c"
            "dart" -> "dart"
            "scala", "sc" -> "scala"
            "clj", "cljs", "cljc" -> "clojure"
            "hs" -> "haskell"
            "sql" -> "sql"
            "sh", "bash", "zsh" -> "shell"
            "ps1" -> "powershell"
            else -> {
                val n = file.name.lowercase()
                when {
                    n.startsWith("dockerfile") -> "dockerfile"
                    n == "makefile" -> "make"
                    else -> null
                }
            }
        }
    }

    fun invalidate(projectRoot: File) {
        indexCache.remove(canonical(projectRoot))
    }

    fun invalidateFile(projectRoot: File, relativePath: String) {
        val key = canonical(projectRoot)
        val index = indexCache[key] ?: return
        val newFiles = index.files.toMutableMap()
        newFiles.remove(relativePath)
        indexCache[key] = index.copy(files = newFiles.toMap())
    }

    suspend fun updateFile(projectRoot: File, file: File) {
        val key = canonical(projectRoot)
        val index = indexCache[key] ?: return
        val relPath = relPath(projectRoot, file) ?: return
        val newFiles = index.files.toMutableMap()
        if (shouldIndex(projectRoot, file)) {
            newFiles[relPath] = IndexedFile(
                path = canonical(file),
                relativePath = relPath,
                size = file.length(),
                lastModified = file.lastModified(),
                language = detectLanguage(file),
                hash = "${file.length()}-${file.lastModified()}"
            )
        } else {
            newFiles.remove(relPath)
        }
        indexCache[key] = index.copy(files = newFiles.toMap())
    }

    fun getStats(projectRoot: File): IndexStats? {
        val index = indexCache[canonical(projectRoot)] ?: return null
        return IndexStats(
            projectRoot = index.projectRoot,
            fileCount = index.fileCount,
            totalSize = index.totalSize,
            indexedAt = index.indexedAt,
            languages = index.files.values.groupBy { it.language ?: "unknown" }.mapValues { it.value.size }
        )
    }

    private fun canonical(file: File): String = try {
        file.canonicalPath
    } catch (e: Exception) {
        file.absolutePath
    }

    private fun relPath(root: File, file: File): String? = try {
        root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    } catch (e: Exception) {
        null
    }
}

/**
 * Index statistics.
 */
data class IndexStats(
    val projectRoot: String,
    val fileCount: Int,
    val totalSize: Long,
    val indexedAt: Long,
    val languages: Map<String, Int>
)
