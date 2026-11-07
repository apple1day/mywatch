package com.anxiong.watchlink;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity implements BluetoothProbe.Listener {
    private static final int PERMISSIONS = 10, ENABLE_BT = 11, EXPORT = 12;
    private static final int BACKGROUND = Color.rgb(245,247,243);
    private static final int INK = Color.rgb(29,47,39);
    private static final int SECONDARY = Color.rgb(91,111,101);
    private static final int GREEN = Color.rgb(23,99,74);
    private static final int SOFT = Color.rgb(225,238,226);
    private static final int AMBER = Color.rgb(140,91,23);
    private LinearLayout root, content;
    private ScrollView scroll;
    private BluetoothProbe probe;
    private Runnable afterPermission;
    private String exportSnapshot;
    private boolean details, showAll, foreground, touching, dirty, scrollToTop;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            exportSnapshot = savedInstanceState.getString("export_snapshot");
            details = savedInstanceState.getBoolean("details");
            showAll = savedInstanceState.getBoolean("show_all");
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(22),dp(18),dp(22),dp(28));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1,-1)); setContentView(root);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof BluetoothProbe) { probe = (BluetoothProbe) retained; probe.setListener(this); }
        else probe = new BluetoothProbe(this, this);
        render(); root.requestApplyInsets();
    }

    @Override protected void onStart() { super.onStart(); foreground = true; if (probe != null) render(); }
    @Override protected void onStop() { foreground = false; if (probe != null) probe.pause(); super.onStop(); }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (probe != null) { if (isChangingConfigurations()) probe.detachListener(this); else probe.close(); }
        super.onDestroy();
    }
    @Override public Object onRetainNonConfigurationInstance() { return probe; }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("export_snapshot",exportSnapshot); out.putBoolean("details",details); out.putBoolean("show_all",showAll);
        super.onSaveInstanceState(out);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) touching = true;
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false; if (dirty) ui.post(this::render);
        }
        return handled;
    }
    @Override public void onChanged() { if (!isFinishing() && !isDestroyed() && foreground) render(); }

    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n + .5f); }
    private GradientDrawable background(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(3),1); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private void label(LinearLayout parent, String s, float size, int color, boolean bold, int bottom) {
        TextView t = text(s,size,color,bold); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(bottom); parent.addView(t,p);
    }
    private LinearLayout card(int color) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(18),dp(18),dp(18),dp(18)); c.setBackground(background(color,20));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(14); content.addView(c,p); return c;
    }
    private Button button(String s, boolean primary, Runnable action) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(s); b.setTextSize(14); b.setMinHeight(dp(48));
        b.setTextColor(primary ? Color.WHITE : GREEN); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(background(primary ? GREEN : SOFT,12)); b.setPadding(dp(10),dp(8),dp(10),dp(8));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void fullButton(LinearLayout parent, String s, boolean primary, Runnable action) {
        Button b = button(s,primary,action); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(8); parent.addView(b,p);
    }
    private void rowButtons(LinearLayout parent, String a, Runnable aa, String b, Runnable bb) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1,-2); rp.topMargin=dp(8); parent.addView(row,rp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1); lp.rightMargin=dp(8); row.addView(button(a,false,aa),lp);
        row.addView(button(b,false,bb),new LinearLayout.LayoutParams(0,-2,1));
    }

    private void render() {
        if (probe == null) return;
        if (touching) { dirty=true; return; }
        dirty=false;
        int oldY = scroll.getScrollY(); content.removeAllViews();
        label(content,"ONEPLUS WATCH · 国行一代",11,GREEN,true,8);
        label(content,"让连接看得见",28,INK,true,6);
        label(content,"一代手表连接  /  0.1.0 验证版",13,SECONDARY,false,20);

        LinearLayout scope = card(Color.rgb(253,245,226));
        label(scope,"当前可用：扫描、系统配对、连接检测",14,AMBER,true,5);
        label(scope,"原厂绑定、通知和健康同步尚未实现。需要用你的手表验证实际连接结果。",13,AMBER,false,0);

        if (!probe.supported()) {
            LinearLayout c = card(Color.WHITE); label(c,"这台设备未提供蓝牙适配器",18,INK,true,8);
            label(c,"请在带蓝牙的安卓手机上运行。模拟器不能代表真实手表连接结果。",14,SECONDARY,false,0); return;
        }

        LinearLayout status = card(Color.WHITE);
        label(status,probe.state(),19,INK,true,12);
        label(status,"01  发现设备        " + (probe.selected() != null ? "已选择" : "等待选择"),14,probe.selected()!=null?GREEN:SECONDARY,true,7);
        boolean anyTransport = probe.connected() || probe.classicConnected();
        label(status,"02  蓝牙通道        " + (anyTransport ? "已连接" : "未连接"),14,anyTransport?GREEN:SECONDARY,true,7);
        label(status,"03  原厂绑定与同步    待适配",14,SECONDARY,false,0);
        if (!probe.permissionsGranted()) fullButton(status,"授权附近设备",true,() -> ready(() -> probe.loadBonded()));
        else if (!probe.enabled()) fullButton(status,"打开蓝牙",true,() -> ready(() -> probe.loadBonded()));

        BluetoothProbe.Device selected = probe.selected();
        if (selected != null) {
            LinearLayout c = card(SOFT);
            label(c,selected.candidate()?"一代名称候选 · 以实际型号为准":"已选择的蓝牙设备",11,GREEN,true,5);
            label(c,selected.name,21,INK,true,4);
            label(c,selected.addressLabel()+"  ·  "+BluetoothProbe.bondLabel(selected.bondState),13,SECONDARY,false,12);
            fullButton(c,probe.classicWorking()?(probe.classicConnected()?"断开一代数据通道":"取消经典蓝牙连接"):"连接一代数据通道",true,() -> ready(() -> { if (probe.classicWorking()) probe.disconnect(); else probe.connectClassic(); }));
            label(c,"使用已从安装包确认的经典蓝牙双通道。首次连接时请留意系统与手表上的配对提示。",12,SECONDARY,false,6);
            rowButtons(c,"系统配对",() -> ready(probe::pair),"查询经典服务",() -> ready(probe::inspectClassicServices));
            fullButton(c,probe.bleWorking()?(probe.connected()?"断开 BLE 检测连接":"取消 BLE 连接"):"BLE 连接检测（备用）",false,() -> ready(() -> { if (probe.bleWorking()) probe.disconnect(); else probe.connect(); }));
            if (probe.connected() && probe.servicesKnown()) fullButton(c,"读取基础信息",false,() -> ready(probe::readBasicInfo));
            label(c,"连接与配对结果分别显示。系统配对成功后，仍可能需要原厂应用的认证。",12,SECONDARY,false,0);
        }

        LinearLayout discovery = card(Color.WHITE);
        label(discovery,"附近的手表",18,INK,true,4);
        label(discovery,"将手表放在手机旁，保持屏幕亮起。国行一代优先使用经典蓝牙扫描，选择 OnePlus Watch 开头的设备。",13,SECONDARY,false,10);
        if (probe.scanning()) fullButton(discovery,"停止扫描",true,() -> { probe.stopScan(); render(); });
        else fullButton(discovery,"扫描一代手表 · 经典蓝牙",true,() -> ready(() -> locationThen(probe::scanClassic)));
        rowButtons(discovery,"BLE 扫描（备用）",() -> ready(this::startBleWithLocationCheck),"系统已配对",() -> ready(probe::loadBonded));

        List<BluetoothProbe.Device> all = probe.devices();
        int shown=0;
        for (BluetoothProbe.Device d : all) {
            if (!showAll && !d.candidate() && d != selected) continue;
            LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setPadding(dp(12),dp(12),dp(12),dp(12));
            item.setBackground(background(d==selected?SOFT:BACKGROUND,12));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1,-2); ip.topMargin=dp(9); discovery.addView(item,ip);
            label(item,d.name,15,INK,true,4);
            String signal = d.rssi == 0 ? "" : "  ·  "+d.rssi+" dBm";
            label(item,d.addressLabel()+"  ·  "+d.source+signal,11,SECONDARY,false,4);
            label(item,d==selected?"已选择":"点击选择设备",12,GREEN,true,0);
            item.setOnClickListener(v -> { probe.select(d); scrollToTop=true; render(); });
            item.setContentDescription(d.name+"，"+(d==selected?"已选择":"点击选择"));
            shown++; if (shown>=40) break;
        }
        if (shown == 0) label(discovery,all.isEmpty()?"扫描后，设备会出现在这里。":"暂未发现一代名称候选。可显示其他设备，或尝试经典蓝牙扫描。",13,SECONDARY,false,0);
        fullButton(discovery,showAll?"仅显示一代名称候选":"显示其他设备（共 "+all.size()+" 个）",false,() -> { showAll=!showAll; render(); });

        if (!probe.information().isEmpty()) {
            LinearLayout c=card(Color.WHITE); label(c,"读取到的基础信息",18,INK,true,10);
            for (Map.Entry<String,String> e : probe.information().entrySet()) label(c,e.getKey()+"  "+e.getValue(),14,SECONDARY,false,6);
            label(c,"仅显示手表实际返回的标准字段；缺少字段不代表设备没有该能力。",12,SECONDARY,false,0);
        }

        LinearLayout report = card(Color.WHITE);
        label(report,"连接报告",18,INK,true,5);
        label(report,"记录手机系统、配对结果和蓝牙服务，帮助继续适配。报告默认隐藏完整蓝牙地址，分享前可自行查看。",13,SECONDARY,false,7);
        fullButton(report,"导出报告",true,this::export);
        rowButtons(report,details?"收起技术详情":"查看技术详情",() -> { details=!details; render(); },"使用说明",this::help);
        if (details) {
            StringBuilder s=new StringBuilder();
            s.append("GATT 服务\n");
            if (probe.serviceLines().isEmpty()) s.append("尚未发现服务\n");
            for(String line:probe.serviceLines())s.append(line).append('\n');
            s.append("\n经典蓝牙服务（SDP）\n");
            if(probe.classicServiceLines().isEmpty())s.append("尚无记录\n");
            for(String line:probe.classicServiceLines())s.append(line).append('\n');
            s.append("\n运行记录\n"); for(String line:probe.eventLines())s.append(line).append('\n');
            TextView tv=text(s.toString(),11,SECONDARY,false); tv.setTypeface(Typeface.MONOSPACE); tv.setTextIsSelectable(true); tv.setPadding(0,dp(14),0,0); report.addView(tv);
        }
        label(content,"独立测试应用 · 无联网权限 · 无需 OPPO 账号\n仅在前台保持连接；切到后台会释放扫描与连接。",11,SECONDARY,false,0);
        final int destination = scrollToTop ? 0 : oldY; scrollToTop=false;
        scroll.post(() -> scroll.scrollTo(0,destination));
    }

    private void startBleWithLocationCheck() { locationThen(probe::scanBle); }
    private void locationThen(Runnable action) {
        if (Build.VERSION.SDK_INT < 31) {
            LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            boolean enabled=false;
            try { enabled=lm!=null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)||lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)); } catch(Exception ignored){}
            if (!enabled) {
                new AlertDialog.Builder(this).setTitle("开启系统定位开关")
                    .setMessage("Android 8–11 的蓝牙扫描通常需要开启定位开关。本应用不读取地理位置，也没有联网权限。开启后返回，再点击扫描。")
                    .setPositiveButton("前往设置",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("取消",null).show(); return;
            }
        }
        action.run();
    }

    private void ready(Runnable action) {
        if (!probe.supported()) return;
        if (!probe.permissionsGranted()) {
            afterPermission=action;
            String[] permissions=Build.VERSION.SDK_INT>=31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(permissions,PERMISSIONS); return;
        }
        if (!probe.enabled()) {
            afterPermission=action;
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT); }
            catch(Exception e) { afterPermission=null; Toast.makeText(this,"请在系统设置中打开蓝牙。",Toast.LENGTH_LONG).show(); }
            return;
        }
        action.run(); render();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode!=PERMISSIONS)return;
        Runnable next=afterPermission; afterPermission=null;
        if(probe.permissionsGranted()) { if(next!=null)ready(next); }
        else new AlertDialog.Builder(this).setTitle("需要蓝牙扫描与连接权限")
            .setMessage("授权后才能发现和连接手表。如果已选择不再询问，请在应用设置中开启权限。")
            .setPositiveButton("应用设置",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
            .setNegativeButton("稍后",null).show();
        render();
    }

    private void export() {
        exportSnapshot=probe.report();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,"Watch-Link-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".json");
        try { startActivityForResult(intent,EXPORT); }
        catch(ActivityNotFoundException e) { exportSnapshot=null; Toast.makeText(this,"系统未提供文件保存器。",Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==ENABLE_BT) {
            Runnable next=afterPermission; afterPermission=null;
            if(resultCode==RESULT_OK&&next!=null)ready(next); else render();
        } else if(requestCode==EXPORT) {
            String snapshot=exportSnapshot; exportSnapshot=null;
            if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
            if(snapshot==null) { Toast.makeText(this,"待导出的报告已丢失，请返回重新导出。",Toast.LENGTH_LONG).show(); return; }
            try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")) {
                if(out==null)throw new java.io.IOException("no stream");
                out.write(snapshot.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this,"报告已保存，可上传到当前聊天继续分析。",Toast.LENGTH_LONG).show();
            }catch(Exception e){Toast.makeText(this,"保存失败："+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();}
        }
    }

    private void help() {
        new AlertDialog.Builder(this).setTitle("如何验证国行一代连接")
            .setMessage("1. 保留原有绑定和应用数据，让手表保持亮屏并靠近手机。\n\n2. 授权附近设备，使用经典蓝牙扫描，或选择系统已配对设备。通常寻找 OnePlus Watch 加后缀的名称；名称匹配仅是候选提示。\n\n3. 选择设备，点击连接一代数据通道，并查看手机与手表的系统配对提示。检测会先连接主通道，再连接数据通道；只被动接收，不发送原厂认证指令。\n\n4. BLE 扫描、GATT 连接和标准基础信息读取是备用诊断。BLE 失败不能说明一代不受支持。\n\n5. 导出报告，上传到当前聊天。\n\n本版未实现原厂账号绑定、通知、健康数据同步、表盘或固件升级。请按实际状态判断，蓝牙通道连通不代表这些功能已经恢复。")
            .setPositiveButton("知道了",null).show();
    }
}
