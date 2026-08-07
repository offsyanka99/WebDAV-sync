package org.vovchenko.webdavsync

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for bottom-nav navigation (plan Phase 10 "Compose UI tests"). Requires an emulator
 * or physical device / connected-test runner to execute — not run in this sandbox.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavSwitchesBetweenTabs() {
        composeRule.onNodeWithText("Folders").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Overview").performClick()
    }
}

