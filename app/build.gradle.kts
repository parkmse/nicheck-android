import java.util.Properties

plugins {
    id("com.android.application")
}

// 배포용 서명 정보. 프로젝트 맨 위의 keystore.properties에서 읽는다(.gitignore로 제외됨).
// 이 파일이 없으면 release 빌드는 서명 없이 만들어진다.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "kr.ac.cau.nichecker"
    compileSdk = 37

    defaultConfig {
        applicationId = "kr.ac.cau.nichecker"
        // Android 8.0 이상.
        minSdk = 26
        targetSdk = 35
        // 새 APK를 배포할 때마다 versionCode를 1씩 올린다. 올리지 않으면 덮어 설치되지 않는다.
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":usbserial"))
    testImplementation("junit:junit:4.13.2")
}
