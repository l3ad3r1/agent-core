package com.hermes.agent.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolveSafePath_validRelativePath_resolvesInsideRoot() {
        val root = tempFolder.newFolder("workspace")
        val resolved = PathSecurity.resolveSafePath("subfolder/file.txt", root)
        assertEquals(File(root, "subfolder/file.txt").canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun resolveSafePath_validAbsolutePathInsideRoot_resolvesSuccessfully() {
        val root = tempFolder.newFolder("workspace")
        val target = File(root, "notes/todo.md")
        val resolved = PathSecurity.resolveSafePath(target.absolutePath, root)
        assertEquals(target.canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun resolveSafePath_traversalWithDotDot_throwsSecurityException() {
        val root = tempFolder.newFolder("workspace")
        try {
            PathSecurity.resolveSafePath("../outside.txt", root)
            fail("Expected SecurityException on '..' traversal")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("traversal") == true)
        }
    }

    @Test
    fun resolveSafePath_deepTraversal_throwsSecurityException() {
        val root = tempFolder.newFolder("workspace")
        try {
            PathSecurity.resolveSafePath("sub/../../escape.txt", root)
            fail("Expected SecurityException on nested '..' traversal")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("traversal") == true)
        }
    }

    @Test
    fun resolveSafePath_blockedDevicePaths_throwsSecurityException() {
        val root = tempFolder.newFolder("workspace")
        val blockedPaths = listOf("/dev/null", "/proc/cpuinfo", "/sys/class", "/etc/passwd", "/system/bin/sh")

        for (path in blockedPaths) {
            try {
                PathSecurity.resolveSafePath(path, root)
                fail("Expected SecurityException for blocked device path $path")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("blocked") == true)
            }
        }
    }

    @Test
    fun resolveSafePath_absolutePathOutsideRoot_throwsSecurityException() {
        val root = tempFolder.newFolder("workspace")
        val outside = tempFolder.newFolder("outside")
        val target = File(outside, "secret.txt")

        try {
            PathSecurity.resolveSafePath(target.absolutePath, root)
            fail("Expected SecurityException for path outside root")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("escapes") == true)
        }
    }

    @Test
    fun validateWithinDir_returnsErrorMessageOnEscape() {
        val root = tempFolder.newFolder("workspace")
        val error = PathSecurity.validateWithinDir(File("../escape.txt"), root)
        assertNotNull(error)
    }
}
