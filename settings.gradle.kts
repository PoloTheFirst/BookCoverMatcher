// SPDX-License-Identifier: GPL-3.0-or-later

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
        // MSAL's `com.microsoft.identity:common` depends at runtime on
        // `com.microsoft.device.display:display-mask`, which Microsoft publishes only to its public
        // Duo SDK feed, not to Maven Central (repo1 returns 404 for it). The `content` filter keeps
        // this repository from serving any other group, so it cannot shadow google()/mavenCentral().
        // See historical_prompt/v2.6.0-dev.1-ag.md §20.2.
        maven {
            url = uri(
                "https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1",
            )
            content { includeGroup("com.microsoft.device.display") }
        }
    }
}

rootProject.name = "BookCoverMatcher"
include(":app")
