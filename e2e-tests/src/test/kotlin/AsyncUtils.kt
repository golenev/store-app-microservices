import io.kotest.assertions.nondeterministic.continuallyConfig
import io.kotest.assertions.nondeterministic.eventuallyConfig
import kotlin.time.Duration.Companion.seconds

val positiveConfig = eventuallyConfig {
    duration = 40.seconds
    interval = 5.seconds
}

val negativeConfig = continuallyConfig<List<Any?>> {
    duration = 30.seconds
    interval = 5.seconds
}


