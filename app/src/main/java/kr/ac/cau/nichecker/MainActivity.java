package kr.ac.cau.nichecker;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.hoho.android.usbserial.driver.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Korean operator UI. No demo measurements, network access, or automatic firmware flashing. */
public class MainActivity extends Activity {
    final int BLUE=Color.rgb(0,103,163),INK=Color.rgb(23,43,77),MUTED=Color.rgb(93,109,126),BG=Color.rgb(243,246,250),LINE=Color.rgb(219,227,236);
    final String USB_PERMISSION="kr.ac.cau.nichecker.USB_PERMISSION";
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final ArrayList<JSONObject> records=new ArrayList<>();
    final ArrayList<MeasurementCore.Point> standards=new ArrayList<>();
    LinearLayout root,body,tabs;TextView connectionText,nextText,resultText,resultNote,modelNote,recordNote;
    EditText operator,sample,reference;CheckBox prepared,isStandard;Button connectButton,measureButton;ScrollView scroll;
    UsbManager usb;UsbInstrument instrument;MeasurementCore.Status status=new MeasurementCore.Status();
    boolean busy=false,destroyed=false;int tab=0,generation=0;String taskNote="",lastMessage="장비를 연결하면 다음 할 일을 안내합니다.",sampleText="샘플1",operatorText="",refText="";
    boolean preparedValue=false,standardValue=false;Double latest=null;String latestCode="",standardEpoch="";
    android.content.SharedPreferences prefs;
    /** 시료 정보 칸: {기록에 쓰는 이름, 화면 이름}. 모두 선택 입력이다. */
    static final String[][] INFO={{"sample_id","시료 ID"},{"plant","공장"},{"line","라인"},{"tank","도금조"},{"sample_temp_c","시료 온도 ℃"},{"mto","MTO"},{"sampled_at","채취 시각 · 예: 14:05 또는 2026-11-12 14:05"}};
    final LinkedHashMap<String,String> sampleInfo=new LinkedHashMap<>();final HashMap<String,EditText> infoInputs=new HashMap<>();boolean infoOpen=false;
    /** 기준 농도(=100%)와 범위 판정 하한·상한. 하한·상한은 기업이 확정하기 전에는 비워 둔다. */
    double target=5.2;Double limitLow=null,limitHigh=null;String latestDetail="";
    /** CSV 열: {열 제목, 기록에서 꺼낼 이름}. "signal#0"은 signal 배열의 첫 값. */
    static final String[][] CSV={{"Time","time"},{"Operator","operator"},{"Sample","name"},{"SampleID","sample_id"},{"Plant","plant"},{"Line","line"},{"Tank","tank"},{"SampledAt","sampled_at"},{"SampleTemp_C","sample_temp_c"},{"MTO","mto"},{"Standard","standard"},{"Reference_g_L","reference_g_l"},{"Signal405","signal#0"},{"Signal520","signal#1"},{"Signal660","signal#2"},{"A405","absorbance#0"},{"A520","absorbance#1"},{"A660","absorbance#2"},{"Concentration_g_L","concentration"},{"Target_g_L","target_g_l"},{"Percent_of_Target","percent_of_target"},{"Verdict","verdict"},{"LimitLow_g_L","limit_low_g_l"},{"LimitHigh_g_L","limit_high_g_l"},{"Status","status"},{"DeviceReported_g_L","device_reported"},{"Settings_JSON","settings"},{"Raw_JSON","raw"}};
    final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(USB_PERMISSION.equals(i.getAction())){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&d!=null)openDevice(d);else info("USB 연결","연결 권한이 허용되지 않았습니다. 다시 연결해 권한을 허용하세요.");
        }else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(instrument!=null&&d!=null&&instrument.device.getDeviceId()==d.getDeviceId())disconnect("USB 연결이 해제되었습니다. 기록은 보관됩니다.");
        }
    }};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        prefs=getSharedPreferences("nicheck",MODE_PRIVATE);operatorText=prefs.getString("operator","");
        for(String[] f:INFO)sampleInfo.put(f[0],"");for(String k:new String[]{"plant","line","tank"})sampleInfo.put(k,prefs.getString("info_"+k,""));
        try{target=Double.parseDouble(prefs.getString("target","5.2"));limitLow=MeasurementCore.optionalNumber(prefs.getString("limit_low",""));limitHigh=MeasurementCore.optionalNumber(prefs.getString("limit_high",""));}catch(NumberFormatException e){target=5.2;limitLow=null;limitHigh=null;}
        usb=(UsbManager)getSystemService(USB_SERVICE);loadRecords();
        IntentFilter f=new IntentFilter(USB_PERMISSION);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);
        getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        render();
    }
    /** 설치된 앱의 버전 이름. app/build.gradle.kts의 versionName과 항상 같다. */
    String versionName(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "";}}
    int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    GradientDrawable shape(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    void add(LinearLayout p,View v){p.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    void gap(LinearLayout p,int h){View v=new View(this);p.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    LinearLayout card(LinearLayout parent,String title){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(shape(Color.WHITE,LINE));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(c,lp);if(!title.isEmpty()){add(c,text(title,16,INK,true));gap(c,10);}return c;}
    Button button(LinearLayout p,String label,boolean primary,Runnable fn){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(16);b.setMinHeight(dp(52));b.setTextColor(primary?Color.WHITE:BLUE);b.setBackground(shape(primary?BLUE:Color.WHITE,primary?0:LINE));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);p.addView(b,lp);b.setOnClickListener(v->fn.run());b.setEnabled(!busy);return b;}
    EditText entry(LinearLayout p,String label,String value,boolean decimal){add(p,text(label,13,MUTED,false));EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(INK);e.setText(value);e.setMinHeight(dp(52));e.setPadding(dp(12),dp(6),dp(12),dp(6));e.setBackground(shape(BG,LINE));if(decimal)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);add(p,e);gap(p,10);return e;}
    void rememberInputs(){if(operator!=null){operatorText=operator.getText().toString().trim();sampleText=sample.getText().toString().trim();refText=reference.getText().toString().trim();preparedValue=prepared.isChecked();standardValue=isStandard.isChecked();prefs.edit().putString("operator",operatorText).apply();}operator=null;sample=null;reference=null;prepared=null;isStandard=null;
        if(!infoInputs.isEmpty()){for(Map.Entry<String,EditText> e:infoInputs.entrySet())sampleInfo.put(e.getKey(),e.getValue().getText().toString().trim());infoInputs.clear();prefs.edit().putString("info_plant",sampleInfo.get("plant")).putString("info_line",sampleInfo.get("line")).putString("info_tank",sampleInfo.get("tank")).apply();}}
    /** 접힌 시료 정보 카드에 보여 줄 한 줄 요약. */
    String infoSummary(){ArrayList<String> p=new ArrayList<>();String v=sampleInfo.get("sample_id");if(!v.isEmpty())p.add("시료 "+v);ArrayList<String> where=new ArrayList<>();for(String k:new String[]{"plant","line","tank"})if(!sampleInfo.get(k).isEmpty())where.add(sampleInfo.get(k));if(!where.isEmpty())p.add(String.join(" / ",where));v=sampleInfo.get("sample_temp_c");if(!v.isEmpty())p.add(v+" ℃");v=sampleInfo.get("mto");if(!v.isEmpty())p.add("MTO "+v);v=sampleInfo.get("sampled_at");if(!v.isEmpty())p.add("채취 "+v);return p.isEmpty()?"입력한 정보가 없습니다. 시료 ID, 공장·라인·도금조, 온도, MTO, 채취 시각을 기록에 함께 남길 수 있습니다.":String.join(" · ",p);}
    /** 기록에서 글자 값을 꺼낸다. 없거나 null이면 빈 문자열. "signal#0"은 signal 배열의 첫 값. 숫자는 3.0E-4가 아니라 0.0003처럼 쓴다. */
    static String cell(JSONObject r,String key){int k=key.indexOf('#');Object v;if(k<0)v=r.opt(key);else{JSONArray a=r.optJSONArray(key.substring(0,k));v=a==null?null:a.opt(Integer.parseInt(key.substring(k+1)));}
        if(v==null||v==JSONObject.NULL)return "";if(v instanceof Number){double d=((Number)v).doubleValue();return Double.isFinite(d)?MeasurementCore.plain(d):"";}return v.toString();}
    void render(){rememberInputs();root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom,left,right;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets s=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());top=s.top;bottom=s.bottom;left=s.left;right=s.right;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();}v.setPadding(left,top,right,bottom);return insets;});root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setOrientation(1);header.setPadding(dp(22),dp(18),dp(22),dp(12));header.setBackgroundColor(Color.WHITE);add(header,text("니켈체크",27,INK,true));add(header,text("니켈 도금액 농도 측정 · 기록",13,MUTED,false));add(root,header);
        scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(body);
        if(tab==0)measurementPage();else if(tab==1)recordsPage();else adminPage();
        tabs=new LinearLayout(this);tabs.setPadding(dp(10),dp(6),dp(10),dp(8));tabs.setBackgroundColor(Color.WHITE);String[] labels={"측정","기록","관리"};
        for(int n=0;n<3;n++){final int index=n;Button b=new Button(this);b.setAllCaps(false);b.setText(labels[n]);b.setTextSize(15);b.setMinHeight(dp(50));b.setTextColor(n==tab?BLUE:MUTED);b.setBackground(shape(n==tab?Color.rgb(230,242,250):Color.WHITE,0));tabs.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{if(!busy){tab=index;render();}});}add(root,tabs);
    }
    void measurementPage(){
        LinearLayout c=card(body,"");connectionText=text(instrument==null?"○ 장비 연결 안 됨":"● USB 장비 연결됨",15,BLUE,true);add(c,connectionText);add(c,text("연결 → 증류수 영점 → 시료 측정 · 검량식이 없으면 먼저 검량",12,MUTED,false));connectButton=button(c,instrument==null?"USB 장비 연결":"연결 끊기",false,()->{rememberInputs();if(instrument==null)connect();else disconnect("연결을 종료했습니다.");});
        c=card(body,"");add(c,text("최근 측정",13,MUTED,false));resultText=text(latest==null?"-- g/L":String.format(Locale.US,"%.2f g/L",latest),42,INK,true);add(c,resultText);if(latest!=null&&!latestDetail.isEmpty())add(c,text(latestDetail,16,INK,true));resultNote=text(latestCode.isEmpty()?"농도 결과를 기다리고 있습니다.":latestCode,14,BLUE,true);add(c,resultNote);gap(c,8);nextText=text(busy?taskNote:lastMessage,14,MUTED,false);add(c,nextText);
        c=card(body,"시료 정보 (선택)");add(c,text(infoSummary(),13,MUTED,false));
        if(infoOpen){gap(c,8);for(String[] f:INFO)infoInputs.put(f[0],entry(c,f[1],sampleInfo.get(f[0]),f[0].equals("sample_temp_c")||f[0].equals("mto")));
            button(c,"채취 시각을 지금으로",false,()->{rememberInputs();sampleInfo.put("sampled_at",new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date()));render();});}
        button(c,infoOpen?"시료 정보 접기":"시료 정보 입력",false,()->{rememberInputs();infoOpen=!infoOpen;render();});
        c=card(body,"시료 준비");operator=entry(c,"작업자",operatorText,false);sample=entry(c,"측정 이름",sampleText,false);reference=entry(c,"기준 농도 g/L (표준액 농도 또는 적정 결과) · 모르면 비움",refText,true);
        isStandard=new CheckBox(this);isStandard.setText("기준 농도가 확인된 검량용 표준액");isStandard.setTextColor(INK);isStandard.setChecked(standardValue);add(c,isStandard);
        prepared=new CheckBox(this);prepared.setText("용기를 고정하고 외부 빛을 가렸습니다.");prepared.setTextColor(INK);prepared.setChecked(preparedValue);add(c,prepared);
        measureButton=button(c,"측정하고 자동 저장",true,this::measure);measureButton.setEnabled(!busy&&instrument!=null);
        button(c,"증류수 영점 측정",false,this::zero).setEnabled(!busy&&instrument!=null);
        c=card(body,"현재 사용 범위");add(c,text("이 앱은 측정과 기록을 돕는 시제품입니다. 기기 저장식의 존재와 정확도 검증은 다릅니다. 범위 판정은 관리 화면에서 설정한 농도 범위와 비교한 결과입니다. 제품 합격 또는 약품 보충량을 판단하지 않습니다.",13,MUTED,false));
        modelNote=text(instrument==null?"장비를 연결하면 영점과 검량식 상태를 보여 줍니다.":(status.zero?"영점 있음":"영점 없음 · 증류수 영점 필요")+"\n"+calibrationNote(status),13,BLUE,true);add(c,modelNote);
    }
    void recordsPage(){
        LinearLayout c=card(body,"측정 기록");add(c,text("실제 USB 측정 기록 "+records.size()+"건 · 앱 내부에 자동 보관",14,MUTED,false));button(c,"CSV 파일로 내보내기",true,this::export);
        if(records.isEmpty()){c=card(body,"");add(c,text("아직 측정 기록이 없습니다. 장비를 연결하고 첫 측정을 시작하세요.",16,MUTED,false));return;}
        for(int j=records.size()-1;j>=Math.max(0,records.size()-40);j--){JSONObject r=records.get(j);c=card(body,r.optString("name","시료"));String value=r.isNull("concentration")?"농도 보류":String.format(Locale.US,"%.2f g/L",r.optDouble("concentration"));if(!r.isNull("concentration")&&!r.isNull("percent_of_target"))value+=String.format(Locale.US," · %.1f%%",r.optDouble("percent_of_target"));add(c,text(value,24,INK,true));
            if(!cell(r,"verdict").isEmpty())add(c,text("범위 판정: "+cell(r,"verdict"),13,BLUE,true));
            add(c,text(r.optString("time")+" · "+r.optString("operator"),12,MUTED,false));
            ArrayList<String> where=new ArrayList<>();if(!cell(r,"sample_id").isEmpty())where.add("시료 "+cell(r,"sample_id"));for(String k:new String[]{"plant","line","tank"})if(!cell(r,k).isEmpty())where.add(cell(r,k));if(!cell(r,"sample_temp_c").isEmpty())where.add(cell(r,"sample_temp_c")+" ℃");if(!cell(r,"mto").isEmpty())where.add("MTO "+cell(r,"mto"));if(!cell(r,"sampled_at").isEmpty())where.add("채취 "+cell(r,"sampled_at"));if(!cell(r,"reference_g_l").isEmpty())where.add((r.optBoolean("standard")?"표준액 ":"적정 ")+cell(r,"reference_g_l")+" g/L");
            if(!where.isEmpty())add(c,text(String.join(" · ",where),12,MUTED,false));add(c,text(r.optString("message"),13,MUTED,false));JSONArray a=r.optJSONArray("signal");if(a!=null)add(c,text("신호 405 / 520 / 660: "+a.toString(),12,MUTED,false));}
    }
    void adminPage(){
        LinearLayout c=card(body,"장비 상태");add(c,text("기준 펌웨어: 팀 최종 nickel_rig (2026-11-11 빌드) · 한국어 텍스트 통신\nUSB 115200 baud · 405/520/660은 펌웨어의 채널 이름이며 실제 LED 파장을 측정한 값이 아닙니다.",13,MUTED,false));
        add(c,text("센서: "+(status.sensor?"응답 확인":"미확인")+"\nLCD: "+status.lcd+"\n게인: "+status.gain+"X\n농도 계산: "+status.calcLabel()+"\n영점: "+(status.zero?Arrays.toString(status.i0):"없음")+"\n검량식: "+(status.modelReady()?String.format(Locale.US,"m=%.6f, c=%.6f",status.m,status.c)+"\n"+calibrationNote(status):"없음 또는 기본 추정치"),14,INK,false));
        button(c,"장비 상태 새로고침",false,()->run("장비 상태 확인 중",()->instrument.status(),x->{status=(MeasurementCore.Status)x;render();})).setEnabled(!busy&&instrument!=null);
        c=card(body,"기준값 설정");add(c,text("기준 농도는 '기준 대비 %'의 100%입니다. 범위 하한·상한에는 기업이 확정한 값을 넣으세요. 비워 두면 범위 판정을 하지 않습니다. 저장한 뒤의 측정부터 적용됩니다.",13,MUTED,false));gap(c,8);
        final EditText targetIn=entry(c,"기준 농도 g/L (100%)",MeasurementCore.plain(target),true),lowIn=entry(c,"범위 하한 g/L · 미정이면 비움",limitLow==null?"":MeasurementCore.plain(limitLow),true),highIn=entry(c,"범위 상한 g/L · 미정이면 비움",limitHigh==null?"":MeasurementCore.plain(limitHigh),true);
        button(c,"기준값 저장",false,()->saveLimits(targetIn.getText().toString(),lowIn.getText().toString(),highIn.getText().toString()));
        c=card(body,"검량선 관리");add(c,text("새 영점을 측정한 뒤, 확인된 농도 3종 이상을 각각 3회 이상 측정하세요. 측정 화면에서 표준액을 체크하고 기준 농도를 직접 입력합니다. 이름의 백분율로 농도를 추정하지 않습니다.\nPC 프로그램에서 저장한 검량식이 기기에 있으면 그대로 사용합니다. 여기서 새로 저장하면 기기에 있던 검량식을 덮어씁니다.",14,MUTED,false));
        add(c,text("현재 연결·영점 구간의 표준 측정 "+standards.size()+"건",15,BLUE,true));
        button(c,"검량선 검토 후 기기에 저장",true,this::calibrate).setEnabled(!busy&&instrument!=null);
        button(c,"새 검량 데이터 수집 시작",false,()->{standards.clear();standardEpoch=status.fingerprint();lastMessage="검량용 측정 구간을 새로 시작했습니다. 이전 원본 기록과 기기의 검량식은 유지됩니다.";render();});
        c=card(body,"게인 설정");add(c,text("게인을 바꾸면 기기의 영점이 지워집니다. 변경 뒤 증류수 영점을 다시 측정하세요. 검량식은 기기에 그대로 남습니다. 검량할 때와 게인이 달라졌다면 표준액으로 값을 확인하세요.",13,MUTED,false));
        Spinner gain=new Spinner(this);String[] choices={"0.5","1","2","4","8","16","32","64","128","256","512"};gain.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));for(int j=0;j<choices.length;j++)if(choices[j].equals(status.gain))gain.setSelection(j);add(c,gain);
        button(c,"게인 변경",false,()->confirm("게인 변경","게인을 바꾸면 영점이 지워집니다. 변경 뒤 증류수 영점을 다시 측정하세요.",()->run("게인 변경 중",()->{instrument.ask("g "+gain.getSelectedItem(),"게인","usage");return instrument.status();},x->{status=(MeasurementCore.Status)x;resetStandards();lastMessage="게인을 바꿨습니다. 증류수 영점을 다시 측정하세요.";render();}))).setEnabled(!busy&&instrument!=null);
        c=card(body,"니켈체크 "+versionName());add(c,text("중앙대학교 · 생산품질 1팀 푸앙이칼취\n현장용 측정 흐름을 검증하기 위한 Android 시제품\n데이터는 이 휴대폰에 저장되며 서버로 전송되지 않습니다. 앱 삭제 전 CSV로 내보내세요.\nR²는 적합도 지표이며 측정 정확도의 보증이 아닙니다.",13,MUTED,false));
    }
    void connect(){
        List<UsbSerialDriver> drivers=UsbSerialProber.getDefaultProber().findAllDrivers(usb);
        if(drivers.isEmpty()){info("장비 연결","USB 호스트를 지원하는 휴대폰에 데이터 케이블 또는 OTG 어댑터로 장비를 연결하세요. 지원 장치가 감지되지 않았습니다.");return;}
        if(drivers.size()==1){requestDevice(drivers.get(0).getDevice());return;}
        String[] names=new String[drivers.size()];for(int n=0;n<names.length;n++)names[n]=drivers.get(n).getClass().getSimpleName()+" · USB "+drivers.get(n).getDevice().getDeviceId();
        new AlertDialog.Builder(this).setTitle("측정 장비 선택").setItems(names,(d,n)->requestDevice(drivers.get(n).getDevice())).show();
    }
    void requestDevice(UsbDevice d){if(usb.hasPermission(d)){openDevice(d);return;}Intent i=new Intent(USB_PERMISSION).setPackage(getPackageName());int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;usb.requestPermission(d,PendingIntent.getBroadcast(this,0,i,flags));}
    void openDevice(UsbDevice d){run("장비 연결 확인 중",()->{UsbSerialDriver driver=UsbSerialProber.getDefaultProber().probeDevice(d);if(driver==null)throw new IOException("지원하지 않는 USB 장치입니다.");UsbInstrument device=new UsbInstrument(usb,driver);try{return new Object[]{device,device.handshake()};}catch(Exception e){device.close();throw e;}},x->{Object[] pair=(Object[])x;instrument=(UsbInstrument)pair[0];status=(MeasurementCore.Status)pair[1];resetStandards();lastMessage=!status.zero?"증류수 용기를 넣고 영점을 측정하세요.":status.modelReady()?"기기에 남아 있는 영점은 언제 잰 것인지 알 수 없습니다. 증류수 영점을 다시 측정한 뒤 시료를 측정하세요.":"검량식이 없습니다. 표준액 측정부터 진행하세요.";render();});}
    void disconnect(String msg){rememberInputs();generation++;UsbInstrument d=instrument;instrument=null;if(d!=null)d.close();busy=false;status=new MeasurementCore.Status();latest=null;latestCode="";standards.clear();lastMessage=msg;render();}
    interface Work{Object get()throws Exception;}interface Done{void done(Object x);}
    void run(String note,Work fn,Done done){if(busy)return;rememberInputs();busy=true;taskNote=note;latest=null;latestCode="처리 중";final int g=generation;render();worker.submit(()->{try{Object value=fn.get();runOnUiThread(()->{if(destroyed||g!=generation){if(value instanceof Object[]){Object d=((Object[])value)[0];if(d instanceof UsbInstrument)((UsbInstrument)d).close();}return;}busy=false;latestCode="";done.done(value);});}catch(Exception e){runOnUiThread(()->{if(destroyed||g!=generation)return;busy=false;latest=null;latestCode="결과 보류";lastMessage=e.getMessage()==null?e.toString():e.getMessage();render();info("확인 필요",lastMessage);});}});}
    boolean ready(){if(instrument==null){info("연결 필요","USB 장비를 먼저 연결하세요.");return false;}rememberInputs();if(!preparedValue){info("측정 준비","용기 고정과 차광을 확인한 뒤 체크하세요.");render();return false;}return true;}
    void zero(){if(!ready())return;confirm("증류수 영점","실제 증류수 용기를 넣었나요? 기기의 영점을 새로 저장합니다. 검량식은 그대로 유지됩니다.",()->run("증류수 영점 측정 중",()->{List<String> lines=instrument.ask("z","ZERO","SENSOR");boolean ok=false;for(String l:lines)if(l.startsWith("ZERO OK"))ok=true;if(!ok)throw new IOException("영점을 저장하지 못했습니다: "+lines);return instrument.status();},x->{status=(MeasurementCore.Status)x;resetStandards();lastMessage=status.modelReady()?"영점을 기록했습니다. 용기를 고정하고 시료를 측정하세요.":"영점을 기록했습니다. 검량식이 없습니다. 표준액 3종 이상을 각각 3회 이상 측정해 검량하세요.";render();}));}
    /** 영점·게인이 바뀌면 그때까지 모은 검량용 표준 측정을 비운다. 기기에 저장된 검량식은 건드리지 않는다. */
    void resetStandards(){standards.clear();standardEpoch=status.fingerprint();latest=null;}
    void measure(){
        if(!ready())return;if(operatorText.isEmpty()||sampleText.isEmpty()){info("입력 확인","작업자와 측정 이름을 입력하세요.");render();return;}
        final Double cref;try{cref=refText.isEmpty()?null:Double.valueOf(refText);if(cref!=null&&(!Double.isFinite(cref)||cref<0))throw new NumberFormatException();if(standardValue&&(cref==null||cref<=0))throw new NumberFormatException();}catch(Exception e){info("기준 농도","표준액은 확인된 농도를 숫자로 입력하세요. 일반 시료는 모르면 비워 둡니다.");render();return;}
        final String name=sampleText,op=operatorText;final boolean standard=standardValue;
        final Double temp,mto;try{temp=MeasurementCore.optionalNumber(sampleInfo.get("sample_temp_c"));mto=MeasurementCore.optionalNumber(sampleInfo.get("mto"));if(mto!=null&&mto<0)throw new NumberFormatException();}catch(NumberFormatException e){info("시료 정보","시료 온도와 MTO는 숫자로 입력하세요. 모르면 비워 둡니다.");render();return;}
        final String sampled;try{sampled=MeasurementCore.sampledAt(sampleInfo.get("sampled_at"),java.time.LocalDateTime.now());}catch(IllegalArgumentException e){info("채취 시각","채취 시각은 14:05 또는 2026-11-12 14:05 형식으로 입력하세요. 모르면 비워 둡니다.");render();return;}
        sampleInfo.put("sampled_at",sampled);final LinkedHashMap<String,String> meta=new LinkedHashMap<>(sampleInfo);final double tgt=target;final Double lo=limitLow,hi=limitHigh;
        run("시료 측정 중 · 위치를 유지하세요",()->{MeasurementCore.Status before=instrument.status();List<String> lines=instrument.ask("","0\t","SENSOR");MeasurementCore.Status after=instrument.status();return new Object[]{before,after,MeasurementCore.parse(lines,before,after)};},x->{Object[] p=(Object[])x;MeasurementCore.Status before=(MeasurementCore.Status)p[0];status=(MeasurementCore.Status)p[1];MeasurementCore.Reading r=(MeasurementCore.Reading)p[2];
            // 기기에 저장된 검량식은 누가 저장했든 그대로 쓴다. 이 휴대폰에서 저장한 식이면 그때의 표준액 농도 범위를 알기 때문에 범위 밖 농도를 보류한다.
            final double[] range=calibrationRange(before);MeasurementCore.applyRange(r,range);
            if(standard&&r.signal!=null&&r.absorbance!=null&&MeasurementCore.positive(r.signal)&&MeasurementCore.finite(r.absorbance)&&(r.code.equals("NO_MODEL")||r.code.equals("DEVICE_RESULT")||r.code.equals("OUTSIDE_CALIBRATION_RANGE"))){if(!standardEpoch.equals(before.fingerprint())){standards.clear();standardEpoch=before.fingerprint();}standards.add(new MeasurementCore.Point(cref,before.response(r.absorbance)));}
            latest=r.accepted;latestCode=r.accepted==null?"농도 결과 보류":range==null?"기기 계산값 · 검량 범위 미확인 · 정확도 검증 별도":"기기 계산값 · 정확도 검증 별도";lastMessage=r.message;
            // 기준 대비 %와 범위 판정은 받아들인 농도에만 붙인다. 표준액은 일부러 범위 밖으로 만든 액이라 판정하지 않는다.
            final Double pct=r.accepted==null?null:Math.round(MeasurementCore.percent(r.accepted,tgt)*100)/100.0;final String verdict=standard?"":MeasurementCore.verdict(r.accepted,lo,hi);
            latestDetail=pct==null?"":String.format(Locale.US,"기준 대비 %.1f%% · 기준 %s g/L",pct,MeasurementCore.plain(tgt))+(verdict.isEmpty()?"":"\n범위 판정: "+verdict+" ("+rangeText(lo,hi)+")");
            try{JSONObject record=new JSONObject();record.put("time",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date()));record.put("operator",op);record.put("name",name);record.put("standard",standard);record.put("reference_g_l",cref==null?JSONObject.NULL:cref);
                for(String k:new String[]{"sample_id","plant","line","tank","sampled_at"})record.put(k,meta.get(k));record.put("sample_temp_c",temp==null?JSONObject.NULL:temp);record.put("mto",mto==null?JSONObject.NULL:mto);
                record.put("target_g_l",tgt);record.put("percent_of_target",pct==null?JSONObject.NULL:pct);record.put("verdict",verdict);record.put("limit_low_g_l",lo==null?JSONObject.NULL:lo);record.put("limit_high_g_l",hi==null?JSONObject.NULL:hi);
                record.put("signal",arr(r.signal));record.put("absorbance",arr(r.absorbance));record.put("concentration",r.accepted==null?JSONObject.NULL:r.accepted);record.put("device_reported",r.reported==null?JSONObject.NULL:r.reported);record.put("status",r.code);record.put("message",r.message);record.put("raw",new JSONArray(r.raw));record.put("settings",statusJSON(before).put("cal_range_g_l",range==null?JSONObject.NULL:arr(range)));records.add(record);append(record);lastMessage+="\n기록이 저장되었습니다.";}catch(Exception e){lastMessage+="\n저장 실패: "+e.getMessage();info("저장 확인",lastMessage);}
            preparedValue=false;render();});
    }
    void saveLimits(String t,String lo,String hi){
        final Double nt,nlo,nhi;try{nt=MeasurementCore.optionalNumber(t);nlo=MeasurementCore.optionalNumber(lo);nhi=MeasurementCore.optionalNumber(hi);}catch(NumberFormatException e){info("기준값 설정","숫자로 입력하세요. 예: 5.2");return;}
        String problem=MeasurementCore.checkLimits(nt,nlo,nhi);if(problem!=null){info("기준값 설정",problem);return;}
        target=nt;limitLow=nlo;limitHigh=nhi;prefs.edit().putString("target",Double.toString(nt)).putString("limit_low",nlo==null?"":Double.toString(nlo)).putString("limit_high",nhi==null?"":Double.toString(nhi)).apply();
        render();info("기준값 설정","저장했습니다. 기준 "+MeasurementCore.plain(target)+" g/L = 100%"+(nlo==null&&nhi==null?" · 범위 판정 안 함":" · 범위 "+rangeText(nlo,nhi)));
    }
    static String rangeText(Double lo,Double hi){if(lo!=null&&hi!=null)return MeasurementCore.plain(lo)+"~"+MeasurementCore.plain(hi)+" g/L";if(lo!=null)return MeasurementCore.plain(lo)+" g/L 이상";return hi==null?"":MeasurementCore.plain(hi)+" g/L 이하";}
    JSONObject statusJSON(MeasurementCore.Status s)throws JSONException{JSONObject j=new JSONObject();j.put("gain",s.gain);j.put("i0",arr(s.i0));j.put("m",Double.isFinite(s.m)?s.m:JSONObject.NULL);j.put("c",Double.isFinite(s.c)?s.c:JSONObject.NULL);j.put("saved",s.saved);j.put("calc",s.calcId());return j;}
    JSONArray arr(double[] a)throws JSONException{if(a==null)return null;JSONArray j=new JSONArray();for(double x:a)j.put(Double.isFinite(x)?x:JSONObject.NULL);return j;}
    void calibrate(){if(instrument==null)return;if(!status.calcKnown()){info("검량 보류","펌웨어의 농도 계산 방식을 확인하지 못했습니다. 장비 상태를 새로고침하세요.");return;}final String calc=status.calcLabel();final MeasurementCore.Fit f;try{f=MeasurementCore.fit(standards);}catch(Exception e){info("검량 데이터 확인",e.getMessage());return;}
        if(f.r2<.995){info("검량식 저장 보류",String.format(Locale.US,"R² = %.6f\n현재 적합도 점검 기준 0.995에 못 미칩니다. 시료·영점·배치를 확인하고 다시 측정하세요.\nR²만으로 정확도가 검증되지는 않습니다.",f.r2));return;}
        confirm("검량식 저장 확인",String.format(Locale.US,"%s = %.6f × C %+.6f\nR² %.6f · %d건 · %.2f~%.2f g/L\n\n이 식을 기기에 저장할까요? 별도 시료로 정확도를 검증해야 합니다.",calc,f.m,f.c,f.r2,f.n,f.min,f.max)+(status.modelReady()?String.format(Locale.US,"\n\n기기에 있던 검량식(m=%.6f, c=%.6f)은 지워집니다.",status.m,status.c):""),()->run("검량식 저장·재확인 중",()->{MeasurementCore.Status before=instrument.status();if(!before.fingerprint().equals(standardEpoch))throw new IOException("검량 중 영점·게인·계산 방식이 바뀌었습니다. 다시 수집하세요.");instrument.ask(String.format(Locale.US,"cal %.6f %.6f",f.m,f.c),"saved","usage");MeasurementCore.Status s=instrument.status();if(!s.modelReady()||Math.abs(s.m-f.m)>1e-5||Math.abs(s.c-f.c)>1e-5||!s.fingerprint().equals(before.fingerprint()))throw new IOException("저장값 재확인이 일치하지 않습니다.");return s;},x->{status=(MeasurementCore.Status)x;rememberCalibration(f);lastMessage="검량식을 저장하고 다시 읽어 확인했습니다. 별도 시료로 정확도를 검증하세요.";render();}));
    }
    /**
     * 기기의 검량식이 이 휴대폰에서 저장한 식이면 그때 쓴 표준액 농도 범위 {최소, 최대}를 준다.
     * PC 프로그램 등 다른 곳에서 저장한 식이면 범위를 알 수 없어 null을 준다. 그 경우에도 농도는 표시한다.
     */
    double[] calibrationRange(MeasurementCore.Status s){
        try{
            if(!MeasurementCore.sameModel(s,prefs.getString("model_calc",""),Double.parseDouble(prefs.getString("model_m","NaN")),Double.parseDouble(prefs.getString("model_c","NaN"))))return null;
            double lo=Double.parseDouble(prefs.getString("model_min","NaN")),hi=Double.parseDouble(prefs.getString("model_max","NaN"));
            return Double.isFinite(lo)&&Double.isFinite(hi)?new double[]{lo,hi}:null;
        }catch(NumberFormatException e){return null;}
    }
    /** 화면에 보여 줄 검량식 상태 한 줄. */
    String calibrationNote(MeasurementCore.Status s){
        if(!s.modelReady())return "검량식 없음 · 표준액으로 검량 필요";
        double[] r=calibrationRange(s);
        return r==null?"PC 등 다른 곳에서 저장한 검량식 · 검량 범위 미확인":String.format(Locale.US,"이 휴대폰에서 저장한 검량식 · 검량 범위 %.2f~%.2f g/L",r[0],r[1]);
    }
    /** 방금 기기에 저장한 검량식과 그 표준액 농도 범위를 휴대폰에 적어 둔다. */
    void rememberCalibration(MeasurementCore.Fit f){
        prefs.edit().putString("model_calc",status.calcId())
          .putString("model_m",Double.toString(status.m)).putString("model_c",Double.toString(status.c))
          .putString("model_min",Double.toString(f.min)).putString("model_max",Double.toString(f.max))
          .remove("invalidated").remove("model_fingerprint").apply();
    }
    void confirm(String title,String msg,Runnable yes){new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setNegativeButton("취소",null).setPositiveButton("확인",(d,w)->yes.run()).show();}
    void info(String title,String msg){if(!destroyed)new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("확인",null).show();}
    File logFile(){return new File(getFilesDir(),"measurements.jsonl");}
    void append(JSONObject record)throws IOException{try(FileOutputStream out=new FileOutputStream(logFile(),true)){out.write((record.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    void loadRecords(){if(!logFile().exists())return;try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(logFile()),StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null){try{records.add(new JSONObject(l));}catch(JSONException ignored){}}}catch(Exception ignored){lastMessage="이전 기록을 읽지 못했습니다. 저장 공간을 확인하세요.";}}
    void export(){if(records.isEmpty()){info("기록 없음","먼저 실제 장비로 측정하세요.");return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"NiCheck_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".csv");startActivityForResult(i,7);}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req!=7||res!=RESULT_OK||data==null)return;try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write("\uFEFF".getBytes(StandardCharsets.UTF_8));ArrayList<String> head=new ArrayList<>();for(String[] col:CSV)head.add(col[0]);writeCSV(out,head);for(JSONObject r:records){ArrayList<String> row=new ArrayList<>();for(String[] col:CSV)row.add(cell(r,col[1]));writeCSV(out,row);}info("내보내기 완료","CSV 파일을 저장했습니다. 원본 기록도 앱에 유지됩니다.");}catch(Exception e){info("저장 실패",e.getMessage());}}
    void writeCSV(OutputStream out,List<String> row)throws IOException{ArrayList<String> q=new ArrayList<>();for(String s:row)q.add(MeasurementCore.csv(s));out.write((String.join(",",q)+"\r\n").getBytes(StandardCharsets.UTF_8));}
    @Override public void onDestroy(){destroyed=true;generation++;if(instrument!=null)instrument.close();worker.shutdownNow();try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
}
