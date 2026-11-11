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

        // 측정 해석: 받아들이는 경우
        MeasurementCore.Reading r = MeasurementCore.parse(good405(), s, s);
        check(r.accepted != null && r.accepted == 5.15 && r.code.equals("DEVICE_RESULT") && !r.turbidity, "405 단독 측정값 수용");
        r = MeasurementCore.parse(good660(), old, old);
        check(r.accepted != null && r.accepted == 5.2, "660-520 측정값 수용");
        List<String> turbid = Arrays.asList(good405().get(0) + "  <- 520도 줄어듦: 가림·탁도 의심", good405().get(1));
        r = MeasurementCore.parse(turbid, s, s);
        check(r.accepted != null && r.turbidity && r.message.contains("탁도"), "탁도 의심은 값과 함께 경고");

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
        check(r.accepted != null && r.accepted == 5.15 && r.code.equals("DEVICE_RESULT") && r.message.contains("범위는 확인하지 못했습니다"),
            "PC에서 저장한 검량식은 농도를 표시하고 범위 미확인을 알림");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{3.64, 5.72});
        check(r.accepted != null && r.accepted == 5.15 && !r.message.contains("확인하지 못했습니다"), "검량 범위 안은 그대로 표시");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), device, device), new double[]{5.2, 5.72});
        check(r.accepted == null && r.code.equals("OUTSIDE_CALIBRATION_RANGE") && r.reported == 5.15 && r.message.contains("5.20~5.72"),
            "검량 범위 밖은 보류");
        r = MeasurementCore.applyRange(MeasurementCore.parse(good405(), status405(false), status405(false)), null);
        check(r.accepted == null && r.code.equals("NO_MODEL") && !r.message.contains("확인하지 못했습니다"), "이미 보류된 측정은 그대로");

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
