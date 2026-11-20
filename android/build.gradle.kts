plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}

// Optional isolated output directory for CI or workspaces with filesystem synchronization.
providers.gradleProperty("orderflow.buildRoot").orNull?.let { outputRoot ->
    require(java.io.File(outputRoot).isAbsolute) { "orderflow.buildRoot must be an absolute path" }
    allprojects { layout.buildDirectory.set(java.io.File(outputRoot, project.name)) }
}
