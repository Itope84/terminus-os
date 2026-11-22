pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // agent-webview, built by JitPack from github.com/aesrael/agent-webview-android; nothing else comes from here.
        maven("https://jitpack.io") { content { includeGroup("com.github.aesrael.agent-webview-android") } }
    }
}
rootProject.name = "terminus"
include(":app")
