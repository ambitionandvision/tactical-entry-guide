plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ambitionandvision.tactical"
    compileSdk = 34

    // android.car (CarUxRestrictionsManager). 런타임에 차량 OS 가 제공한다.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "io.github.ambitionandvision.tactical"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("boolean", "SHOW_DEMO_CONTROLS", "true")
    }

    buildTypes {
        release {
            // 공모전 제출용: 실행 가능한 release 빌드. 디버그 키 서명, 난독화 없음.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // 테스트가 assets 의 실제 zone JSON·GPX 를 읽는다.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    testImplementation("junit:junit:4.13.2")
    // 안드로이드 stub 의 org.json 은 JVM 테스트에서 동작하지 않으므로 실제 구현을 쓴다.
    testImplementation("org.json:json:20231013")
}
