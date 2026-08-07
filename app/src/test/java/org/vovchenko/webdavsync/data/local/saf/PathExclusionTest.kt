package org.vovchenko.webdavsync.data.local.saf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathExclusionTest {

    @Test
    fun `exact folder name is excluded`() {
        assertTrue(PathExclusion.isExcluded(".thumbnails", listOf(".thumbnails")))
    }

    @Test
    fun `files inside an excluded folder are excluded`() {
        assertTrue(PathExclusion.isExcluded("node_modules/pkg/index.js", listOf("node_modules")))
    }

    @Test
    fun `glob wildcard matches any prefix`() {
        assertTrue(PathExclusion.isExcluded("cache/tmp123.dat", listOf("cache/tmp*")))
    }

    @Test
    fun `unrelated paths are not excluded`() {
        assertFalse(PathExclusion.isExcluded("Photos/2026/beach.jpg", listOf(".thumbnails", "node_modules")))
    }

    @Test
    fun `no patterns means nothing is excluded`() {
        assertFalse(PathExclusion.isExcluded("anything/at/all.txt", emptyList()))
    }
}
