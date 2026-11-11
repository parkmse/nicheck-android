package kr.ac.cau.nichecker;

import org.junit.Test;

/**
 * 기존 CoreTest의 점검을 Android Studio와 Gradle의 단위 테스트로 실행한다.
 * 합성 입력만 사용하며 실제 장비나 USB는 열지 않는다.
 */
public class MeasurementCoreTest {
    @Test
    public void corePolicyChecksPass() {
        CoreTest.main(new String[0]);
    }
}
