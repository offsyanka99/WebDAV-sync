package org.vovchenko.webdavsync.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabBackStackPolicyTest {

    @Test
    fun `settings menu alone is kept`() {
        assertFalse(TabBackStackPolicy.shouldCollapseToSettingsMenu(listOf("overview", "settings")))
    }

    @Test
    fun `each settings item above the menu is collapsed`() {
        listOf(
            listOf("overview", "settings", "settings/sync"),
            listOf("overview", "settings", "settings/system"),
            listOf("overview", "settings", "settings/backup"),
            listOf("overview", "settings", "settings/about"),
            listOf("overview", "settings", "accounts"),
            listOf("overview", "settings", "accounts", "accounts/add"),
        ).forEach { routes ->
            assertTrue(routes.toString(), TabBackStackPolicy.shouldCollapseToSettingsMenu(routes))
        }
    }

    @Test
    fun `folder editor routes are recognized from the back stack`() {
        assertTrue(TabBackStackPolicy.isFolderEditor("folders/add"))
        assertTrue(TabBackStackPolicy.isFolderEditor("folders/edit/{folderPairId}"))
        assertTrue(TabBackStackPolicy.isFolderEditor("folders/edit/4"))
        assertFalse(TabBackStackPolicy.isFolderEditor("folders"))
        assertFalse(TabBackStackPolicy.isFolderEditor("accounts/add"))
        assertFalse(TabBackStackPolicy.isFolderEditor(null))
    }

    @Test
    fun `folders and the folder editor are not settings details`() {
        assertFalse(
            TabBackStackPolicy.shouldCollapseToSettingsMenu(
                listOf("overview", "folders", "folders/edit/{folderPairId}"),
            ),
        )
        assertFalse(
            TabBackStackPolicy.shouldCollapseToSettingsMenu(
                listOf("overview", "folders", "accounts"),
            ),
        )
    }
}
