package com.tom.rv2ide.artificial.session

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for stable project identity ([ProjectIdentity]) and the read-only
 * Git-ignore check ([ProjectGitIgnore]).
 */
class ProjectIdentityTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("proj_id_").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun testFirstResolveCreatesStableIdAndReusesIt() {
        val first = ProjectIdentity.getOrCreate(root)
        assertTrue(first is ProjectIdentity.Resolution.Stable)
        val id = (first as ProjectIdentity.Resolution.Stable).id
        assertTrue(id.matches(Regex("[0-9a-f-]{36}")))

        // Marker file actually on disk; second resolve reuses it.
        assertTrue(ProjectIdentity.markerFile(root).isFile)
        val second = ProjectIdentity.getOrCreate(root)
        assertEquals(first, second)
        assertEquals(
            ProjectIdentity.Resolution.Stable(id),
            ProjectIdentity.resolve(root)
        )
    }

    @Test
    fun testMovedProjectKeepsIdentityWhenMarkerMovesAlong() {
        val id = (ProjectIdentity.getOrCreate(root)
            as ProjectIdentity.Resolution.Stable).id
        val moved = File(root.parentFile, "proj_id_moved_${System.nanoTime()}").apply { mkdirs() }
        try {
            File(root, ".opencode").copyRecursively(File(moved, ".opencode"))
            assertEquals(
                ProjectIdentity.Resolution.Stable(id),
                ProjectIdentity.resolve(moved)
            )
        } finally {
            moved.deleteRecursively()
        }
    }

    @Test
    fun testCopiedProjectRetainsMarkerByPolicy() {
        // Documented policy: copies keep the marker; isolation comes from
        // directory-scoped listing, never from rewriting the copy.
        val id = (ProjectIdentity.getOrCreate(root)
            as ProjectIdentity.Resolution.Stable).id
        val copy = Files.createTempDirectory("proj_id_copy_").toFile()
        try {
            File(root, ".opencode").copyRecursively(File(copy, ".opencode"))
            assertEquals(
                ProjectIdentity.Resolution.Stable(id),
                ProjectIdentity.resolve(copy)
            )
        } finally {
            copy.deleteRecursively()
        }
    }

    @Test
    fun testMalformedMarkerFallsBackWithoutTouchingFile() {
        val marker = ProjectIdentity.markerFile(root)
        marker.parentFile.mkdirs()
        marker.writeText("not-a-uuid")
        val before = marker.readText()

        val resolved = ProjectIdentity.resolve(root)
        assertTrue(resolved is ProjectIdentity.Resolution.PathFallback)
        assertEquals(
            ProjectIdentity.FallbackReason.MARKER_MALFORMED,
            (resolved as ProjectIdentity.Resolution.PathFallback).reason
        )
        // getOrCreate must not repair, delete, or overwrite it either.
        assertTrue(ProjectIdentity.getOrCreate(root) is ProjectIdentity.Resolution.PathFallback)
        assertEquals(before, marker.readText())
    }

    @Test
    fun testMissingMarkerReportsWithoutCreating() {
        val resolved = ProjectIdentity.resolve(root)
        assertTrue(resolved is ProjectIdentity.Resolution.PathFallback)
        assertEquals(
            ProjectIdentity.FallbackReason.MARKER_MISSING,
            (resolved as ProjectIdentity.Resolution.PathFallback).reason
        )
        assertFalse(ProjectIdentity.markerFile(root).exists())
    }

    @Test
    fun testFileAsRootIsNotADirectory() {
        val file = File(root, "plain.txt").apply { writeText("x") }
        val resolved = ProjectIdentity.resolve(file)
        assertTrue(resolved is ProjectIdentity.Resolution.PathFallback)
        assertEquals(
            ProjectIdentity.FallbackReason.NOT_A_DIRECTORY,
            (resolved as ProjectIdentity.Resolution.PathFallback).reason
        )
        assertTrue(ProjectIdentity.getOrCreate(file) is ProjectIdentity.Resolution.PathFallback)
    }

    @Test
    fun testGitIgnoreIgnored() {
        File(root, ".git").mkdir()
        File(root, ".gitignore").writeText("*.log\n.opencode/\n")
        assertEquals(ProjectGitIgnore.Status.IGNORED, ProjectGitIgnore.status(root))
    }

    @Test
    fun testGitIgnoreRuleMissing() {
        File(root, ".git").mkdir()
        File(root, ".gitignore").writeText("*.log\n")
        assertEquals(ProjectGitIgnore.Status.RULE_MISSING, ProjectGitIgnore.status(root))
    }

    @Test
    fun testGitIgnoreCommentedRuleDoesNotCount() {
        File(root, ".git").mkdir()
        File(root, ".gitignore").writeText("# .opencode/\n")
        assertEquals(ProjectGitIgnore.Status.RULE_MISSING, ProjectGitIgnore.status(root))
    }

    @Test
    fun testGitIgnoreNotARepo() {
        assertEquals(ProjectGitIgnore.Status.NOT_A_REPO, ProjectGitIgnore.status(root))
    }
}
