package com.dfdx047.odinhub.overlay

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Minimal owner set Compose needs to execute and animate inside a WindowManager view.
 */
class OverlayViewHost(context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore = ViewModelStore()

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    val composeView = KeyAwareComposeView(context)

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
    }

    fun setContent(content: @Composable () -> Unit) = composeView.setContent(content)

    /** Teclas vistas ANTES do Compose (ex: Voltar/B para fechar o painel). `true` = consumida. */
    fun setKeyInterceptor(interceptor: (KeyEvent) -> Boolean) {
        composeView.keyInterceptor = interceptor
    }

    fun onResumed() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroyed() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
/**
 * Igual ao `ComposeView`, mas deixa interceptar teclas ao nível da janela. Um `OnKeyListener` no
 * ComposeView não chega: quando um nó interno do Compose tem o foco, a tecla vai direta para ele
 * e o listener do pai nunca é chamado -- por isso Voltar/B não fechavam o painel do overlay.
 */
class KeyAwareComposeView(context: Context) : AbstractComposeView(context) {
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    var keyInterceptor: ((KeyEvent) -> Boolean)? = null

    @Composable
    override fun Content() {
        content.value?.invoke()
    }

    fun setContent(content: @Composable () -> Unit) {
        this.content.value = content
        if (isAttachedToWindow) createComposition()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}
