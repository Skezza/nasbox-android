package skezza.nasbox.ui.common

sealed interface LoadState {
    object Idle : LoadState
    object Loading : LoadState
    object Success : LoadState
    data class Error(val message: String) : LoadState
}
