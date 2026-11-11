// 모든 모듈에 공통인 플러그인 버전만 선언한다. 모듈별 설정은 각 모듈의 build.gradle.kts에 둔다.
// Android Gradle 플러그인 9.5.x는 Android Studio 2026.2.2(Rabbit 2)와 Gradle 9.6 이상에 대응한다.
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
}
