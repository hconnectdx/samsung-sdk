import java.io.FileInputStream
import java.util.Properties

include(":samsung-sdk")


include(":samsung_sdk_example")


include(":samsung-server-sdk-example")


pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// local.properties 파일 읽기
var localProperties = Properties()
var localFile = File(rootDir, "local.properties")
if (localFile.exists()) {
    localProperties.load(FileInputStream(localFile))
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
//        maven { url 'https://jitpack.io' }
        maven {
            url = uri("https://maven.pkg.github.com/hconnectdx/bluetooth-sdk-android-v2")
            credentials {
                username = localProperties.getProperty("githubUsername")
                password = localProperties.getProperty("githubAccessToken")
            }
        }
    }
}

rootProject.name = "SamsungSDK"
include(":samsung-server-sdk")
