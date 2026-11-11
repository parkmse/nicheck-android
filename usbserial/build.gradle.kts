// usb-serial-for-android v3.10.0 소스를 그대로 담은 라이브러리 모듈. 출처와 변경 내용은 PROVENANCE.json 참고.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.hoho.android.usbserial"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
