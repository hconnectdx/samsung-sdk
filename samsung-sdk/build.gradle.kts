import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
    `maven-publish`
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(FileInputStream(file))
}

val libVersion = "1.0.2"

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                maybeCreate("java").apply {
                    option("lite")
                }
            }
        }
    }
}

android {
    namespace = "kr.co.hconnect.samsung_sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    compileOnly(files("libs/samsung-health-sensor-api-1.4.1.aar"))

    api(libs.protobuf.javalite)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "kr.co.hconnect"
                artifactId = "samsung-sdk"
                version = libVersion
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/hconnectdx/samsung-sdk")
                credentials {
                    username = localProperties.getProperty("githubUsername") ?: System.getenv("GITHUB_USERNAME")
                    password = localProperties.getProperty("githubAccessToken") ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
