package ru.ypmn.sdk
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.flow.Flow

data class ThreeDsResult(val status: Status, val code: String? = null, val intentStatus: IntentStatus? = null) {
    enum class Status { SUCCESS, FAILURE }
}

interface ThreeDsView {
    val webView: WebView?
    fun mount(container: ViewGroup): ThreeDsView
    fun unmount()
    fun destroy()
    val results: Flow<ThreeDsResult>
}
