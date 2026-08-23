package com.custom.astrion.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Give a Compose Dialog's window back to the activity's key router.
 *
 * A Dialog is its OWN window, so hardware keys go to it and never reach
 * MainActivity.dispatchKeyEvent -- which is where every physical button on the
 * HA100 is resolved. The visible symptom: with the app-drawer modal open, the
 * volume rocker moved ANDROID's media volume instead of the Sonos, because
 * nothing bound them and the system fell back to its default.
 *
 * This wraps the dialog window's callback so keys are offered to the activity
 * first and only fall through to the dialog (and thus to Android) if the
 * layout has no binding for them. Restored on dispose, so a dialog closing
 * cannot leave a stale callback behind.
 */
@Composable
fun ForwardHardwareKeys() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        // NOT `context as? Activity`. Inside a Dialog, LocalContext.current is a
        // ContextWrapper around the activity, so the direct cast yields null --
        // and a null here disables the whole thing silently, which is exactly
        // how the first two attempts at this "worked" and changed nothing.
        var c: Context? = context
        var activity: Activity? = null
        while (c != null && activity == null) {
            activity = c as? Activity
            c = (c as? ContextWrapper)?.baseContext
        }
        // Walk UP the chain rather than assuming the immediate parent.
        // `LocalView.current.parent as DialogWindowProvider` is the recipe you
        // find everywhere, and it silently produced null here -- the compose
        // view is nested deeper than one level inside the dialog. A null cast
        // fails invisibly, which is why the first version of this looked
        // correct and changed nothing.
        var p: ViewParent? = view.parent
        var provider: DialogWindowProvider? = null
        while (p != null && provider == null) {
            provider = p as? DialogWindowProvider
            p = (p as? View)?.parent
        }
        val window = provider?.window
        val original = window?.callback
        if (activity == null || window == null || original == null) {
            Log.w("AstrionKeys", "dialog key forwarding NOT attached " +
                "(activity=${activity != null} window=${window != null} cb=${original != null})")
        }
        if (activity != null && window != null && original != null) {
            Log.i("AstrionKeys", "dialog key forwarding attached")
            window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    // BACK belongs to the MODAL, not the page underneath. It is
                    // bound to the AV back action, so forwarding it first meant
                    // the source device stepped back and the modal stayed open
                    // -- the one key whose meaning genuinely changes while a
                    // modal is up. Everything else still reaches the page, so
                    // volume and transport keep working over the top of it.
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        return original.dispatchKeyEvent(event)
                    }
                    return activity.dispatchKeyEvent(event) || original.dispatchKeyEvent(event)
                }
            }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
}
