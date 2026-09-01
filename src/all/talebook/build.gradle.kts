import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets {
        named("test") {
            java.directories.add("test")
            kotlin.directories.add("test")
        }
    }
}

tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach {
    enabled = false
}

keiyoushi {
    name = "Talebook"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl {
            custom("https://talebook.example.com")
        }
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
    testImplementation(libs.tachiyomi.lib.v16)
}
