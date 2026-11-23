package kr.ac.cau.nichecker;

import java.util.*;
import java.util.regex.*;

/**
 * 장치 응답 해석과 농도 계산 규칙. Android에 의존하지 않아 PC에서 단위 테스트할 수 있다.
 *
 * 기준 펌웨어: 팀 최종 nickel_rig (한국어 텍스트 통신, 2026-11-11 빌드). 형식은 docs/FirmwareProtocol_KO.md 참고.
 * 405·520·660은 펌웨어가 쓰는 채널 이름이다. 이 앱이 실제 LED 파장을 측정했다는 뜻이 아니다.
 */
public final class MeasurementCore {
    static final String N = "([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)";
    /** 펌웨어 채널 이름. 배열 순서가 신호·흡광도 배열의 순서와 같다. */
    public static final String[] CHANNELS = {"405", "520", "660"};

    private static final Pattern MODEL = Pattern.compile("m = " + N + ", c = " + N);
    private static final Pattern ZERO = Pattern.compile("405 " + N + " / 520 " + N + " / 660 " + N);
    private static final Pattern GAIN = Pattern.compile("게인 " + N + "X");
    private static final Pattern CALC = Pattern.compile("계산 파장 (\\d+) nm( 단독)?");
    private static final Pattern RESULT =
        Pattern.compile("농도 " + N + " g/L.*흡광도 405 " + N + " / 520 " + N + " / 660 " + N);

    /** 's' 명령의 응답. */
    public static final class Status {
        public boolean sensor, zero, saved;
        public String gain = "?", operator = "", lcd = "미확인";
        public double m = Double.NaN, c = Double.NaN;
        public double[] i0;
        /** 농도 계산의 주 채널 번호(0 = 405, 2 = 660). 응답에서 읽지 못하면 -1. */
        public int primary = -1;
        /** true면 주 채널 흡광도만 쓴다. false면 주 채널에서 520을 뺀다. */
        public boolean single;

        public boolean calcKnown() { return primary == 0 || primary == 2; }

        /** PC 프로그램의 검량선 기록 '계산' 칸과 같은 표기: "405" 또는 "660-520". */
        public String calcId() {
            if (!calcKnown()) return "?";
            return CHANNELS[primary] + (single ? "" : "-520");
        }

        /** 화면에 보여 주는 계산식 왼쪽 항: "A405" 또는 "A660 − A520". */
        public String calcLabel() {
            if (!calcKnown()) return "미확인";
            return "A" + CHANNELS[primary] + (single ? "" : " − A520");
        }

        /** 검량식 A = m × C + c 의 A. 계산 방식을 모르면 NaN. */
        public double response(double[] absorbance) {
            if (!calcKnown() || absorbance == null || absorbance.length < 3) return Double.NaN;
            return absorbance[primary] - (single ? 0 : absorbance[1]);
        }

        /** 영점·게인·계산 방식 중 하나라도 바뀌면 달라진다. 검량식이 어느 조건에서 만들어졌는지 구분하는 데 쓴다. */
        public String fingerprint() { return gain + "|" + Arrays.toString(i0) + "|" + calcId(); }

        public boolean modelReady() {
            return saved && Double.isFinite(m) && Math.abs(m) > 1e-9 && Double.isFinite(c);
        }
    }

    public static Status status(List<String> lines) {
        Status s = new Status();
        for (String l : lines) {
            if (l.startsWith("센서:")) s.sensor = value(l).equals("OK");
            if (l.startsWith("작업자:")) {
                String name = value(l);
                s.operator = name.startsWith("없음") ? "" : name;
            }
            if (l.startsWith("LCD:")) s.lcd = value(l);
            if (l.startsWith("검량선:")) {
                Matcher p = MODEL.matcher(l);
                if (p.find()) { s.m = Double.parseDouble(p.group(1)); s.c = Double.parseDouble(p.group(2)); }
                // '기본 추정치'는 펌웨어에 들어 있는 초기값이다. 검량한 결과가 아니다.
                s.saved = l.contains("저장됨") && !l.contains("기본 추정");
            }
            if (l.startsWith("증류수 기준:")) {
                Matcher p = ZERO.matcher(l);
                if (p.find()) {
                    s.i0 = new double[]{Double.parseDouble(p.group(1)), Double.parseDouble(p.group(2)), Double.parseDouble(p.group(3))};
                    s.zero = positive(s.i0);
                }
            }
            if (l.startsWith("설정:")) {
                Matcher p = GAIN.matcher(l);
                if (p.find()) s.gain = p.group(1);
                p = CALC.matcher(l);
                if (p.find()) {
                    s.primary = Arrays.asList(CHANNELS).indexOf(p.group(1));
                    s.single = p.group(2) != null;
                }
            }
        }
        return s;
    }

    private static String value(String line) { return line.substring(line.indexOf(':') + 1).trim(); }

    /** 측정 1회의 응답과 그 값을 화면에 보여 줄지에 대한 판단. */
    public static final class Reading {
        public double[] signal, absorbance;
        /** reported = 기기가 보낸 농도. accepted = 검사를 모두 통과해 화면에 보여 주는 농도(아니면 null). */
        public Double reported, accepted;
        /** 520 흡광도가 ±A520_LIMIT을 넘은 경우. 농도는 보여 주되 판정을 "확인 필요"로 바꾼다. */
        public boolean check520;
        public String code = "NO_RESPONSE", message = "측정 응답이 없습니다.";
        public final List<String> raw = new ArrayList<>();
    }

    /**
     * 측정 응답을 해석한다. before·after는 측정 직전·직후에 읽은 상태다.
     * 기기가 보낸 농도는 기기에 저장된 검량식으로 앱이 다시 계산해 일치할 때만 받아들인다.
     */
    public static Reading parse(List<String> lines, Status before, Status after) {
        Reading r = new Reading();
        r.raw.addAll(lines);
        boolean saturated = false, error = false;
        for (String l : lines) {
            if (l.startsWith("SATURATED")) saturated = true;
            if (l.startsWith("SENSOR") || l.startsWith("농도 계산")) error = true;
            if (l.startsWith("0\t")) {
                String[] f = l.split("\\t");
                if (f.length >= 4) {
                    try {
                        r.signal = new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])};
                    } catch (NumberFormatException ignored) { }
                }
            }
            Matcher p = RESULT.matcher(l);
            if (p.find()) {
                r.reported = Double.parseDouble(p.group(1));
                r.absorbance = new double[]{Double.parseDouble(p.group(2)), Double.parseDouble(p.group(3)), Double.parseDouble(p.group(4))};
                r.check520 = Math.abs(r.absorbance[1]) > A520_LIMIT;
            }
        }
        if (r.signal == null) return r;
        if (saturated) return hold(r, "SATURATED", "빛이 너무 강합니다. 관리 화면에서 게인을 낮추세요.");
        if (!positive(r.signal)) return hold(r, "INVALID_SIGNAL", "빛이 너무 약합니다. 큐벳 위치와 뚜껑을 확인하세요.");
        if (!before.sensor || !after.sensor || error) return hold(r, "DEVICE_ERROR", "측정기가 측정에 실패했습니다. 센서와 영점을 확인하고 다시 재세요.");
        if (!before.fingerprint().equals(after.fingerprint()) || Double.compare(before.m, after.m) != 0
                || Double.compare(before.c, after.c) != 0 || before.saved != after.saved)
            return hold(r, "SETTINGS_CHANGED", "측정하는 동안 설정이 바뀌었습니다. 다시 측정하세요.");
        if (!before.zero) return hold(r, "NO_ZERO", "영점이 없습니다. 증류수를 넣고 영점을 먼저 재세요.");
        if (!before.calcKnown()) return hold(r, "UNKNOWN_CALC", "펌웨어가 농도를 어떻게 계산하는지 읽지 못했습니다. 펌웨어 버전을 확인하세요.");
        if (!before.modelReady()) return hold(r, "NO_MODEL", "검량식이 없습니다. 농도를 아는 표준액으로 검량하세요.");
        if (r.reported == null || !Double.isFinite(r.reported) || r.absorbance == null || !finite(r.absorbance))
            return hold(r, "INVALID_RESULT", "농도를 읽지 못했습니다. 신호값만 저장했습니다.");
        double expected = (before.response(r.absorbance) - before.c) / before.m;
        // 펌웨어는 흡광도와 농도를 반올림해서 보낸다(옛 버전은 소수 3자리·2자리). 그 반올림만큼은 허용한다.
        double tol = .03 + (.0011 + Math.abs(r.reported) * .000005) / Math.abs(before.m);
        if (Math.abs(expected - r.reported) > tol)
            return hold(r, "MODEL_MISMATCH", "측정기가 보낸 농도와 검량식이 맞지 않습니다. 장비 상태를 새로고침하고 다시 재세요.");
        r.accepted = r.reported;
        r.code = "DEVICE_RESULT";
        r.message = "측정했습니다.";
        // 니켈은 520에서 거의 흡수하지 않는다. 양수면 탁도·기포, 음수면 영점이나 520 채널 문제다.
        if (r.check520) r.message += (r.absorbance[1] > 0
            ? "\n520 흡광도가 높습니다. 큐벳 오염이나 기포, 탁도를 확인하세요."
            : "\n520 흡광도가 음수입니다. 증류수 영점을 다시 재세요.")
            + " 다시 채워서 재 보고, 그래도 같으면 적정으로 확인하세요.";
        return r;
    }

    /** 520 흡광도가 이 값을 넘으면(양수·음수 모두) 판정을 "확인 필요"로 바꾼다. 팀 수정안(2026-11-12)의 임시 문턱값. */
    public static final double A520_LIMIT = 0.05;
    /** 검량에 쓴 표준액 농도 범위를 이만큼 넘게 벗어나면 "검량 범위 밖"으로 보류한다. */
    public static final double RANGE_MARGIN = 0.2;
    /** 같은 시료를 다시 채워 잰 값들의 차이가 이 값을 넘으면 한 번 더 채워 재게 한다. */
    public static final double FILL_DIFF_LIMIT = 0.1;

    /** 눈금 막대에서 농도가 놓일 자리(0~1). 막대는 기준 농도의 60%에서 120%까지를 그린다. 밖이면 끝에 붙인다. */
    public static double gaugePosition(double value, double target) {
        if (!Double.isFinite(value) || !Double.isFinite(target) || target <= 0) return 0;
        return Math.max(0, Math.min(1, (value - 0.6 * target) / (0.6 * target)));
    }

    /** 영점을 잰 뒤 이 시간(분)이 지나면 다시 재라고 안내한다. LED 밝기와 온도가 변하기 때문이다. PC 프로그램과 같은 값. */
    public static final int ZERO_MAX_MIN = 5;

    /** 영점을 잰 뒤 지난 분. 이번 연결에서 재지 않았으면(zeroAt < 0) -1. 두 시각은 같은 시계의 밀리초다. */
    public static long zeroMinutes(long zeroAt, long now) {
        return zeroAt < 0 ? -1 : Math.max(0, now - zeroAt) / 60000;
    }

    /** 측정 전에 영점을 다시 잴지 물을 때 보여 줄 문구. 영점이 5분 안에 잰 것이면 null. */
    public static String zeroWarning(long zeroAt, long now) {
        long min = zeroMinutes(zeroAt, now);
        if (min < 0) return "측정기에 남아 있는 영점은 언제 잰 것인지 알 수 없습니다. 증류수 영점을 다시 재는 것이 좋습니다.";
        if (min < ZERO_MAX_MIN) return null;
        return "영점을 잰 지 " + min + "분 지났습니다. LED 밝기가 변했을 수 있으니 증류수 영점을 다시 재는 것이 좋습니다.";
    }

    /** 같은 시료를 여러 번 채워 잰 농도의 {평균, 최대 − 최소}. 값이 없으면 null. */
    public static double[] fillSummary(List<Double> fills) {
        if (fills == null || fills.isEmpty()) return null;
        double sum = 0;
        for (double v : fills) sum += v;
        return new double[]{sum / fills.size(), Collections.max(fills) - Collections.min(fills)};
    }

    static Reading hold(Reading r, String code, String msg) {
        r.code = code;
        r.message = msg;
        r.accepted = null;
        return r;
    }

    /**
     * 검량 농도 범위 확인. range는 검량에 쓴 표준액 농도의 {최소, 최대}이고, 모르면 null이다.
     * 범위를 알면 벗어난 농도를 보류한다.
     * 범위를 모르면(PC 등 다른 곳에서 저장한 검량식) 농도는 그대로 두고 안내문만 붙인다.
     */
    public static Reading applyRange(Reading r, double[] range) {
        if (r.accepted == null) return r;
        if (range == null) {
            r.message += "\nPC에서 저장한 검량식이라 검량 범위를 모릅니다.";
            return r;
        }
        if (r.accepted < range[0] - RANGE_MARGIN || r.accepted > range[1] + RANGE_MARGIN)
            return hold(r, "OUTSIDE_CALIBRATION_RANGE", String.format(Locale.US,
                "검량 범위 밖입니다. 표준액 범위(%.2f~%.2f g/L)를 %.1f g/L 넘게 벗어났습니다. 적정으로 확인하세요.",
                range[0], range[1], RANGE_MARGIN));
        return r;
    }

    public static boolean finite(double[] v) {
        for (double x : v) if (!Double.isFinite(x)) return false;
        return true;
    }

    public static boolean positive(double[] v) {
        if (!finite(v)) return false;
        for (double x : v) if (x <= 0) return false;
        return true;
    }

    /** 검량용 한 점: 기준 농도와 그때의 A(Status.response). */
    public static final class Point {
        public double concentration, a;
        public Point(double x, double y) { concentration = x; a = y; }
    }

    public static final class Fit {
        public double m, c, r2, min, max;
        public int n, levels;
    }

    /** A = m × C + c 최소제곱 적합. 농도 3종 이상, 종류마다 3회 이상이 아니면 거부한다. */
    public static Fit fit(List<Point> p) {
        final String few = "농도가 다른 표준액 3종 이상을 3번씩 재세요.";
        if (p.size() < 9) throw new IllegalArgumentException(few);
        Map<Double, Integer> counts = new TreeMap<>();
        double sx = 0, sy = 0;
        for (Point q : p) {
            if (!Double.isFinite(q.concentration) || !Double.isFinite(q.a) || q.concentration <= 0)
                throw new IllegalArgumentException("기준 농도와 흡광도를 확인하세요.");
            counts.put(q.concentration, counts.getOrDefault(q.concentration, 0) + 1);
            sx += q.concentration;
            sy += q.a;
        }
        if (counts.size() < 3 || Collections.min(counts.values()) < 3) throw new IllegalArgumentException(few);
        double mx = sx / p.size(), my = sy / p.size(), xx = 0, xy = 0, yy = 0;
        for (Point q : p) {
            xx += (q.concentration - mx) * (q.concentration - mx);
            xy += (q.concentration - mx) * (q.a - my);
            yy += (q.a - my) * (q.a - my);
        }
        if (xx <= 0 || yy <= 1e-15) throw new IllegalArgumentException("농도에 따른 흡광도 변화가 충분하지 않습니다.");
        Fit f = new Fit();
        f.m = xy / xx;
        f.c = my - f.m * mx;
        f.r2 = xy * xy / (xx * yy);
        f.min = Collections.min(counts.keySet());
        f.max = Collections.max(counts.keySet());
        f.n = p.size();
        f.levels = counts.size();
        if (Math.abs(f.m) < 1e-9 || !Double.isFinite(f.m) || !Double.isFinite(f.r2))
            throw new IllegalArgumentException("검량식 기울기가 유효하지 않습니다.");
        return f;
    }

    /**
     * 기기에 저장된 검량식이 (계산 방식, m, c)와 같은 식인가.
     * 휴대폰이 직접 저장한 검량식인지 확인할 때 쓴다. 같으면 그때 쓴 표준액 농도 범위를 안다.
     * 영점과 게인은 비교하지 않는다. 영점을 다시 재도 검량식은 그대로 쓰기 때문이다.
     */
    public static boolean sameModel(Status s, String calcId, double m, double c) {
        return s.modelReady() && s.calcKnown() && s.calcId().equals(calcId)
            && Math.abs(s.m - m) < 1e-5 && Math.abs(s.c - c) < 1e-5;
    }

    /** 기준 농도 대비 백분율. 기준 농도가 0 이하이면 NaN. */
    public static double percent(double concentration, double target) {
        if (!Double.isFinite(concentration) || !Double.isFinite(target) || target <= 0) return Double.NaN;
        return 100 * concentration / target;
    }

    public static final String NORMAL = "정상", LOW = "경고 · 낮음", HIGH = "경고 · 높음", CHECK = "확인 필요";

    /** 520 흡광도가 이상하면 범위와 관계없이 "확인 필요". 아니면 범위 판정. 농도가 없으면 빈 문자열. */
    public static String verdict(Double concentration, Double low, Double high, double[] absorbance) {
        if (concentration == null || !Double.isFinite(concentration)) return "";
        if (absorbance != null && absorbance.length > 1 && Math.abs(absorbance[1]) > A520_LIMIT) return CHECK;
        return verdict(concentration, low, high);
    }

    /**
     * 농도를 관리자가 설정한 범위와 비교한다. 하한·상한이 모두 없거나 농도가 없으면 빈 문자열(판정 안 함).
     * 농도 범위만 비교한다. 제품 합격이나 약품 보충량을 판단하는 것이 아니다.
     */
    public static String verdict(Double concentration, Double low, Double high) {
        if (concentration == null || !Double.isFinite(concentration) || (low == null && high == null)) return "";
        if (low != null && concentration < low) return LOW;
        if (high != null && concentration > high) return HIGH;
        return NORMAL;
    }

    /** 기준값 설정을 검사한다. 문제가 있으면 사용자에게 보여 줄 문구, 없으면 null. */
    public static String checkLimits(Double target, Double low, Double high) {
        if (target == null || target <= 0) return "기준 농도는 0보다 큰 숫자로 입력하세요.";
        if ((low != null && low < 0) || (high != null && high <= 0)) return "범위 하한·상한은 0보다 큰 숫자로 입력하세요.";
        if (low != null && high != null && low >= high) return "범위 하한은 상한보다 작아야 합니다.";
        return null;
    }

    /** 빈칸이면 null, 숫자면 그 값. 숫자가 아니면 NumberFormatException. */
    public static Double optionalNumber(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return null;
        double v = Double.parseDouble(t);
        if (!Double.isFinite(v)) throw new NumberFormatException(t);
        return v;
    }

    private static final java.time.format.DateTimeFormatter DATE_TIME =
        java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final java.time.format.DateTimeFormatter TIME_ONLY =
        java.time.format.DateTimeFormatter.ofPattern("H:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT);

    /**
     * 채취 시각을 "yyyy-MM-dd HH:mm"으로 정리한다. "14:05"처럼 시각만 쓰면 오늘 날짜를 붙인다.
     * 빈칸이면 빈 문자열. 형식이 틀리면 IllegalArgumentException.
     */
    public static String sampledAt(String text, java.time.LocalDateTime now) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";
        java.time.LocalDateTime when;
        try {
            when = t.contains("-")
                ? java.time.LocalDateTime.parse(t, DATE_TIME)
                : java.time.LocalDateTime.of(now.toLocalDate(), java.time.LocalTime.parse(t, TIME_ONLY));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(t, e);
        }
        return when.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"));
    }

    /** 숫자를 입력 칸에 다시 보여 줄 때 쓰는 표기. 5.2 → "5.2", 5.0 → "5". */
    public static String plain(double v) {
        return new java.math.BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
    }

    public static String csv(String x) { return "\"" + x.replace("\"", "\"\"") + "\""; }
}
