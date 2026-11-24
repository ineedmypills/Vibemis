import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Aggregate all variant/unit tests under the root `test` task
gradle.projectsEvaluated {
    val agg = tasks.findByName("test") ?: tasks.register("test") {
        group = "verification"
        description = "Runs every unit-test task in all sub-projects"
    }.get()

    subprojects.forEach { p ->
        p.tasks.matching { it.name.endsWith("UnitTest") }.configureEach {
            agg.dependsOn(this)
        }
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        outputs.upToDateWhen { false }
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }
}
