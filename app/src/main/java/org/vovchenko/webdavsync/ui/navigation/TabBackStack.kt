package org.vovchenko.webdavsync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Bottom tabs restore the back stack of the tab you return to.
 * Settings details must not be part of that saved stack: leaving Configuration
 * (or any other Settings item) and opening Settings again shows the menu.
 * The folder editor and a half-filled new account register [TabLeaveInterceptor]
 * and pop themselves only after save or discard, so they are not restored either.
 */
internal object TabBackStackPolicy {
    const val SETTINGS_ROUTE = "settings"
    const val ADD_ACCOUNT_ROUTE = "accounts/add"

    fun shouldCollapseToSettingsMenu(routesFromRoot: List<String?>): Boolean {
        val settingsIndex = routesFromRoot.indexOf(SETTINGS_ROUTE)
        return settingsIndex >= 0 && settingsIndex < routesFromRoot.lastIndex
    }

    fun isFolderEditor(route: String?): Boolean =
        route == "folders/add" || (route != null && route.startsWith("folders/edit/"))
}

/**
 * The visible form owns the handler. [unbind] ignores a stale owner so disposing
 * the previous screen cannot clear the handler the next screen just registered.
 */
class TabLeaveInterceptor {
    var onAttemptLeave: ((proceed: () -> Unit) -> Unit)? = null
        private set

    /**
     * Set when a screen above the folder editor was popped during a tab switch
     * and the editor is not composed yet. The editor consumes this once it appears.
     */
    var deferredProceed: (() -> Unit)? = null

    private var boundOwner: Any? = null

    fun bind(owner: Any, handler: ((proceed: () -> Unit) -> Unit)?) {
        if (handler == null) {
            if (boundOwner == owner) {
                boundOwner = null
                onAttemptLeave = null
            }
            return
        }
        boundOwner = owner
        onAttemptLeave = handler
    }

    fun unbind(owner: Any) {
        if (boundOwner == owner) {
            boundOwner = null
            onAttemptLeave = null
        }
    }
}

@Composable
fun BindTabLeave(
    interceptor: TabLeaveInterceptor?,
    handler: ((proceed: () -> Unit) -> Unit)?,
) {
    val owner = remember { Any() }
    SideEffect { interceptor?.bind(owner, handler) }
    DisposableEffect(interceptor, owner) {
        onDispose { interceptor?.unbind(owner) }
    }
}
