// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.secrets) apply false
}

tasks.register("downloadCrMd") {
    doLast {
        val url = java.net.URL("https://raw.githubusercontent.com/nhasibuan/juaravibecoding/main/CR.md")
        val connection = url.openConnection()
        val content = connection.getInputStream().reader().use { it.readText() }
        file("CR_DOWNLOADED.md").writeText(content)
        println("SUCCESSFULLY DOWNLOADED CR.md")
    }
}
