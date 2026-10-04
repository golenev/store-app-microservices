package org.golenev.ui.allure

/**
 * Результат извлечения пояснения `because` из условия проверки.
 *
 * @property condition условие без фрагмента `because`.
 * @property because найденное пояснение или `null`, если пояснение отсутствует.
 */
internal data class BecauseResult(val condition: String, val because: String?)
