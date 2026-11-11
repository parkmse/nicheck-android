package kr.ac.cau.nichecker;

import android.hardware.usb.*;
import android.os.SystemClock;
import com.hoho.android.usbserial.driver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * USB 시리얼로 측정기와 명령·응답을 주고받는다. 한 번에 한 명령만 보내고 정해진 줄이 올 때까지 기다린다.
 * 한글 응답이 USB 패킷 경계에서 잘려도 깨지지 않도록 줄 단위로 모아서 UTF-8로 읽는다.
 * 명령과 응답 형식은 docs/FirmwareProtocol_KO.md 참고.
 */
public final class UsbInstrument implements Closeable {
    /** 명령 하나의 최대 대기 시간. 측정·영점은 약 3초 걸린다. */
    private static final long COMMAND_TIMEOUT_MS = 25000;
    private static final String WRONG_FIRMWARE =
        "다른 펌웨어가 감지되었습니다. 팀 최종 nickel_rig 펌웨어(한국어 텍스트 통신)가 필요합니다. JSON 통신을 쓰는 v04는 연결되지 않습니다.";

    /** 다른 펌웨어 안내문에 기기가 실제로 보낸 줄(앞 80자)을 붙인다. 어떤 펌웨어인지 바로 알 수 있다. */
    private static String wrongFirmware(String line) {
        return WRONG_FIRMWARE + "\n\n기기가 보낸 내용: " + (line.length() > 80 ? line.substring(0, 80) + "…" : line);
    }

    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    public UsbDevice device;

    /** 재시도해도 소용없는 경우(다른 펌웨어, 센서 배선). */
    private static final class Fatal extends IOException {
        Fatal(String message) { super(message); }
    }

    public UsbInstrument(UsbManager manager, UsbSerialDriver driver) throws IOException {
        device = driver.getDevice();
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("USB 연결 권한을 확인하세요.");
        try {
            port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(false);
            port.setRTS(false);
            SystemClock.sleep(300);
        } catch (Exception e) {
            close();
            throw new IOException("USB 장치를 열지 못했습니다: " + e.getMessage(), e);
        }
    }

    /** 다음 한 줄(빈 줄 제외)을 돌려준다. deadline까지 오지 않으면 예외. */
    private String line(long deadline) throws IOException {
        byte[] b = new byte[4096];
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!pending.isEmpty()) return pending.removeFirst();
            int n = port.read(b, 250);
            for (int i = 0; i < n; i++) {
                if (b[i] == '\n') {
                    String s = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
                    bytes.reset();
                    if (!s.isEmpty()) pending.add(s);
                } else {
                    bytes.write(b[i]);
                    if (bytes.size() > 16384) throw new IOException("장치 응답이 너무 깁니다.");
                }
            }
        }
        if (!pending.isEmpty()) return pending.removeFirst();
        throw new IOException("장치 응답 시간이 초과되었습니다. USB·센서 연결을 확인하세요.");
    }

    /** 기기 버튼으로 잰 결과처럼 이전에 온 줄은 버린다. */
    private void drain() throws IOException {
        pending.clear();
        bytes.reset();
        byte[] b = new byte[4096];
        long until = SystemClock.elapsedRealtime() + 450;
        while (SystemClock.elapsedRealtime() < until) {
            if (port.read(b, 60) == 0) break;
        }
    }

    /** 명령을 보내고, ends 중 하나로 시작하는 줄이 올 때까지의 응답 줄을 돌려준다. */
    public List<String> ask(String command, String... ends) throws IOException {
        return ask(COMMAND_TIMEOUT_MS, command, ends);
    }

    private List<String> ask(long timeoutMs, String command, String... ends) throws IOException {
        drain();
        port.write((command + "\n").getBytes(StandardCharsets.UTF_8), 1500);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        List<String> out = new ArrayList<>();
        while (out.size() < 512) {
            String s = line(deadline);
            if (s.startsWith("{")) throw new Fatal(wrongFirmware(s));
            if (s.startsWith(">")) continue;
            out.add(s);
            for (String end : ends) if (s.startsWith(end)) return out;
        }
        throw new IOException("장치 응답 형식을 확인하세요.");
    }

    public MeasurementCore.Status status() throws IOException {
        return MeasurementCore.status(ask("s", "설정"));
    }

    /** RTS를 잠깐 올려 ESP32를 재부팅하고(EN 버튼과 같음), 부팅 마지막 안내 줄이 올 때까지 기다린다. */
    private void reboot() throws IOException {
        port.setRTS(true);
        SystemClock.sleep(100);
        port.setRTS(false);
        pending.clear();
        bytes.reset();
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (true) {
            String s;
            try {
                s = line(deadline);
            } catch (IOException timeout) {
                throw new IOException("측정기 응답이 없습니다. USB 연결을 확인하세요. 처음 쓰는 기기면 PC 프로그램으로 펌웨어를 먼저 설치하세요.");
            }
            if (s.startsWith("{")) throw new Fatal(wrongFirmware(s));
            if (s.startsWith("SENSOR ERROR")) throw new Fatal("센서 응답이 없습니다. 센서 선(SDA 21 / SCL 22)을 확인하세요.");
            if (s.startsWith("Enter =")) return;
        }
    }

    /** 연결 직후 상태를 읽는다. 답이 없으면 기기를 재부팅한 뒤 한 번 더 읽는다. */
    public MeasurementCore.Status handshake() throws IOException {
        MeasurementCore.Status s;
        try {
            s = MeasurementCore.status(ask(4000, "s", "설정"));
        } catch (Fatal e) {
            throw e;
        } catch (IOException first) {
            reboot();
            s = status();
        }
        if (s.gain.equals("?") || !s.sensor) throw new IOException("센서 상태 또는 펌웨어 응답을 확인하세요.");
        return s;
    }

    @Override public void close() {
        try { if (port != null) port.close(); } catch (Exception ignored) { }
        try { if (connection != null) connection.close(); } catch (Exception ignored) { }
        port = null;
        connection = null;
    }
}
