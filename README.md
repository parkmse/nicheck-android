# 니켈체크 / NiCheck — 안드로이드 앱

안드로이드 휴대폰에 측정기(ESP32 + AS7341)를 USB로 연결해 니켈 도금액 농도를 재고 기록하는 앱입니다. 현장 작업자가 PC 없이 휴대폰만으로 농도를 확인할 수 있게 하는 것이 목적입니다. 현재 버전은 0.2.0이며 시제품 단계입니다. 측정 정확도는 따로 검증해야 합니다.

- 설치 파일(APK): GitHub 저장소의 Releases에서 받습니다.
- 쓰는 방법: [docs/UserGuide_KO.md](docs/UserGuide_KO.md)
- 측정기와의 통신 형식: [docs/FirmwareProtocol_KO.md](docs/FirmwareProtocol_KO.md)
- 빌드 방법과 개발 기록: 이 문서의 아래쪽

처음에는 `20261109_NiChecker_Mobile_Release/android`의 v0.1.0 시제품 소스를 Android Studio(Gradle) 프로젝트로 옮긴 것에서 시작했습니다. 아래 기록은 그 뒤에 바뀐 내용을 날짜순으로 적은 것입니다.

## 구성

| 경로 | 내용 |
|---|---|
| `app/` | 앱 모듈. 화면 `MainActivity`, 응답 해석·계산 `MeasurementCore`, USB 연결 `UsbInstrument` |
| `app/src/test/` | `CoreTest` 점검을 JUnit으로 실행하는 단위 테스트 |
| `usbserial/` | usb-serial-for-android v3.10.0 소스(MIT). 출처는 `PROVENANCE.json` |
| `gradle/wrapper/` | Gradle 9.6.1 래퍼 |

- Android Gradle 플러그인 9.5.1, compileSdk 37, minSdk 26(Android 8.0), targetSdk 35
- 패키지 `kr.ac.cau.nichecker`, 버전 0.2.0 (versionCode 2)

## 기준 펌웨어

앱은 팀 최종 `nickel_rig` 펌웨어(한국어 텍스트 통신, 2026-11-11 빌드)에 맞춥니다. 이 펌웨어는 농도를 405 채널 단독(A405 = m × C + c)으로 계산합니다. 이전 배포 바이너리(A660 − A520)도 연결되며, 앱이 상태 응답에서 계산 방식을 읽어 구분합니다. JSON 통신을 쓰는 v04 펌웨어는 연결되지 않습니다.

통신 형식과 근거는 [docs/FirmwareProtocol_KO.md](docs/FirmwareProtocol_KO.md)에 있습니다.

## 여는 방법

**한글이 없는 경로로 복사한 뒤 여세요.** 현재 폴더 경로의 한글 때문에 Android 빌드 도구(aapt2)가 리소스 폴더를 열지 못하는 것을 확인했습니다.

1. 이 폴더 전체를 예: `C:\dev\NiCheck`로 복사
2. Android Studio → Open → `C:\dev\NiCheck`
3. 처음 열 때 Gradle 동기화가 Gradle 9.6.1과 빌드 플러그인을 내려받음
4. 기기를 연결하고 Run, 또는 Build → Generate Signed App Bundle or APK

## 확인한 범위 (2026-11-11)

Gradle을 내려받지 않고 설치된 SDK(API 37, build-tools 36.0.0)로 다음을 직접 실행했습니다.

- 리소스 컴파일·매니페스트 링크, 앱+USB 라이브러리 Java 11 컴파일, DEX 변환: 통과
- `CoreTest` 11개 점검: 통과 (합성 입력, 실제 장비·USB 미사용)

이 시점에는 Gradle 빌드를 실행하지 않았습니다. 이후 실행한 결과는 아래 "Gradle 빌드와 시험용 APK"에 있습니다.

### 최종 펌웨어 대응 후 (2026-11-11)

- `CoreTest` 23개 점검: 통과. 응답 줄은 최종 펌웨어의 출력 형식에 맞춰 직접 쓴 것이고, 검량 점검 하나는 팀원의 11/10~11 표준액 기록을 써서 PC 프로그램과 같은 m·c가 나오는지 확인합니다.
- 앱+USB 라이브러리 Java 11 컴파일(API 37 `android.jar`): 통과
- 리소스는 바꾸지 않아 다시 컴파일하지 않았습니다. 실제 보드·휴대폰 연결은 아직 확인하지 않았습니다.

### 기준 대비 %·기록 항목 추가 후 (2026-11-11)

추가한 기능:

- 측정 결과에 기준 대비 %를 함께 표시합니다. 기준 농도는 관리 화면에서 바꿉니다(기본 5.2 g/L).
- 범위 판정(정상 / 경고 · 낮음 / 경고 · 높음)은 관리 화면에 하한·상한을 넣었을 때만 표시합니다. 기업이 값을 확정하기 전에는 비워 둡니다. 표준액은 판정하지 않습니다.
- 시료 정보(시료 ID, 공장, 라인, 도금조, 시료 온도, MTO, 채취 시각)를 기록에 함께 저장합니다. 모두 선택 입력이고, 공장·라인·도금조는 앱을 다시 열어도 남습니다.
- 기준 농도 칸은 표준액 농도 또는 적정 결과를 넣는 칸입니다. 적정 결과는 측정할 때만 넣을 수 있고, 저장된 기록에 나중에 추가할 수는 없습니다.
- CSV 열이 28개로 늘었습니다. 이전 기록은 새 열이 빈칸으로 나옵니다.

확인한 범위:

- `CoreTest` 36개 점검: 통과
- 앱+USB 라이브러리 Java 11 컴파일: 통과
- 에뮬레이터(Medium_Phone_API_37.0)에 시험용 APK를 올려 확인: 시료 정보 입력·요약, 기준값 저장과 잘못된 범위 거부, 기록 목록 표시, CSV 내보내기(28열, 한글, 이전 기록 포함)
- 기록 목록과 CSV는 직접 써 넣은 시험 기록 3건으로 확인했습니다. 측정 → 저장 경로는 USB 장비가 필요해서 실행해 보지 못했습니다(컴파일만 확인).

### 기기에 저장된 검량식 사용 (2026-11-11)

작업자가 PC 없이 휴대폰만으로 농도를 확인할 수 있게, 앱이 기기에 저장된 검량식을 그대로 쓰도록 바꿨습니다.

- PC 프로그램에서 검량한 측정기를 휴대폰에 연결하면 농도가 바로 표시됩니다. 이전에는 휴대폰에서 다시 검량해야 했습니다.
- 영점을 다시 재거나 게인을 바꿔도 검량식은 유지됩니다(PC 프로그램과 같은 동작). 이전에는 영점·게인을 바꾸면 재검량을 요구했습니다.
- 앱은 검량 농도 범위를 아는 경우에만 범위 밖 농도를 보류합니다. 범위는 이 휴대폰에서 검량식을 저장했을 때만 압니다. PC 등 다른 곳에서 저장한 검량식은 화면과 기록에 "검량 범위 미확인"으로 표시하고 농도는 그대로 보여 줍니다.
- 기록의 `settings`에 `cal_range_g_l`(검량 범위, 모르면 null)을 함께 저장합니다. CSV 열은 그대로 28개입니다.
- 연결했을 때 기기에 영점이 남아 있으면, 언제 잰 영점인지 알 수 없으니 다시 재라고 안내합니다.
- 앱에서 검량식을 새로 저장하면 기기에 있던 검량식을 덮어씁니다. 저장 확인 창에 이 내용을 표시합니다.

확인한 범위:

- `CoreTest` 44개 점검: 통과. 검량식이 같은 식인지 구분하는 부분과 검량 범위 확인(범위 미확인이면 표시, 범위 밖이면 보류)을 포함합니다.
- 앱+USB 라이브러리 Java 11 컴파일: 통과
- 화면 동작은 실행해 보지 못했습니다. 바뀐 부분이 모두 장비가 연결된 뒤의 동작이라 에뮬레이터로는 확인할 수 없습니다.

### Gradle 빌드와 시험용 APK (2026-11-11)

명령줄에서 Gradle 빌드를 처음 실행했습니다.

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat assembleDebug testDebugUnitTest
```

- 빌드 성공(4분 27초, Gradle 9.6.1과 플러그인 내려받기 포함). `MeasurementCoreTest` 통과.
- 산출물: `app/build/outputs/apk/debug/app-debug.apk`. 전달용 사본은 `dist/NiCheck_v0.1.0_test_20261111b.apk`(990,416바이트)이며 `.gitignore`로 저장소에서 제외됩니다.
- SHA-256: `b14a2d41e7a3b0aae5d74deb93e88c6105dbde71aa507fba7a81ec0027c43c86`
- 파일 이름 끝의 `b`는 같은 날 두 번째 빌드라는 뜻입니다. 첫 빌드(`..._20261111.apk`)는 연결·영점 뒤에도 결과 칸에 "처리 중"이 남는 문제가 있어 고친 뒤 다시 빌드했고, 첫 파일은 지웠습니다.
- 서명: Android 디버그 키(이 PC에서 자동 생성). **시험용이며 배포용이 아닙니다.** 나중에 배포용 키로 서명한 APK는 이 시험용 위에 덮어 설치되지 않습니다. 시험용을 지우면 앱 안의 기록도 지워지므로 먼저 CSV로 내보내야 합니다.
- 권한: 인터넷 권한 없음. USB 호스트 기능 필요.
- 에뮬레이터(Medium_Phone_API_37.0)에 설치해 확인: 실행, 측정·기록·관리 화면 표시, 장비가 없을 때의 연결 안내. 충돌 없음.
- 실제 휴대폰 설치와 측정기 연결은 아직 확인하지 않았습니다.

### 보드 펌웨어 교체 (2026-11-11)

실제 휴대폰에서 앱을 연결했더니 "다른 펌웨어" 안내가 나왔습니다. PC에서 보드에 직접 물어본 결과 v04 펌웨어(`NICKEL_PROTO_20261109_V4`, JSON 통신)가 들어 있었습니다.

- 대상 보드: ESP32-D0WD-V3, 플래시 4MB, CP210x(COM3)
- 교체 전 플래시 전체 백업: `board_backup/ESP32_v04_flash_backup_20261111.bin` (4,194,304바이트, SHA-256 `a17043203241e7926c7582962567c9d7cc9a47a74107a7fbb63db11edb1ebc64`). `.gitignore`로 저장소에서 제외됩니다.
- Arduino IDE에 설치된 esptool 5.3.1로 팀 최종 펌웨어를 설치했습니다. 주소는 PC 프로그램과 같습니다(0x1000, 0x8000, 0xe000, 0x10000). 쓰기 후 해시 검증 통과. 파티션 표와 부트로더는 v04와 같아서 영점·검량식 저장 영역(NVS)은 그대로입니다.
- 설치 후 보드는 새 펌웨어로 부팅하지만 `SENSOR ERROR: AS7341 응답 없음 -> SDA 21 / SCL 22 배선 확인`을 출력하고 명령에 답하지 않습니다. I2C 장치가 하나도 잡히지 않았습니다. 센서 연결을 확인해야 합니다.
- v04로 되돌리는 명령(보드를 PC에 꽂은 상태):

```
esptool --chip esp32 --port COM3 --baud 460800 write-flash 0 board_backup\ESP32_v04_flash_backup_20261111.bin
```

### 실제 휴대폰·측정기 확인 (2026-11-11)

- 센서 미응답의 원인은 ESP32와 AS7341 사이 배선이 빠져 있던 것이었습니다. 11/2 결선도대로 다시 연결했습니다(3V3, GND, GPIO21 = SDA, GPIO22 = SCL).
- 팀원이 실제 안드로이드 휴대폰에 `NiCheck_v0.1.0_test_20261111b.apk`를 설치하고, 브레드보드에 연결한 ESP32(최종 펌웨어) + AS7341로 앱 동작을 확인했습니다. 결과는 "다 잘 된다"로 전달받았습니다.
- 항목별 결과(연결, 영점, 측정, 기록 저장, CSV 내보내기)와 휴대폰 기종은 따로 기록하지 않았습니다. 측정값의 정확도는 이 확인의 대상이 아닙니다.

### 배포용 빌드 준비 (2026-11-11)

- 버전을 0.2.0(versionCode 2)으로 올렸습니다. 관리 화면의 버전 표시는 설치된 앱에서 읽어 오므로 따로 고칠 필요가 없습니다.
- 다른 펌웨어를 만났을 때 안내 창에 기기가 실제로 보낸 내용(앞 80자)을 함께 표시합니다.
- 배포용 서명 정보는 프로젝트 맨 위의 `keystore.properties`에서 읽습니다. 이 파일과 `*.jks`는 `.gitignore`로 제외됩니다. 파일이 없으면 release 빌드는 서명 없이 만들어집니다.

`keystore.properties` 형식:

```
storeFile=C:/dev/keys/nicheck-release.jks
storePassword=(키 비밀번호)
keyAlias=nicheck
keyPassword=(키 비밀번호)
```

서명키 만들기(한 번만). 비밀번호는 실행하면 물어봅니다:

```
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore C:\dev\keys\nicheck-release.jks -alias nicheck -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NiCheck, O=CAU Production Quality Team 1, C=KR"
```

배포용 APK 빌드:

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat assembleRelease
```

산출물은 `app/build/outputs/apk/release/app-release.apk`입니다.

지켜야 할 것:

- 서명키 파일과 비밀번호는 프로젝트 폴더 밖에 두고, 팀원 두 명 이상이 따로 보관합니다. 잃어버리면 업데이트를 덮어 설치할 수 없습니다.
- 새 APK를 배포할 때마다 `app/build.gradle.kts`의 `versionCode`를 1씩 올립니다.

확인한 범위: 임시로 만든 키로 서명 설정과 release 빌드가 끝까지 되는 것을 확인했습니다(버전 0.2.0 / 2, 서명 검증 통과, 디버그 표시 없음, 단위 테스트 통과). 임시 키와 그 APK는 지웠습니다.

### 배포용 APK 0.2.0 (2026-11-11)

- 팀 서명키(`C:\dev\keys\nicheck-release.jks`, 별칭 `nicheck`)로 서명한 배포용 APK를 빌드했습니다.
- 파일: `dist/NiCheck_v0.2.0_20261111.apk` (700,503바이트)
- SHA-256: `64d1c9628c9e8c137be0ccb033b39123b7ec10c7f71e82e1471732145ce264df`
- 서명 인증서: `CN=NiCheck, O=CAU Production Quality Team 1, C=KR`, 인증서 SHA-256 `1ae90de970524b322e79bb6c1289fb191649ca0377e9cb6010d53b006d7ac112`. 이후 업데이트 APK도 이 값이 같아야 덮어 설치됩니다.
- 확인: 서명 검증 통과, 버전 0.2.0 / versionCode 2, 디버그 표시 없음, 인터넷 권한 없음, 단위 테스트 통과. 에뮬레이터에 설치해 실행했고 관리 화면에 "니켈체크 0.2.0"이 표시됩니다.
- 실제 휴대폰과 측정기로 확인한 것은 시험용 0.1.0 빌드입니다. 0.2.0 배포용으로는 아직 실제 기기에서 확인하지 않았습니다. 0.1.0 이후 바뀐 코드는 버전 표시와 다른 펌웨어 안내문뿐입니다.
- 시험용(디버그 서명)이 설치된 휴대폰에는 덮어 설치되지 않습니다. 시험용을 지운 뒤 설치합니다.

## 남은 일

- PC 프로그램에 있고 앱에 없는 기능: 반복 측정 평균, 영점 후 경과 시간 안내, 게인 자동 맞춤
- 저장된 기록에 적정 결과를 나중에 추가하는 기능
- 서명키 보관: `nicheck-release.jks`와 비밀번호를 팀원 두 명 이상이 프로젝트 폴더 밖에 따로 보관
- 배포용 0.2.0을 실제 휴대폰과 측정기에서 한 번 확인
- 실제 휴대폰 확인은 한 번 마쳤습니다. 항목별 점검표(`20261109_NiChecker_Mobile_Release/docs/PhoneCheck_KO.md`)에 따른 기록은 아직 없습니다.
- `app/src/main/assets/release_profile.json`은 0.1.0 때의 값이 남아 있고 코드에서 읽지 않습니다. 다음 버전에서 지우거나 고칩니다.
- 아이폰 지원: USB로는 불가능합니다. Wi-Fi 웹 화면이나 블루투스로 바꿔야 하고, 펌웨어 소스(`nickel_rig.ino`)가 필요합니다.
- 검량선 m·c는 재측정 전이라 앱에 확정 모델 없음
