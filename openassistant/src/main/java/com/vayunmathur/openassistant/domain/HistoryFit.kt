package com.vayunmathur.openassistant.domain

/**
 * [history] plus [current], with the oldest history dropped until [fits] accepts it.
 *
 * The current turn is never dropped; null when even it alone does not fit. The kept history
 * starts on a user turn. [fits] must be monotone (fewer turns never fit worse), which lets
 * this binary-search the cut instead of measuring once per dropped turn.
 */
fun <T> fitConversation(
    history: List<T>,
    current: T,
    isUserTurn: (T) -> Boolean,
    fits: (List<T>) -> Boolean,
): List<T>? {
    if (fits(history + current)) return history + current
    if (!fits(listOf(current))) return null
    var low = 1
    var high = history.size
    while (low < high) {
        val mid = (low + high) / 2
        if (fits(history.drop(mid) + current)) high = mid else low = mid + 1
    }
    return history.drop(low).dropWhile { !isUserTurn(it) } + current
}
