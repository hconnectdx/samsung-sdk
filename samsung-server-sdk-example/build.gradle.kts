plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.co.hconnect.samsung_server_sdk_example"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.hconnect.samsung_server_sdk_example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // debug 기본값 (stg)
        buildConfigField("String", "API_URL",       "\"https://mapi-stg.health-on.co.kr/\"")
        buildConfigField("String", "CLIENT_ID",     "\"3270e7da-55b1-4dd4-abb9-5c71295b849b\"")
        buildConfigField(
            "String", "CLIENT_SECRET",
            "\"eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpbmZyYSI6IkhlYWx0aE9uLVN0YWdpbmciLCJjbGllbnQtaWQiOiIzMjcwZTdkYS01NWIxLTRkZDQtYWJiOS01YzcxMjk1Yjg0OWIifQ.u0rBK-2t3l4RZ113EzudZsKb0Us9PEtiPcFDBv--gYdJf9yZJQOpo41XqzbgSdDa6Z1VDrgZXiOkIZOTeeaEYA\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // release 는 live 서버
            buildConfigField("String", "API_URL",       "\"https://mapi.health-on.co.kr/\"")
            buildConfigField("String", "CLIENT_ID",     "\"659c95fd-900a-4a9a-8f61-1888334a3c7b\"")
            buildConfigField(
                "String", "CLIENT_SECRET",
                "\"eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpbmZyYSI6IkhlYWx0aE9uLUxpdmUiLCJjbGllbnQtaWQiOiI2NTljOTVmZC05MDBhLTRhOWEtOGY2MS0xODg4MzM0YTNjN2IifQ.GV8Fg5pY-08GlZI0UUFLIqtrmlwnU7kQ-soN6VFlj_usXBex7mv3-vjkAZxV5Yb2MMecifUqwOQpikyirX9aBw\""
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":samsung-server-sdk"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}