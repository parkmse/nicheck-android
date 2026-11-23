package kr.ac.cau.nichecker;

import java.util.*;

/**
 * MeasurementCore 점검. 장비·USB 없이 PC에서 실행한다.
 * 응답 줄은 팀 최종 펌웨어(2026-11-11 빌드)의 출력 형식에 맞춰 직접 쓴 것이다.
 * 검량 점검 하나만 팀원이 2026-11-10~11에 실제로 잰 표준액 기록을 쓴다.
 */
public class CoreTest {
    static int n = 0;

    static void check(boolean ok, String label) {
        n++;
        if (!ok) throw new AssertionError(label);
    }

    /** 최종 펌웨어: 405 단독 계산. m·c는 팀원이 11/11 00:11에 저장한 값(기기는 소수 5자리로 보여 줌). */
    static MeasurementCore.Status status405(boolean saved) {
        return MeasurementCore.status(Arrays.asList(
            "작업자: 홍길동", "센서: OK", "LCD: I2C 16x2",
            "증류수 기준: 405 10535 / 520 1200 / 660 673",
            "검량선: m = 0.12653, c = 0.00003 " + (saved ? "(cal로 저장됨)" : "(기본 추정치)"),
            "설정: 게인 128X, 적분 50 ms, 6회 평균, 계산 파장 405 nm 단독"));
    }

    /** 최종 펌웨어의 측정 응답: 농도 소수 3자리, 흡광도 소수 4자리. */
    static List<String> good405() {
        return Arrays.asList(
            "농도 5.150 g/L (99%, OK)  흡광도 405 0.6517 / 520 0.0003 / 660 0.2570",
            "0\t2345\t1199\t373");
    }

    /** 이전 배포 펌웨어: 660 − 520 계산, 농도 소수 2자리, 흡광도 소수 3자리. */
    static MeasurementCore.Status status660(boolean saved) {
        return MeasurementCore.status(Arrays.asList(
            "센서: OK", "LCD: I2C 16x2",
            "증류수 기준: 405 1000 / 520 1000 / 660 1000",
            "검량선: m = 0.03000, c = 0.00100 " + (saved ? "(cal로 저장됨)" : "(기본 추정치)"),
            "설정: 게인 16X, 적분 50 ms, 6회 평균, 계산 파장 660 nm"));
    }

    static List<String> good660() {
        return Arrays.asList(
            "농도 5.20 g/L (100%, OK)  흡광도 405 0.100 / 520 0.020 / 660 0.177",
            "0\t900\t950\t600");
    }

    static boolean rejects(List<MeasurementCore.Point> points) {
        try {
            MeasurementCore.fit(points);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        // 상태 해석
        MeasurementCore.Status s = status405(true);
        check(s.sensor && s.zero && s.modelReady() && s.gain.equals("128") && s.operator.equals("홍길동"), "최종 펌웨어 상태 해석");
        check(s.calcKnown() && s.single && s.calcId().equals("405") && s.calcLabel().equals("A405"), "405 단독 계산 인식");
        check(s.response(new double[]{.6517, .0003, .257}) == .6517, "405 단독은 A405만 사용");
        MeasurementCore.Status old = status660(true);
        check(old.calcKnown() && !old.single && old.calcId().equals("660-520"), "이전 펌웨어의 660-520 계산 인식");
        check(Math.abs(old.response(new double[]{.1, .02, .177}) - .157) < 1e-12, "660-520은 A660 − A520 사용");
        MeasurementCore.Status none = MeasurementCore.status(Arrays.asList("작업자: 없음 -> w 이름", "센서: OK", "증류수 기준: 없음 -> 증류수 넣고 z"));
        check(none.operator.isEmpty() && !none.zero && !none.calcKnown(), "작업자·영점·계산 방식 없음 인식");

        // 0.9.0: 11/24 펌웨어는 상태를 "계산 방식 405", "405+520", "405-520"으로 보낸다. m·c는 11/23 검량선 기록의 값.
        MeasurementCore.Status m405 = MeasurementCore.status(Arrays.asList("센서: OK", "증류수 기준: 405 7252 / 520 843 / 660 1305",
            "검량선: m = 0.10820, c = -0.02647 (cal로 저장됨)", "설정: 게인 32X, 적분 50 ms, 6회 평균, 계산 방식 405, LED 예열 켜짐"));
        check(m405.calcKnown() && m405.single && m405.calcId().equals("405") && m405.gain.equals("32") && m405.modelReady(), "새 표기의 405 단독 인식");
        MeasurementCore.Status plus = MeasurementCore.status(Arrays.asList("센서: OK", "증류수 기준: 405 7252 / 520 843 / 660 1305",
            "검량선: m = 0.10498, c = -0.00437 (cal로 저장됨)", "설정: 게인 32X, 적분 50 ms, 6회 평균, 계산 방식 405+520, LED 예열 켜짐"));
        check(plus.calcKnown() && !plus.single && plus.calcId().equals("405+520") && plus.calcLabel().equals("A405 + 0.76×A520")
            && Math.abs(plus.response(new double[]{.5778, .0159, .2352}) - (.5778 + .76 * .0159)) < 1e-12, "405+520은 A405 + 0.76×A520 사용");
        MeasurementCore.Status minus = MeasurementCore.status(Arrays.asList("센서: OK", "설정: 게인 32X, 적분 50 ms, 6회 평균, 계산 방식 405-520, LED 예열 켜짐"));
        check(minus.calcId().equals("405-520") && Math.abs(minus.response(new double[]{.5778, .0159, .2352}) - .5619) < 1e-12, "405-520은 A405 − A520 사용");
        MeasurementCore.Reading rm = MeasurementCore.parse(Arrays.asList("농도 5.585 g/L (107%, OK)  흡광도 405 0.5778 / 520 0.0159 / 660 0.2352", "0\t1917\t813\t759"), m405, m405);
        check(rm.accepted != null && rm.accepted == 5.585, "새 표기의 405 단독 측정값 수용");
        rm = MeasurementCore.parse(Arrays.asList("농도 5.661 g/L (109%, OK)  흡광도 405 0.5778 / 520 0.0159 / 660 0.2352", "0\t1917\t813\t759"), plus, plus);
        check(rm.accepted != null && rm.accepted == 5.661, "405+520 측정값 수용");
        rm = MeasurementCore.parse(Arrays.asList("농도 5.585 g/L (107%, OK)  흡광도 405 0.5778 / 520 0.0159 / 660 0.2352", "0\t1917\t813\t759"), plus, plus);
        check(rm.accepted == null && rm.code.equals("MODEL_MISMATCH"), "계산 방식과 안 맞는 농도는 보류");

        // 측정 해석: 받아들이는 경우
        MeasurementCore.Reading r = MeasurementCore.parse(good405(), s, s);
        check(r.accepted != null && r.accepted == 5.15 && r.code.equals("DEVICE_RESULT") && !r.check520, "405 단독 측정값 수용");
        r = MeasurementCore.parse(good660(), old, old);
        check(r.accepted != null && r.accepted == 5.2, "660-520 측정값 수용");

        // 520 확인: 흡광도가 ±0.05를 넘으면 농도는 두고 "확인 필요". 펌웨어가 붙이는 문구가 아니라 숫자로 판단한다.
        String line405 = "농도 5.150 g/L (99%, OK)  흡광도 405 0.6517 / 520 ";
        r = MeasurementCore.parse(Arrays.asList(line405 + "0.0600 / 660 0.2570  <- 520도 줄어듦: 가림·탁도 의심", good405().get(1)), s, s);
        check(r.accepted != null && r.check520 && r.message.contains("탁도"), "520이 높으면 값과 함께 탁도 안내");
        check(MeasurementCore.verdict(r.accepted, 4.2, 6.2, r.absorbance).equals(MeasurementCore.CHECK)
            && MeasurementCore.verdict(r.accepted, null, null, r.absorbance).equals(MeasurementCore.CHECK), "520이 높으면 범위와 관계없이 확인 필요");
        r = MeasurementCore.parse(Arrays.asList(line405 + "-0.0600 / 660 0.2570", good405().get(1)), s, s);
        check(r.accepted != null && r.check520 && r.message.contains("영점")
            && MeasurementCore.verdict(r.accepted, 4.2, null, r.absorbance).equals(MeasurementCore.CHECK), "520이 음수면 영점 안내와 확인 필요");
        r = MeasurementCore.parse(Arrays.asList(line405 + "0.0500 / 660 0.2570", good405().get(1)), s, s);
        check(!r.check520 && MeasurementCore.verdict(r.accepted, 4.2, null, r.absorbance).equals(MeasurementCore.NORMAL)
            && MeasurementCore.verdict(null, 4.2, null, r.absorbance).isEmpty(), "520이 문턱값 이하면 범위 판정 그대로");

        // 새 펌웨어(LED 예열 추가)의 상태 줄도 같은 방식으로 읽힌다.
        MeasurementCore.Status warm = MeasurementCore.status(Arrays.asList(
            "작업자: 홍길동", "센서: OK", "LCD: I2C 16x2", "증류수 기준: 405 10535 / 520 1200 / 660 673",
            "검량선: m = 0.12653, c = 0.00003 (cal로 저장됨)",
            "설정: 게인 128X, 적분 50 ms, 6회 평균, 계산 파장 405 nm 단독, LED 예열 켜짐"));
        check(warm.modelReady() && warm.zero && warm.calcId().equals("405") && warm.gain.equals("128")
            && MeasurementCore.parse(good405(), warm, warm).accepted != null, "LED 예열이 붙은 상태 줄 해석");

        // 채움 비교: 같은 시료를 다시 채워 잰 값들의 평균과 차이
        double[] fs = MeasurementCore.fillSummary(Arrays.asList(5.10, 5.20));
        check(Math.abs(fs[0] - 5.15) < 1e-12 && Math.abs(fs[1] - 0.10) < 1e-12 && !(fs[1] > MeasurementCore.FILL_DIFF_LIMIT + 1e-9), "채움 2회 평균과 차이");
        fs = MeasurementCore.fillSummary(Arrays.asList(5.10, 5.30, 5.20));
        check(Math.abs(fs[0] - 5.20) < 1e-12 && Math.abs(fs[1] - 0.20) < 1e-12 && fs[1] > MeasurementCore.FILL_DIFF_LIMIT, "채움 3회는 전체 평균과 최대 차이");
        check(MeasurementCore.fillSummary(new ArrayList<Double>()) == null, "채움 값이 없으면 요약 없음");

        // 눈금 막대: 기준 5.2일 때 3.12(왼쪽 끝)~6.24(오른쪽 끝). 기준은 2/3 지점, 밖은 끝에 붙는다.
        check(Math.abs(MeasurementCore.gaugePosition(5.2, 5.2) - 2.0 / 3) < 1e-12 && Math.abs(MeasurementCore.gaugePosition(4.2, 5.2) - 1.08 / 3.12) < 1e-12, "눈금 막대에서 기준과 하한의 자리");
        check(MeasurementCore.gaugePosition(1.0, 5.2) == 0 && MeasurementCore.gaugePosition(18.1, 5.2) == 1
            && MeasurementCore.gaugePosition(Double.NaN, 5.2) == 0 && MeasurementCore.gaugePosition(5.2, 0) == 0, "눈금 밖과 잘못된 값은 끝에 붙임");

        // 영점 경과: 5분이 지났거나 이번 연결에서 재지 않았으면 측정 전에 다시 잴지 묻는다.
        check(MeasurementCore.zeroMinutes(-1, 1000) == -1 && MeasurementCore.zeroMinutes(0, 299_999) == 4 && MeasurementCore.zeroMinutes(600_000, 0) == 0, "영점 후 지난 분(재지 않음, 내림, 시계가 뒤로 간 경우)");
        check(MeasurementCore.zeroWarning(0, 299_999) == null && MeasurementCore.zeroWarning(0, 300_000).contains("5분") && MeasurementCore.zeroWarning(-1, 0).contains("언제 잰 것인지"), "영점 5분 경과와 미측정 안내");

        // 측정 해석: 보류하는 경우
        r = MeasurementCore.parse(good660(), s, s);
        check(r.accepted == null && r.code.equals("MODEL_MISMATCH"), "계산 방식이 다른 응답 보류");
        r = MeasurementCore.parse(Arrays.asList("농도 10.000 g/L (192%, HIGH)  흡광도 405 0.6517 / 520 0.0003 / 660 0.2570", "0\t2345\t1199\t373"), s, s);
        check(r.accepted == null && r.code.equals("MODEL_MISMATCH"), "검량식과 안 맞는 농도 보류");
        MeasurementCore.Status unsaved = status405(false);
        r = MeasurementCore.parse(good405(), unsaved, unsaved);
        check(r.accepted == null && r.code.equals("NO_MODEL"), "기본 추정치는 검량식으로 인정하지 않음");
        List<String> saturated = new ArrayList<>(good405());
        saturated.add(0, "SATURATED: LED 저항을 키우거나 게인을 낮추세요");
        r = MeasurementCore.parse(saturated, s, s);
        check(r.accepted == null && r.code.equals("SATURATED"), "포화 시 농도 보류");
        MeasurementCore.Status changed = status405(true);
        changed.gain = "64";
        r = MeasurementCore.parse(good405(), s, changed);
        check(r.accepted == null && r.code.equals("SETTINGS_CHANGED"), "측정 중 게인 변경 보류");
        r = MeasurementCore.parse(good405(), s, old);
        check(r.accepted == null && r.code.equals("SETTINGS_CHANGED"), "측정 중 계산 방식 변경 보류");
        r = MeasurementCore.parse(Arrays.asList(good405().get(0), "0\t2345\t0\t373"), s, s);
        check(r.accepted == null && r.code.equals("INVALID_SIGNAL"), "신호 0 보류");
        r = MeasurementCore.parse(Arrays.asList("농도 계산 안 됨: 증류수 기준 없음 -> 증류수 넣고 z", "0\t2345\t1199\t373"), s, s);
        check(r.accepted == null && r.code.equals("DEVICE_ERROR"), "기기 계산 오류 보류");
        MeasurementCore.Status unknown = status405(true);
        unknown.primary = -1;
        r = MeasurementCore.parse(good405(), unknown, unknown);
        check(r.accepted == null && r.code.equals("UNKNOWN_CALC"), "계산 방식을 모르면 보류");

        // 검량
        List<MeasurementCore.Point> p = new ArrayList<>();
        for (double c : new double[]{3.64, 5.2, 6.24}) for (int j = 0; j < 3; j++) p.add(new MeasurementCore.Point(c, .03 * c + .001));
        MeasurementCore.Fit f = MeasurementCore.fit(p);
        check(Math.abs(f.m - .03) < 1e-10 && Math.abs(f.c - .001) < 1e-10 && f.r2 > .999, "알려진 검량식 복원");
        check(rejects(p.subList(0, 6)), "농도 2종으로는 검량 거부");
        List<MeasurementCore.Point> flat = new ArrayList<>();
        for (double c : new double[]{3, 5, 6}) for (int j = 0; j < 3; j++) flat.add(new MeasurementCore.Point(c, .2));
        check(rejects(flat), "농도에 반응이 없으면 검량 거부");

        // 실제 기록: 팀원이 잰 표준70·100·110의 A405 9점. PC 프로그램이 저장한 값은 m = 0.126529, c = 0.000026, R² = 0.9996.
        double[] a405 = {.462, .461, .461, .655, .655, .655, .727, .726, .725};
        double[] conc = {3.64, 3.64, 3.64, 5.2, 5.2, 5.2, 5.72, 5.72, 5.72};
        List<MeasurementCore.Point> real = new ArrayList<>();
        for (int j = 0; j < 9; j++) real.add(new MeasurementCore.Point(conc[j], a405[j]));
        f = MeasurementCore.fit(real);
        check(Math.abs(f.m - .126529) < 5e-7 && Math.abs(f.c - .000026) < 5e-7 && Math.abs(f.r2 - .9996) < 5e-5 && f.min == 3.64 && f.max == 5.72, "PC 프로그램과 같은 검량식");

        // 검량식 출처: 휴대폰이 적어 둔 (계산 방식, m, c)와 기기의 검량식이 같으면 그때의 검량 범위를 안다.
        // 기기는 m·c를 소수 5자리로 보여 주므로, 방금 맞춘 값(소수 6자리)과 1e-5 안에서 같으면 같은 식으로 본다.
        MeasurementCore.Status device = status405(true);
        check(MeasurementCore.sameModel(device, "405", f.m, f.c), "휴대폰에서 저장한 검량식 인식");
        check(!MeasurementCore.sameModel(device, "405", .13, f.c) && !MeasurementCore.sameModel(device, "405", f.m, .001)
            && !MeasurementCore.sameModel(device, "660-520", f.m, f.c), "다른 곳에서 저장한 검량식 구분");
        check(!MeasurementCore.sameModel(device, "", Double.NaN, Double.NaN) && !MeasurementCore.sameModel(status405(false), "405", f.m, f.c),
            "저장 기록 없음·기본 추정치는 같은 식이 아님");
        MeasurementCore.Status rezeroed = MeasurementCore.status(Arrays.asList(
            "센서: OK", "증류수 기준: 405 10410 / 520 1188 / 660 669",
            "검량선: m = 0.12653, c = 0.00003 (cal로 저장됨)",
            "설정: 게인 64X, 적분 50 ms, 6회 평균, 계산 파장 405 nm 단독"));
        check(!rezeroed.fingerprint().equals(device.fingerprint()) && MeasurementCore.sameModel(rezeroed, "405", f.m, f.c),
            "영점·게인을 바꿔도 같은 검량식");

        // 검량 범위 확인: 5.15 g/L 측정값
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), null);
        check(r.accepted != null && r.accepted == 5.15 && r.code.equals("DEVICE_RESULT") && r.message.contains("검량 범위를 모릅니다"),
            "PC에서 저장한 검량식은 농도를 표시하고 범위 미확인을 알림");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{3.64, 5.72});
        check(r.accepted != null && r.accepted == 5.15 && !r.message.contains("검량 범위를 모릅니다"), "검량 범위 안은 그대로 표시");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{5.2, 5.72});
        check(r.accepted != null && r.accepted == 5.15, "검량 범위를 0.2 g/L 안쪽으로 벗어난 값은 표시");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{5.4, 5.72});
        check(r.accepted == null && r.code.equals("OUTSIDE_CALIBRATION_RANGE") && r.reported == 5.15 && r.message.contains("5.40~5.72"),
            "검량 범위를 0.2 g/L 넘게 벗어나면 보류");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{3.0, 4.9});
        check(r.accepted == null && r.code.equals("OUTSIDE_CALIBRATION_RANGE"), "위쪽으로 0.2 g/L 넘게 벗어나도 보류");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), status405(false), status405(false)), null);
        check(r.accepted == null && r.code.equals("NO_MODEL") && !r.message.contains("검량 범위를 모릅니다"), "이미 보류된 측정은 그대로");

        // 기준 대비 %
        check(Math.abs(MeasurementCore.percent(5.2, 5.2) - 100) < 1e-12 && Math.abs(MeasurementCore.percent(5.15, 5.2) - 99.0385) < 1e-4, "기준 대비 % 계산");
        check(Double.isNaN(MeasurementCore.percent(5.2, 0)) && Double.isNaN(MeasurementCore.percent(Double.NaN, 5.2)), "기준이 0이거나 값이 없으면 % 없음");

        // 범위 판정: 하한·상한을 설정했을 때만
        check(MeasurementCore.verdict(5.2, null, null).isEmpty() && MeasurementCore.verdict(null, 4.2, 6.2).isEmpty(), "범위 미설정·농도 보류는 판정 안 함");
        check(MeasurementCore.verdict(5.2, 4.2, 6.2).equals(MeasurementCore.NORMAL) && MeasurementCore.verdict(4.2, 4.2, 6.2).equals(MeasurementCore.NORMAL)
            && MeasurementCore.verdict(6.2, 4.2, 6.2).equals(MeasurementCore.NORMAL), "범위 안과 경계값은 정상");
        check(MeasurementCore.verdict(4.19, 4.2, 6.2).equals(MeasurementCore.LOW) && MeasurementCore.verdict(6.21, 4.2, 6.2).equals(MeasurementCore.HIGH), "범위 밖은 경고");
        check(MeasurementCore.verdict(3.0, 4.2, null).equals(MeasurementCore.LOW) && MeasurementCore.verdict(9.0, 4.2, null).equals(MeasurementCore.NORMAL)
            && MeasurementCore.verdict(9.0, null, 6.2).equals(MeasurementCore.HIGH), "하한 또는 상한만 설정");

        // 0.8.0: 채움 평균으로 판정. 4.16 → 4.22, 하한 4.2이면 마지막 값은 정상이지만 평균 4.19는 낮음이다.
        double[] mean = MeasurementCore.fillSummary(Arrays.asList(4.16, 4.22));
        double[] clean = {.52, .0, .2};
        check(MeasurementCore.verdict(4.22, 4.2, null, clean, true).equals(MeasurementCore.NORMAL)
            && Math.abs(mean[0] - 4.19) < 1e-9 && MeasurementCore.verdict(mean[0], 4.2, null, clean, true).equals(MeasurementCore.LOW), "다시 채운 값은 평균으로 판정");

        // 0.8.0: 검량 범위를 모르면 정상이라고 하지 않는다. 낮음·높음·확인 필요는 그대로 알린다.
        check(MeasurementCore.verdict(9.0, 4.2, null, clean, false).equals(MeasurementCore.RANGE_UNKNOWN)
            && MeasurementCore.verdict(5.2, 4.2, null, clean, false).equals(MeasurementCore.RANGE_UNKNOWN)
            && MeasurementCore.verdict(5.2, 4.2, null, clean, true).equals(MeasurementCore.NORMAL), "검량 범위를 모르면 정상 대신 범위 모름");
        check(MeasurementCore.verdict(3.0, 4.2, null, clean, false).equals(MeasurementCore.LOW)
            && MeasurementCore.verdict(5.2, 4.2, null, new double[]{.52, .08, .2}, false).equals(MeasurementCore.CHECK)
            && MeasurementCore.verdict(5.2, null, null, clean, false).isEmpty(), "범위를 몰라도 낮음·확인 필요는 그대로, 기준이 없으면 판정 안 함");

        // 0.8.0: 관리 화면에서 입력한 검량 범위에는 여유 0.2를 주지 않는다. 5.15 g/L 측정값.
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{4.0, 6.5}, 0);
        check(r.accepted != null && r.accepted == 5.15, "입력한 검량 범위 안은 표시");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{5.2, 6.5}, 0);
        check(r.accepted == null && r.code.equals("OUTSIDE_CALIBRATION_RANGE") && r.message.contains("5.20~6.50") && !r.message.contains("0.2"),
            "입력한 검량 범위는 조금만 벗어나도 보류");
        check(MeasurementCore.checkRange(4.0, 6.5) == null && MeasurementCore.checkRange(null, 6.5) != null
            && MeasurementCore.checkRange(6.5, 4.0) != null && MeasurementCore.checkRange(-1.0, 6.5) != null, "검량 범위 입력 검사");

        // 기준값 설정 검사
        check(MeasurementCore.checkLimits(5.2, null, null) == null && MeasurementCore.checkLimits(5.2, 4.2, 6.2) == null, "올바른 기준값 통과");
        check(MeasurementCore.checkLimits(null, null, null) != null && MeasurementCore.checkLimits(0.0, null, null) != null
            && MeasurementCore.checkLimits(5.2, 6.2, 4.2) != null && MeasurementCore.checkLimits(5.2, -1.0, null) != null, "잘못된 기준값 거부");

        // 선택 입력 숫자
        check(MeasurementCore.optionalNumber("") == null && MeasurementCore.optionalNumber("  ") == null && MeasurementCore.optionalNumber(" 55.5 ") == 55.5, "빈칸과 숫자 입력");
        boolean bad = false;
        try { MeasurementCore.optionalNumber("55도"); } catch (NumberFormatException e) { bad = true; }
        check(bad, "숫자가 아닌 입력 거부");
        check(MeasurementCore.plain(5.2).equals("5.2") && MeasurementCore.plain(5.0).equals("5") && MeasurementCore.plain(0.05).equals("0.05")
            && MeasurementCore.plain(3.0E-4).equals("0.0003") && MeasurementCore.plain(2345).equals("2345"), "숫자 표기");

        // 채취 시각
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 11, 12, 15, 30);
        check(MeasurementCore.sampledAt("", now).isEmpty() && MeasurementCore.sampledAt("14:05", now).equals("2026-11-12 14:05")
            && MeasurementCore.sampledAt("9:05", now).equals("2026-11-12 09:05") && MeasurementCore.sampledAt("2026-11-11 23:50", now).equals("2026-11-11 23:50"), "채취 시각 정리");
        bad = false;
        for (String wrong : new String[]{"25:00", "오후 2시", "2026-13-01 10:00", "2026-11-12"}) {
            try { MeasurementCore.sampledAt(wrong, now); bad = false; break; } catch (IllegalArgumentException e) { bad = true; }
        }
        check(bad, "잘못된 채취 시각 거부");

        check(MeasurementCore.csv("a,\"b").equals("\"a,\"\"b\""), "CSV 따옴표 처리");
        System.out.println("PASS " + n + " checks; no Android device/USB opened.");
    }
}
