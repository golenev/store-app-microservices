package org.golenev.ui.config
import org.golenev.restapi.endpoints.*

/** Исходный HTTP-ответ, передаваемый через принадлежащий тесту прокси. */
internal data class Forwarded(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)
