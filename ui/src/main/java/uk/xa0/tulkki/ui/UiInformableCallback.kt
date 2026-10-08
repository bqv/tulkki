package uk.xa0.tulkki.ui

interface UiInformableCallback<T> : UiCallback<T> {
    fun inform(text: String)
}
