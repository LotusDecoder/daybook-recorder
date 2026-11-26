package io.github.lotusdecoder.daybookrecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import io.github.lotusdecoder.daybookrecorder.core.ConnectionHud;
import io.github.lotusdecoder.daybookrecorder.core.DeviceIdentity;
import io.github.lotusdecoder.daybookrecorder.core.Protocol;
import io.github.lotusdecoder.daybookrecorder.core.RecordingSizeUnit;

/**
 * Graphic, low-text 3-tab UI for Daybook Recorder (v0.2.0):
 * Tab 0: 连接 (4-state lifecycle hero + waveform/MB/% live transfer card + bean storage row)
 * Tab 1: 手机副本 (audio waveform cards + minimalist right-side trash button + bottom confirm bar)
 * Tab 2: 设置与维护 (grouped switches + maintenance grid + color-coded event log)
 * Closing the Activity never stops the foreground BLE RecorderService.
 */
@SuppressLint({"MissingPermission", "SetTextI18n"})
public final class MainActivity extends Activity {
    private static final DateTimeFormatter LOG_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RecordingStore store;
    private DeviceFilesStore deviceFiles;

    // Top bar & Bottom 3-tab navigation
    private int activeTab = 0; // 0=conn, 1=copies, 2=settings
    private int copyFilter = 0; // 0=all, 1=active, 2=localOnly
    private TextView topTitle;
    private LinearLayout topServicePill;
    private View topServiceDot;
    private TextView topServiceText;
    private LinearLayout topRetryBtn;
    private TextView topVersionChip;

    private View tabConnView, tabCopiesView, tabSettingsView;
    private LinearLayout navConnTab, navCopiesTab, navSettingsTab;
    private UiGraphics.VectorIconView navConnIcon, navCopiesIcon, navSettingsIcon;
    private TextView navConnLbl, navCopiesLbl, navSettingsLbl, navCopiesBadge;

    // Tab 0: Connection Hero & Live Transfer
    private TextView heroDeviceChip, heroActionBtn, heroStateTitle, heroStateSub;
    private UiGraphics.BeanHeroView beanHeroView;
    private LinearLayout connectingStepper;
    private TextView stepBle, stepKey, stepSync;
    private LinearLayout stableMetricsRow;
    private UiGraphics.VectorIconView metricBatIcon, metricSigIcon;
    private TextView metricBatVal, metricSigVal, metricSigLbl, metricSpanVal;
    private LinearLayout disconnectedBtnRow;
    private LinearLayout reconnectingBox;
    private TextView rcHeadline, rcSubLeft;
    private UiGraphics.SmoothProgressView rcProgressBar;

    private TextView transferSecTitle, transferSecBadge;
    private LinearLayout activeTransferCard;
    private TextView tfTimeText, tfStageText, tfDurationChip, tfPercentText, tfMbText;
    private LinearLayout tfStagePill, tfAudioPill;
    private UiGraphics.VectorIconView tfStageIcon, tfAudioIcon;
    private UiGraphics.WaveformBarView tfWaveform;
    private UiGraphics.SmoothProgressView tfProgressBar;

    private LinearLayout idleSummaryCard;
    private TextView idleTitleText, idleSubText, idleBadgeText;

    private TextView beanMiniTitle, beanMiniSub;
    private TextView beanIconBox;

    // Tab 1: Phone Copies
    private TextView copiesSummaryText;
    private TextView segAll, segActive, segLocal;
    private LinearLayout copiesContainer;
    private String renderedCopiesKey = "";
    private final Map<String, CopyRowHolder> rowHolders = new HashMap<>();

    // Inline Bottom Delete Confirmation Strip
    private LinearLayout deleteConfirmStrip;
    private TextView delConfirmTitle, delConfirmSub;
    private RecordingStore.Row pendingDeleteRow;

    // Tab 2: Settings, Maintenance & Logs
    private TextView relaySubText, relayBadgeText;
    private UiGraphics.SwitchRowCheckBox syncOnConnectSwitch;
    private UiGraphics.SwitchRowCheckBox autoCleanupSwitch;
    private boolean changingCleanup;
    private TextView batterySubText, batteryBadgeText;
    private UiGraphics.SwitchRowCheckBox bootRestoreSwitch;
    private TextView maintBeanSub, maintBindSub, maintHistoryBadge;
    private LinearLayout logLinesContainer;
    private String lastLogTail = "";

    // BLE scanning, playback & SAF export
    private BluetoothLeScanner scanner;
    private boolean scanning, startAfterScan;
    private AlertDialog scanDialog;
    private ArrayAdapter<String> scanAdapter;
    private final ArrayList<BluetoothDevice> scanDevices = new ArrayList<>();
    private final ArrayList<String> scanAdvHashes = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private File exportFile;
    private String exportText;
    private MediaPlayer player;
    private String playingRowId = "";
    private Dialog beanSheetDialog;
    private Runnable beanSheetRefresh;

    private static final class CopyRowHolder {
        RecordingStore.Row row;
        LinearLayout card;
        FrameLayout playCircle;
        UiGraphics.VectorIconView playIcon;
        TextView timeText, badgeText, durChip, mbText, noteText;
        LinearLayout badgeBox;
        UiGraphics.VectorIconView badgeIcon;
        UiGraphics.WaveformBarView waveform;
        UiGraphics.SmoothProgressView miniProg;
        FrameLayout delBtn;
        UiGraphics.VectorIconView delIcon;
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            renderAll();
            boolean fast = RuntimeTelemetry.httpUpload != null
                    || RuntimeTelemetry.bleTransfer != null
                    || player != null
                    || (isRunning() && !prefs().getBoolean("ble_ready", false));
            main.postDelayed(this, fast ? 400L : 1500L);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new RecordingStore(this);
        deviceFiles = new DeviceFilesStore(this);
        io.execute(() -> {
            try (RecordingStore pending = new RecordingStore(getApplicationContext())) {
                pending.finishPendingDeletes();
                if (pending.reconcileDuplicates() > 0) {
                    main.post(this::renderAll);
                }
            }
        });

        Window w = getWindow();
        w.setStatusBarColor(UiGraphics.BG);
        w.setNavigationBarColor(Color.WHITE);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = vBox();
        root.setBackgroundColor(UiGraphics.BG);

        LinearLayout appBar = buildAppBar();
        root.addView(appBar, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        tabConnView = buildConnectionTab();
        tabCopiesView = buildCopiesTab();
        tabSettingsView = buildSettingsTab();
        content.addView(tabConnView, new FrameLayout.LayoutParams(-1, -1));
        content.addView(tabCopiesView, new FrameLayout.LayoutParams(-1, -1));
        content.addView(tabSettingsView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        deleteConfirmStrip = buildDeleteConfirmStrip();
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(-1, -2);
        stripLp.setMargins(dp(16), dp(4), dp(16), dp(8));
        root.addView(deleteConfirmStrip, stripLp);

        LinearLayout bottomNav = buildBottomNav();
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, -2));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            appBar.setPadding(dp(18), dp(10) + insets.getSystemWindowInsetTop(), dp(18), dp(8));
            bottomNav.setPadding(dp(12), dp(6), dp(12), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        setContentView(root);
        selectTab(0);
        renderAll();
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    @Override protected void onPause() {
        main.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (beanSheetDialog != null && beanSheetDialog.isShowing()) beanSheetDialog.dismiss();
        stopScan();
        releasePlayer();
        io.shutdown();
        store.close();
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Top Bar & Bottom Navigation
    // -----------------------------------------------------------------------

    private LinearLayout buildAppBar() {
        LinearLayout bar = hBox();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(12), dp(18), dp(8));

        topTitle = text(getString(R.string.app_name), 21f, UiGraphics.INK, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(topTitle, titleLp);

        // Connection status pill (Tab 0)
        topServicePill = hBox();
        topServicePill.setGravity(Gravity.CENTER_VERTICAL);
        topServicePill.setPadding(dp(11), dp(5), dp(11), dp(5));
        topServiceDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7.5f), dp(7.5f));
        dotLp.rightMargin = dp(6);
        topServicePill.addView(topServiceDot, dotLp);
        topServiceText = text("已暂停", 12f, UiGraphics.SLATE, true);
        topServicePill.addView(topServiceText);
        topServicePill.setOnClickListener(v -> {
            if (isRunning()) stopSession();
            else startSessionOrScan();
        });
        bar.addView(topServicePill);

        // Retry queue pill (Tab 1)
        topRetryBtn = hBox();
        topRetryBtn.setGravity(Gravity.CENTER_VERTICAL);
        topRetryBtn.setPadding(dp(11), dp(6), dp(11), dp(6));
        topRetryBtn.setBackground(roundBg(0xffedf5f2, dp(99), 0, 0));
        UiGraphics.VectorIconView refIcon = new UiGraphics.VectorIconView(
                this, UiGraphics.VectorIconView.Kind.REFRESH, UiGraphics.GREEN);
        LinearLayout.LayoutParams riLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        riLp.rightMargin = dp(4);
        topRetryBtn.addView(refIcon, riLp);
        topRetryBtn.addView(text("重试队列", 12f, UiGraphics.GREEN, true));
        topRetryBtn.setOnClickListener(v -> triggerRefreshAndRetry());
        bar.addView(topRetryBtn);

        // Version chip (Tab 2)
        topVersionChip = text("v" + appVersion(), 12f, UiGraphics.MUTED, true);
        topVersionChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        topVersionChip.setBackground(roundBg(0xffe8edea, dp(99), 0, 0));
        bar.addView(topVersionChip);

        return bar;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = hBox();
        nav.setBackgroundColor(Color.WHITE);
        nav.setElevation(dp(6));
        nav.setPadding(dp(12), dp(6), dp(12), dp(8));

        navConnIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.NAV_LINK, UiGraphics.GREEN);
        navConnLbl = text("连接", 11f, UiGraphics.GREEN, true);
        navConnTab = buildNavTabItem(navConnIcon, navConnLbl, null, () -> selectTab(0));

        navCopiesIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.NAV_AUDIO, UiGraphics.MUTED);
        navCopiesLbl = text("手机副本", 11f, UiGraphics.MUTED, false);
        navCopiesBadge = text("0", 9.5f, Color.WHITE, true);
        navCopiesBadge.setPadding(dp(5), dp(1), dp(5), dp(1));
        navCopiesBadge.setBackground(roundBg(UiGraphics.GREEN, dp(99), 0, 0));
        navCopiesBadge.setVisibility(View.GONE);
        navCopiesTab = buildNavTabItem(navCopiesIcon, navCopiesLbl, navCopiesBadge, () -> selectTab(1));

        navSettingsIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.NAV_SETTINGS, UiGraphics.MUTED);
        navSettingsLbl = text("设置与维护", 11f, UiGraphics.MUTED, false);
        navSettingsTab = buildNavTabItem(navSettingsIcon, navSettingsLbl, null, () -> selectTab(2));

        nav.addView(navConnTab, new LinearLayout.LayoutParams(0, dp(52), 1f));
        nav.addView(navCopiesTab, new LinearLayout.LayoutParams(0, dp(52), 1f));
        nav.addView(navSettingsTab, new LinearLayout.LayoutParams(0, dp(52), 1f));
        return nav;
    }

    private LinearLayout buildNavTabItem(UiGraphics.VectorIconView icon, TextView lbl, TextView badge, Runnable onClick) {
        LinearLayout wrap = vBox();
        wrap.setGravity(Gravity.CENTER);
        FrameLayout iconFrame = new FrameLayout(this);
        FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        iconFrame.addView(icon, icLp);
        if (badge != null) {
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            iconFrame.addView(badge, bLp);
        }
        wrap.addView(iconFrame, new LinearLayout.LayoutParams(dp(52), dp(22)));
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-2, -2);
        lLp.topMargin = dp(2);
        wrap.addView(lbl, lLp);
        wrap.setOnClickListener(v -> onClick.run());
        return wrap;
    }

    void selectTab(int tab) {
        activeTab = tab;
        tabConnView.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        tabCopiesView.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        tabSettingsView.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);

        topTitle.setText(tab == 0 ? getString(R.string.app_name) : tab == 1 ? "手机副本" : "设置与维护");
        topServicePill.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        topRetryBtn.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        topVersionChip.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);

        styleNavTab(navConnTab, navConnIcon, UiGraphics.VectorIconView.Kind.NAV_LINK, navConnLbl, tab == 0);
        styleNavTab(navCopiesTab, navCopiesIcon, UiGraphics.VectorIconView.Kind.NAV_AUDIO, navCopiesLbl, tab == 1);
        styleNavTab(navSettingsTab, navSettingsIcon, UiGraphics.VectorIconView.Kind.NAV_SETTINGS, navSettingsLbl, tab == 2);
        if (tab != 1 && pendingDeleteRow != null) hideDeleteConfirm();
        renderAll();
    }

    private void styleNavTab(LinearLayout box, UiGraphics.VectorIconView icon,
                             UiGraphics.VectorIconView.Kind kind, TextView lbl, boolean selected) {
        box.setBackground(selected ? roundBg(0xffecf5f2, dp(14), 0, 0) : null);
        int color = selected ? UiGraphics.GREEN : 0xff7a8c85;
        icon.setIcon(kind, color);
        lbl.setTextColor(color);
        lbl.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    // -----------------------------------------------------------------------
    // Tab 0: Connection Lifecycle & Graphic Upload Progress
    // -----------------------------------------------------------------------

    private View buildConnectionTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = vBox();
        col.setPadding(dp(16), dp(6), dp(16), dp(12));
        scroll.addView(col);

        // Hero Card
        LinearLayout hero = vBox();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(16), dp(15), dp(16), dp(15));
        hero.setBackground(roundBg(Color.WHITE, dp(22), dp(1), 0x10172f29));

        LinearLayout topRow = hBox();
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        heroDeviceChip = text("尚未选择录音豆", 12f, 0xff52665e, true);
        heroDeviceChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        heroDeviceChip.setBackground(roundBg(0xfff3f6f4, dp(99), 0, 0));
        heroDeviceChip.setSingleLine(true);
        heroDeviceChip.setEllipsize(TextUtils.TruncateAt.END);
        heroDeviceChip.setOnClickListener(v -> scan(true));
        topRow.addView(heroDeviceChip, new LinearLayout.LayoutParams(-2, -2));
        View spacer = new View(this);
        topRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        heroActionBtn = text("选择设备", 11.5f, UiGraphics.GREEN, true);
        heroActionBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        heroActionBtn.setBackground(roundBg(0xffedf5f2, dp(99), 0, 0));
        heroActionBtn.setOnClickListener(v -> {
            if (isRunning()) stopSession();
            else scan(false);
        });
        topRow.addView(heroActionBtn);
        hero.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        beanHeroView = new UiGraphics.BeanHeroView(this);
        LinearLayout.LayoutParams beanLp = new LinearLayout.LayoutParams(dp(140), dp(140));
        beanLp.topMargin = dp(2);
        hero.addView(beanHeroView, beanLp);

        heroStateTitle = text("连接已断开", 19f, UiGraphics.INK, true);
        heroStateTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(-1, -2);
        htLp.topMargin = dp(4);
        hero.addView(heroStateTitle, htLp);

        heroStateSub = text("按实体键开始录音 · 再按结束自动同步", 12.5f, UiGraphics.MUTED, false);
        heroStateSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-1, -2);
        hsLp.topMargin = dp(3);
        hero.addView(heroStateSub, hsLp);

        // Mode 1: Connecting Stepper
        connectingStepper = hBox();
        connectingStepper.setGravity(Gravity.CENTER);
        connectingStepper.setPadding(dp(10), dp(9), dp(10), dp(9));
        connectingStepper.setBackground(roundBg(0xfff4f9fb, dp(13), 0, 0));
        stepBle = text("● 蓝牙就绪", 11.5f, UiGraphics.BLUE, true);
        stepKey = text("○ 密钥握手", 11.5f, UiGraphics.SLATE_LIGHT, true);
        stepSync = text("○ 同步豆内", 11.5f, UiGraphics.SLATE_LIGHT, true);
        connectingStepper.addView(stepBle);
        connectingStepper.addView(stepLine());
        connectingStepper.addView(stepKey);
        connectingStepper.addView(stepLine());
        connectingStepper.addView(stepSync);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.topMargin = dp(12);
        hero.addView(connectingStepper, stLp);

        // Mode 2: Stable Metrics Row (Battery, Signal, Stable Duration)
        stableMetricsRow = hBox();
        metricBatIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.BATTERY, UiGraphics.GREEN);
        metricBatVal = text("--%", 12.5f, UiGraphics.INK, true);
        LinearLayout batPill = buildMetricPill(metricBatIcon, metricBatVal, text("录音豆电量", 10.5f, UiGraphics.MUTED, false));

        metricSigIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.SIGNAL, UiGraphics.GREEN);
        metricSigVal = text("在线", 12.5f, UiGraphics.INK, true);
        metricSigLbl = text("蓝牙信号", 10.5f, UiGraphics.MUTED, false);
        LinearLayout sigPill = buildMetricPill(metricSigIcon, metricSigVal, metricSigLbl);

        UiGraphics.VectorIconView spanIcon = new UiGraphics.VectorIconView(
                this, UiGraphics.VectorIconView.Kind.CLOCK, UiGraphics.GREEN);
        metricSpanVal = text("00m 01s", 12.5f, UiGraphics.INK, true);
        LinearLayout spanPill = buildMetricPill(spanIcon, metricSpanVal, text("持续稳定连接", 10.5f, UiGraphics.MUTED, false));

        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, -2, 1f);
        m1.rightMargin = dp(6);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, -2, 1f);
        m2.rightMargin = dp(6);
        stableMetricsRow.addView(batPill, m1);
        stableMetricsRow.addView(sigPill, m2);
        stableMetricsRow.addView(spanPill, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(-1, -2);
        mrLp.topMargin = dp(12);
        hero.addView(stableMetricsRow, mrLp);

        // Mode 3: Disconnected Action Row
        disconnectedBtnRow = hBox();
        TextView primaryStart = text("开启连接守护", 13.5f, Color.WHITE, true);
        primaryStart.setGravity(Gravity.CENTER);
        primaryStart.setPadding(dp(12), dp(11), dp(12), dp(11));
        primaryStart.setBackground(roundBg(UiGraphics.GREEN, dp(12), 0, 0));
        primaryStart.setOnClickListener(v -> startSessionOrScan());

        TextView scanNew = text("扫描新豆", 13f, UiGraphics.GREEN, true);
        scanNew.setGravity(Gravity.CENTER);
        scanNew.setPadding(dp(12), dp(11), dp(12), dp(11));
        scanNew.setBackground(roundBg(0xffedf4f0, dp(12), 0, 0));
        scanNew.setOnClickListener(v -> scan(false));

        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, -2, 1.45f);
        pLp.rightMargin = dp(8);
        disconnectedBtnRow.addView(primaryStart, pLp);
        disconnectedBtnRow.addView(scanNew, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(-1, -2);
        dbLp.topMargin = dp(12);
        hero.addView(disconnectedBtnRow, dbLp);

        // Mode 4: Reconnecting after stable session box
        reconnectingBox = vBox();
        reconnectingBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        reconnectingBox.setBackground(roundBg(0xfffffbeb, dp(14), dp(1), UiGraphics.AMBER_BORDER));
        LinearLayout rcTop = hBox();
        rcTop.setGravity(Gravity.CENTER_VERTICAL);
        rcHeadline = text("退避重试中", 12f, 0xff92400e, true);
        rcTop.addView(rcHeadline, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView rcNowBtn = text("立即重连", 11.5f, Color.WHITE, true);
        rcNowBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        rcNowBtn.setBackground(roundBg(UiGraphics.AMBER, dp(9), 0, 0));
        rcNowBtn.setOnClickListener(v -> serviceAction(RecorderService.REFRESH));
        rcTop.addView(rcNowBtn);
        reconnectingBox.addView(rcTop, new LinearLayout.LayoutParams(-1, -2));

        rcProgressBar = new UiGraphics.SmoothProgressView(this);
        LinearLayout.LayoutParams rcpLp = new LinearLayout.LayoutParams(-1, dp(5));
        rcpLp.topMargin = dp(7);
        rcpLp.bottomMargin = dp(7);
        reconnectingBox.addView(rcProgressBar, rcpLp);

        LinearLayout rcFoot = hBox();
        rcSubLeft = text("上次电量 --%", 11f, 0xff92400e, false);
        rcFoot.addView(rcSubLeft, new LinearLayout.LayoutParams(0, -2, 1f));
        rcFoot.addView(text("豆内实体键录音不受影响", 11f, 0xff92400e, false));
        reconnectingBox.addView(rcFoot, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams rcbLp = new LinearLayout.LayoutParams(-1, -2);
        rcbLp.topMargin = dp(11);
        hero.addView(reconnectingBox, rcbLp);

        col.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        // Transfer Section Header
        LinearLayout secHead = hBox();
        secHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(-1, -2);
        shLp.topMargin = dp(12);
        shLp.bottomMargin = dp(7);
        transferSecTitle = text("传输队列", 12.5f, 0xff4a5e56, true);
        secHead.addView(transferSecTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        transferSecBadge = text("✓ 全部已备份", 11f, UiGraphics.GREEN, true);
        transferSecBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        secHead.addView(transferSecBadge);
        col.addView(secHead, shLp);

        // Active Transfer Card (Waveform + Audio Duration + Progress Bar + % + MB)
        activeTransferCard = vBox();
        activeTransferCard.setPadding(dp(14), dp(13), dp(14), dp(13));
        activeTransferCard.setBackground(roundBg(Color.WHITE, dp(20), dp(1), 0x26176b5b));

        LinearLayout tfTop = hBox();
        tfTop.setGravity(Gravity.CENTER_VERTICAL);
        tfTimeText = text("", 13f, UiGraphics.INK, true);
        tfTop.addView(tfTimeText, new LinearLayout.LayoutParams(0, -2, 1f));
        tfStagePill = hBox();
        tfStagePill.setGravity(Gravity.CENTER_VERTICAL);
        tfStagePill.setPadding(dp(8), dp(3), dp(8), dp(3));
        tfStageIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.UPLOAD, UiGraphics.GREEN);
        LinearLayout.LayoutParams tsiLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        tsiLp.rightMargin = dp(4);
        tfStagePill.addView(tfStageIcon, tsiLp);
        tfStageText = text("上传至中转", 11f, UiGraphics.GREEN, true);
        tfStagePill.addView(tfStageText);
        tfTop.addView(tfStagePill);
        activeTransferCard.addView(tfTop, new LinearLayout.LayoutParams(-1, -2));

        tfAudioPill = hBox();
        tfAudioPill.setGravity(Gravity.CENTER_VERTICAL);
        tfAudioPill.setPadding(dp(10), dp(7), dp(10), dp(7));
        tfAudioPill.setBackground(roundBg(0xfff4f8f6, dp(12), 0, 0));
        tfAudioIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.PLAY, UiGraphics.GREEN);
        LinearLayout.LayoutParams taiLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        taiLp.rightMargin = dp(8);
        tfAudioPill.addView(tfAudioIcon, taiLp);
        tfWaveform = new UiGraphics.WaveformBarView(this, 24);
        LinearLayout.LayoutParams twLp = new LinearLayout.LayoutParams(0, dp(22), 1f);
        twLp.rightMargin = dp(8);
        tfAudioPill.addView(tfWaveform, twLp);
        tfDurationChip = monoText("00:00", 12f, UiGraphics.GREEN, true);
        tfDurationChip.setPadding(dp(7), dp(2), dp(7), dp(2));
        tfDurationChip.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(6), 0, 0));
        tfAudioPill.addView(tfDurationChip);
        LinearLayout.LayoutParams tapLp = new LinearLayout.LayoutParams(-1, -2);
        tapLp.topMargin = dp(9);
        tapLp.bottomMargin = dp(9);
        activeTransferCard.addView(tfAudioPill, tapLp);

        tfProgressBar = new UiGraphics.SmoothProgressView(this);
        activeTransferCard.addView(tfProgressBar, new LinearLayout.LayoutParams(-1, dp(7)));

        LinearLayout tfFoot = hBox();
        tfFoot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tffLp = new LinearLayout.LayoutParams(-1, -2);
        tffLp.topMargin = dp(6);
        tfPercentText = text("0%", 13f, UiGraphics.GREEN, true);
        tfFoot.addView(tfPercentText, new LinearLayout.LayoutParams(0, -2, 1f));
        tfMbText = monoText("0.00 MB", 11.5f, 0xff52665e, true);
        tfFoot.addView(tfMbText);
        activeTransferCard.addView(tfFoot, tffLp);
        activeTransferCard.setOnClickListener(v -> selectTab(1));
        col.addView(activeTransferCard, new LinearLayout.LayoutParams(-1, -2));

        // Idle Calm Summary Card
        idleSummaryCard = hBox();
        idleSummaryCard.setGravity(Gravity.CENTER_VERTICAL);
        idleSummaryCard.setPadding(dp(15), dp(14), dp(15), dp(14));
        idleSummaryCard.setBackground(roundBg(Color.WHITE, dp(20), dp(1), 0x10172f29));
        FrameLayout okCircle = new FrameLayout(this);
        okCircle.setBackground(roundBg(0xffe6f3ef, dp(99), 0, 0));
        okCircle.addView(new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.CLOUD_OK, UiGraphics.GREEN),
                new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        LinearLayout.LayoutParams ocLp = new LinearLayout.LayoutParams(dp(35), dp(35));
        ocLp.rightMargin = dp(11);
        idleSummaryCard.addView(okCircle, ocLp);

        LinearLayout idleMid = vBox();
        idleTitleText = text("准备就绪", 13.5f, UiGraphics.INK, true);
        idleSubText = text("按录音豆实体键即可录制语音", 11.5f, UiGraphics.MUTED, false);
        LinearLayout.LayoutParams isLp = new LinearLayout.LayoutParams(-1, -2);
        isLp.topMargin = dp(2);
        idleMid.addView(idleTitleText);
        idleMid.addView(idleSubText, isLp);
        idleSummaryCard.addView(idleMid, new LinearLayout.LayoutParams(0, -2, 1f));

        idleBadgeText = text("0 待办", 11f, UiGraphics.GREEN, true);
        idleBadgeText.setPadding(dp(8), dp(4), dp(8), dp(4));
        idleBadgeText.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(7), 0, 0));
        idleSummaryCard.addView(idleBadgeText);
        idleSummaryCard.setOnClickListener(v -> selectTab(1));
        col.addView(idleSummaryCard, new LinearLayout.LayoutParams(-1, -2));

        // Push Bean Storage Row to bottom of screen when tall
        View flexPush = new View(this);
        col.addView(flexPush, new LinearLayout.LayoutParams(-1, dp(10), 1f));

        // Bean Storage Bottom Entry Row -> Opens Bean Files Bottom Sheet
        LinearLayout beanRow = hBox();
        beanRow.setGravity(Gravity.CENTER_VERTICAL);
        beanRow.setPadding(dp(14), dp(11), dp(14), dp(11));
        beanRow.setBackground(roundBg(Color.WHITE, dp(16), dp(1), 0x10172f29));
        beanIconBox = text("豆", 12f, UiGraphics.GREEN, true);
        beanIconBox.setGravity(Gravity.CENTER);
        beanIconBox.setBackground(roundBg(0xffedf5f2, dp(9), 0, 0));
        LinearLayout.LayoutParams biLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        biLp.rightMargin = dp(10);
        beanRow.addView(beanIconBox, biLp);

        LinearLayout bmMid = vBox();
        beanMiniTitle = text("录音豆存储", 13f, UiGraphics.INK, true);
        beanMiniSub = text("点击查看豆内列表或手动核验清豆", 11f, UiGraphics.MUTED, false);
        bmMid.addView(beanMiniTitle);
        bmMid.addView(beanMiniSub);
        beanRow.addView(bmMid, new LinearLayout.LayoutParams(0, -2, 1f));
        beanRow.addView(new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.CHEVRON, UiGraphics.SLATE_LIGHT),
                new LinearLayout.LayoutParams(dp(15), dp(15)));
        beanRow.setOnClickListener(v -> showBeanFilesSheet());
        col.addView(beanRow, new LinearLayout.LayoutParams(-1, -2));

        return scroll;
    }

    private View stepLine() {
        View line = new View(this);
        line.setBackgroundColor(0xffcbd5e1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(1.5f));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        line.setLayoutParams(lp);
        return line;
    }

    private LinearLayout buildMetricPill(UiGraphics.VectorIconView icon, TextView valView, TextView lblView) {
        LinearLayout box = vBox();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6), dp(7), dp(6), dp(7));
        box.setBackground(roundBg(0xfff5f8f6, dp(12), dp(1), 0x16176b5b));
        LinearLayout top = hBox();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iLp.rightMargin = dp(4);
        top.addView(icon, iLp);
        valView.setSingleLine(true);
        top.addView(valView);
        box.addView(top);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-2, -2);
        lLp.topMargin = dp(2);
        lblView.setSingleLine(true);
        box.addView(lblView, lLp);
        return box;
    }

    // -----------------------------------------------------------------------
    // Tab 1: Dedicated Phone Copies Page + Minimalist Right Trash Icon
    // -----------------------------------------------------------------------

    private View buildCopiesTab() {
        LinearLayout root = vBox();
        root.setPadding(dp(16), dp(4), dp(16), 0);

        LinearLayout filterBar = hBox();
        filterBar.setGravity(Gravity.CENTER_VERTICAL);
        filterBar.setPadding(dp(2), dp(2), dp(2), dp(8));
        copiesSummaryText = text("共 0 条 · 0.00 MB", 12f, 0xff52665e, true);
        filterBar.addView(copiesSummaryText, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout seg = hBox();
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(roundBg(0xffe5ebe7, dp(10), 0, 0));
        segAll = buildSegBtn("全部", 0);
        segActive = buildSegBtn("进行中", 1);
        segLocal = buildSegBtn("仅本地", 2);
        seg.addView(segAll);
        seg.addView(segActive);
        seg.addView(segLocal);
        filterBar.addView(seg);
        root.addView(filterBar, new LinearLayout.LayoutParams(-1, -2));
        styleSegments();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        copiesContainer = vBox();
        copiesContainer.setPadding(0, dp(2), 0, dp(18));
        scroll.addView(copiesContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private TextView buildSegBtn(String title, int idx) {
        TextView t = text(title, 11f, 0xff556962, true);
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        t.setOnClickListener(v -> {
            copyFilter = idx;
            renderedCopiesKey = "";
            styleSegments();
            renderAll();
        });
        return t;
    }

    private void styleSegments() {
        TextView[] items = {segAll, segActive, segLocal};
        for (int i = 0; i < items.length; i++) {
            boolean on = (copyFilter == i);
            items[i].setTextColor(on ? UiGraphics.GREEN : 0xff556962);
            items[i].setBackground(on ? roundBg(Color.WHITE, dp(7), 0, 0) : null);
        }
    }

    private LinearLayout buildDeleteConfirmStrip() {
        LinearLayout bar = hBox();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(13), dp(10), dp(11), dp(10));
        bar.setBackground(roundBg(0xff1d2d28, dp(16), 0, 0));
        bar.setElevation(dp(4));
        bar.setVisibility(View.GONE);

        LinearLayout txtCol = vBox();
        delConfirmTitle = text("删除这条手机副本？", 12f, Color.WHITE, true);
        delConfirmSub = text("✓ 云端与豆内副本各自独立保留", 10.5f, 0xffa7f3d0, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(2);
        txtCol.addView(delConfirmTitle);
        txtCol.addView(delConfirmSub, subLp);
        bar.addView(txtCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView cancelBtn = text("取消", 11.5f, Color.WHITE, true);
        cancelBtn.setPadding(dp(11), dp(7), dp(11), dp(7));
        cancelBtn.setBackground(roundBg(0x28ffffff, dp(8), 0, 0));
        cancelBtn.setClickable(true);
        cancelBtn.setOnClickListener(v -> hideDeleteConfirm());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
        cLp.leftMargin = dp(8);
        cLp.rightMargin = dp(6);
        bar.addView(cancelBtn, cLp);

        TextView delBtn = text("删除手机副本", 11.5f, Color.WHITE, true);
        delBtn.setPadding(dp(11), dp(7), dp(11), dp(7));
        delBtn.setBackground(roundBg(0xffef4444, dp(8), 0, 0));
        delBtn.setClickable(true);
        delBtn.setOnClickListener(v -> executePendingLocalDelete());
        bar.addView(delBtn);

        return bar;
    }

    private void promptLocalDelete(RecordingStore.Row row) {
        pendingDeleteRow = row;
        String shortMeta = shortTime(row.recordedAt) + " · " + ConnectionHud.formatMb(row.bytes);
        delConfirmTitle.setText("删除这条手机副本？ (" + shortMeta + ")");
        if (row.state.equals("SENT")) {
            delConfirmSub.setText("✓ 中转云端与豆内状态各自独立保留");
            delConfirmSub.setTextColor(0xffa7f3d0);
        } else {
            delConfirmSub.setText("尚未上云；停止此条任务并删除本地文件");
            delConfirmSub.setTextColor(0xfffde68a);
        }
        deleteConfirmStrip.setVisibility(View.VISIBLE);
        refreshDeleteHighlight();
    }

    private void hideDeleteConfirm() {
        pendingDeleteRow = null;
        deleteConfirmStrip.setVisibility(View.GONE);
        refreshDeleteHighlight();
    }

    private void refreshDeleteHighlight() {
        String targetId = pendingDeleteRow == null ? "" : pendingDeleteRow.id;
        for (CopyRowHolder h : rowHolders.values()) {
            boolean focused = h.row.id.equals(targetId);
            h.delBtn.setBackground(roundBg(
                    focused ? UiGraphics.DANGER_SOFT : 0xfff8fafc,
                    dp(9), dp(1),
                    focused ? UiGraphics.DANGER_BORDER : 0xffe2e8f0));
            h.delIcon.setIcon(UiGraphics.VectorIconView.Kind.TRASH,
                    focused ? UiGraphics.DANGER : UiGraphics.SLATE_LIGHT);
        }
    }

    private void executePendingLocalDelete() {
        RecordingStore.Row row = pendingDeleteRow;
        hideDeleteConfirm();
        if (row == null) return;
        if (row.id.equals(playingRowId)) releasePlayer();
        exportFile = null;
        LocalRecordingDeletion.delete(getApplicationContext(), row.id, message -> main.post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                toast(message);
                renderedCopiesKey = "";
                renderAll();
            }
        }));
    }

    // -----------------------------------------------------------------------
    // Tab 2: Settings, Maintenance & Color-Coded Event Logs
    // -----------------------------------------------------------------------

    private View buildSettingsTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = vBox();
        col.setPadding(dp(16), dp(6), dp(16), dp(14));
        scroll.addView(col);

        col.addView(sectionHeader("同步与后台策略", 0, 6));

        LinearLayout group = vBox();
        group.setPadding(dp(14), dp(4), dp(14), dp(4));
        group.setBackground(roundBg(Color.WHITE, dp(18), dp(1), 0x10172f29));

        // Row 1: Relay server
        LinearLayout relayRow = hBox();
        relayRow.setGravity(Gravity.CENTER_VERTICAL);
        relayRow.setPadding(0, dp(10), 0, dp(10));
        LinearLayout relayLeft = vBox();
        relayLeft.addView(text("Daybook 中转服务", 13.5f, UiGraphics.INK, true));
        relaySubText = text("尚未配置 · 录音仅保存在手机", 11.2f, UiGraphics.MUTED, false);
        relaySubText.setSingleLine(true);
        relaySubText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        relayLeft.addView(relaySubText);
        relayRow.addView(relayLeft, new LinearLayout.LayoutParams(0, -2, 1f));
        relayBadgeText = text("去配置 ▸", 11f, UiGraphics.GREEN, true);
        relayBadgeText.setPadding(dp(8), dp(4), dp(8), dp(4));
        relayBadgeText.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(7), 0, 0));
        relayRow.addView(relayBadgeText);
        relayRow.setOnClickListener(v -> configureUpload());
        group.addView(relayRow);
        group.addView(rowDivider());

        // Row 2: Auto sync on connect
        syncOnConnectSwitch = new UiGraphics.SwitchRowCheckBox(
                this, "连接后自动同步遗留录音", "自动补收 → 上传 → 核验清豆");
        syncOnConnectSwitch.setChecked(prefs().getBoolean("sync_on_connect", true));
        syncOnConnectSwitch.setOnCheckedChangeListener((v, checked) -> {
            prefs().edit().putBoolean("sync_on_connect", checked).commit();
            if (isRunning()) serviceAction(RecorderService.SYNC_CHANGED);
            renderAll();
        });
        group.addView(syncOnConnectSwitch, new LinearLayout.LayoutParams(-1, -2));
        group.addView(rowDivider());

        // Row 3: Continuous auto cleanup
        autoCleanupSwitch = new UiGraphics.SwitchRowCheckBox(
                this, "核验中转后自动清理豆内副本", "双端 SHA256 一致后自动删豆");
        autoCleanupSwitch.setChecked(prefs().getBoolean("auto_cleanup", false));
        autoCleanupSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (changingCleanup) return;
            if (!checked) {
                prefs().edit().putBoolean("auto_cleanup", false).commit();
                if (isRunning()) serviceAction(RecorderService.CLEANUP_CHANGED);
                return;
            }
            changingCleanup = true;
            autoCleanupSwitch.setChecked(false);
            changingCleanup = false;
            new AlertDialog.Builder(this)
                    .setTitle("开启自动清理豆内副本？")
                    .setMessage("开启后，当前及今后上传成功的录音会在重新核对中转回执、SHA256 与豆内列表一致后自动清理豆内副本。手机与中转音频保留。")
                    .setPositiveButton("开启", (d, w) -> {
                        prefs().edit().putBoolean("auto_cleanup", true).commit();
                        changingCleanup = true;
                        autoCleanupSwitch.setChecked(true);
                        changingCleanup = false;
                        if (isRunning()) serviceAction(RecorderService.CLEANUP_CHANGED);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        group.addView(autoCleanupSwitch, new LinearLayout.LayoutParams(-1, -2));
        group.addView(rowDivider());

        // Row 4: Battery unrestricted
        LinearLayout batRow = hBox();
        batRow.setGravity(Gravity.CENTER_VERTICAL);
        batRow.setPadding(0, dp(10), 0, dp(10));
        LinearLayout batLeft = vBox();
        batLeft.addView(text("电池不受限制", 13.5f, UiGraphics.INK, true));
        batterySubText = text("保障锁屏后台持续收音", 11.2f, UiGraphics.MUTED, false);
        batLeft.addView(batterySubText);
        batRow.addView(batLeft, new LinearLayout.LayoutParams(0, -2, 1f));
        batteryBadgeText = text("✓ 已豁免", 11f, UiGraphics.GREEN, true);
        batteryBadgeText.setPadding(dp(8), dp(4), dp(8), dp(4));
        batteryBadgeText.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(7), 0, 0));
        batRow.addView(batteryBadgeText);
        batRow.setOnClickListener(v -> requestBatteryExemption());
        group.addView(batRow);
        group.addView(rowDivider());

        // Row 5: Restore on boot
        bootRestoreSwitch = new UiGraphics.SwitchRowCheckBox(
                this, "手机重启后恢复已开启的连接", "开机解锁后自动拉起守护服务");
        bootRestoreSwitch.setChecked(prefs().getBoolean("boot", false));
        bootRestoreSwitch.setOnCheckedChangeListener((v, b) -> prefs().edit().putBoolean("boot", b).apply());
        group.addView(bootRestoreSwitch, new LinearLayout.LayoutParams(-1, -2));

        col.addView(group, new LinearLayout.LayoutParams(-1, -2));

        // Maintenance 2x2 Grid
        col.addView(sectionHeader("设备维护", 12, 6));
        LinearLayout gridRow1 = hBox();
        maintBeanSub = text("查看快照 · 手动核验清豆", 10.5f, UiGraphics.MUTED, false);
        LinearLayout tileBean = buildMaintTile("豆内文件管理", maintBeanSub, null, this::showBeanFilesSheet);
        maintBindSub = text("发送绑定指令", 10.5f, UiGraphics.MUTED, false);
        LinearLayout tileBind = buildMaintTile("重新绑定设备", maintBindSub, null, () ->
                new AlertDialog.Builder(this)
                        .setTitle("设备绑定")
                        .setMessage("向当前连接的录音豆发送绑定请求。如设备提示，请按实体键确认。")
                        .setPositiveButton("发送请求", (d, w) -> serviceAction(RecorderService.BIND))
                        .setNegativeButton("取消", null)
                        .show());
        LinearLayout.LayoutParams t1Lp = new LinearLayout.LayoutParams(0, -2, 1f);
        t1Lp.rightMargin = dp(8);
        gridRow1.addView(tileBean, t1Lp);
        gridRow1.addView(tileBind, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(gridRow1, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout gridRow2 = hBox();
        LinearLayout.LayoutParams gr2Lp = new LinearLayout.LayoutParams(-1, -2);
        gr2Lp.topMargin = dp(8);
        LinearLayout tileRetry = buildMaintTile(
                "检查队列与重试",
                text("刷新豆内并重试上传", 10.5f, UiGraphics.MUTED, false),
                null,
                this::triggerRefreshAndRetry);
        maintHistoryBadge = text("", 10f, UiGraphics.MUTED, true);
        LinearLayout tileHistory = buildMaintTile(
                "补收绑定前旧录音",
                text("含首次连接前历史条目", 10.5f, UiGraphics.MUTED, false),
                maintHistoryBadge,
                () -> {
                    boolean next = !prefs().getBoolean("history", false);
                    prefs().edit().putBoolean("history", next).apply();
                    if (isRunning()) serviceAction(RecorderService.REFRESH);
                    renderAll();
                });
        LinearLayout.LayoutParams t3Lp = new LinearLayout.LayoutParams(0, -2, 1f);
        t3Lp.rightMargin = dp(8);
        gridRow2.addView(tileRetry, t3Lp);
        gridRow2.addView(tileHistory, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(gridRow2, gr2Lp);

        // Dark Terminal Event Log Card
        LinearLayout logCard = vBox();
        logCard.setPadding(dp(13), dp(12), dp(13), dp(12));
        logCard.setBackground(roundBg(0xff172622, dp(18), 0, 0));
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(-1, -2);
        lcLp.topMargin = dp(12);

        LinearLayout logHead = hBox();
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(text("设备运行日志", 12.5f, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1f));

        TextView copyDiagBtn = text("复制诊断", 10.5f, 0xffd1fae5, true);
        copyDiagBtn.setPadding(dp(9), dp(4), dp(9), dp(4));
        copyDiagBtn.setBackground(roundBg(0x22ffffff, dp(7), 0, 0));
        copyDiagBtn.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(
                    ClipData.newPlainText("Daybook 录音接收诊断", diagnosticText()));
            toast("诊断已复制");
        });
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(-2, -2);
        cdLp.rightMargin = dp(6);
        logHead.addView(copyDiagBtn, cdLp);

        TextView exportDiagBtn = text("导出 .txt", 10.5f, 0xffd1fae5, true);
        exportDiagBtn.setPadding(dp(9), dp(4), dp(9), dp(4));
        exportDiagBtn.setBackground(roundBg(0x22ffffff, dp(7), 0, 0));
        exportDiagBtn.setOnClickListener(v -> {
            exportFile = null;
            exportText = diagnosticText();
            createDocument("text/plain", "daybook-recorder-diagnostics.txt");
        });
        logHead.addView(exportDiagBtn);
        logCard.addView(logHead, new LinearLayout.LayoutParams(-1, -2));

        logLinesContainer = vBox();
        LinearLayout.LayoutParams llLp = new LinearLayout.LayoutParams(-1, -2);
        llLp.topMargin = dp(8);
        logCard.addView(logLinesContainer, llLp);
        col.addView(logCard, lcLp);

        return scroll;
    }

    private LinearLayout buildMaintTile(String title, TextView subView, TextView rightBadge, Runnable onClick) {
        LinearLayout tile = vBox();
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        tile.setBackground(roundBg(Color.WHITE, dp(14), dp(1), 0x10172f29));
        LinearLayout top = hBox();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(title, 12.5f, UiGraphics.INK, true), new LinearLayout.LayoutParams(0, -2, 1f));
        if (rightBadge != null) {
            top.addView(rightBadge);
        } else {
            top.addView(new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.CHEVRON, UiGraphics.SLATE_LIGHT),
                    new LinearLayout.LayoutParams(dp(14), dp(14)));
        }
        tile.addView(top);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.topMargin = dp(3);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(subView, sLp);
        tile.setOnClickListener(v -> onClick.run());
        return tile;
    }

    // -----------------------------------------------------------------------
    // Render Engine (Fast, Differential, Flicker-Free)
    // -----------------------------------------------------------------------

    private void renderAll() {
        if (isFinishing() || isDestroyed()) return;
        SharedPreferences p = prefs();
        boolean running = isRunning();
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter bt = bm == null ? null : bm.getAdapter();
        boolean btOn = bt != null && bt.isEnabled();
        String address = p.getString("address", "");
        String deviceId = Settings.activeDeviceId(this);
        boolean bleReady = running && p.getBoolean("ble_ready", false)
                && !deviceId.isEmpty()
                && deviceId.equals(p.getString("ble_device", ""))
                && System.currentTimeMillis() - p.getLong("heartbeat", 0L) < 45_000L;

        long lastStableMs = Math.max(RuntimeTelemetry.lastStableDurationMs, p.getLong("last_stable_ms", 0L));
        ConnectionHud.Mode mode = ConnectionHud.classify(
                running, btOn, !address.isEmpty(), bleReady, RuntimeTelemetry.reconnectCount, lastStableMs);

        List<RecordingStore.Row> allRows = store.copiesRows();
        String endpoint = p.getString("endpoint", "");
        DeviceFilesStore.Snapshot snap = deviceFiles.read(deviceId);
        List<RecordingStore.Row> pendingList = store.pendingRows(endpoint);
        int activeCount = 0;
        RecordingStore.Row oldestActionable = null;
        for (RecordingStore.Row r : pendingList) {
            if (isActionableRow(r, deviceId, endpoint, snap)) {
                activeCount++;
                if (oldestActionable == null) oldestActionable = r;
            }
        }
        if (!RuntimeTelemetry.cleaningRecordingId.isEmpty() && activeCount == 0) activeCount = 1;
        if (activeCount > 0) {
            navCopiesBadge.setText(String.valueOf(activeCount));
            navCopiesBadge.setVisibility(View.VISIBLE);
        } else {
            navCopiesBadge.setVisibility(View.GONE);
        }

        renderTopStatusPill(mode);
        renderConnectionTab(mode, p, address, deviceId, btOn, lastStableMs, allRows, activeCount, oldestActionable, snap);
        renderCopiesTab(allRows, deviceId, endpoint, snap);
        renderSettingsTab(p, deviceId);
        if (beanSheetRefresh != null && beanSheetDialog != null && beanSheetDialog.isShowing()) {
            beanSheetRefresh.run();
        }
    }

    private static boolean isActionableRow(RecordingStore.Row r, String activeDeviceId,
                                           String endpoint, DeviceFilesStore.Snapshot snap) {
        if (r == null) return false;
        if (r.state.equals("RECEIVING")) return true;
        if (r.state.equals("READY")) return !endpoint.isEmpty();
        if (!r.state.equals("WAITING")) return false;
        if (activeDeviceId.isEmpty() || !r.device.equals(activeDeviceId)) return false;
        if (snap != null && snap.fetchedAt > 0L && !snap.entries.containsKey(r.fileId)
                && r.recordedAt < snap.fetchedAt - 5000L) {
            return false;
        }
        return true;
    }

    private void renderTopStatusPill(ConnectionHud.Mode mode) {
        int bg, fg, dot;
        String label;
        switch (mode) {
            case CONNECTING:
                bg = UiGraphics.BLUE_SOFT; fg = UiGraphics.BLUE_DARK; dot = UiGraphics.BLUE;
                label = "建立连接中";
                break;
            case STABLE:
                bg = 0xffdff0ec; fg = UiGraphics.GREEN; dot = UiGraphics.GREEN_BRIGHT;
                label = RuntimeTelemetry.beanRecording ? "实体键录音中" : "后台守护中";
                break;
            case RECONNECTING:
                bg = UiGraphics.AMBER_SOFT; fg = UiGraphics.AMBER_DARK; dot = UiGraphics.AMBER;
                label = "自动重连中";
                break;
            default:
                bg = 0xffe2e8f0; fg = UiGraphics.SLATE; dot = UiGraphics.SLATE_LIGHT;
                label = "已暂停";
                break;
        }
        topServicePill.setBackground(roundBg(bg, dp(99), 0, 0));
        topServiceDot.setBackground(roundBg(dot, dp(99), 0, 0));
        topServiceText.setTextColor(fg);
        topServiceText.setText(label);
    }

    private void renderConnectionTab(ConnectionHud.Mode mode, SharedPreferences p, String address,
                                     String deviceId, boolean btOn, long lastStableMs,
                                     List<RecordingStore.Row> allRows, int activeCount,
                                     RecordingStore.Row oldestActionable,
                                     DeviceFilesStore.Snapshot snap) {
        String name = p.getString("name", "Soundcore Work");
        String fw = p.getString("firmware", "");
        int battery = p.getInt("battery", -1);

        heroDeviceChip.setText(address.isEmpty() ? "尚未选择录音豆"
                : name + (fw.isEmpty() ? "" : " · v" + fw));

        if (mode == ConnectionHud.Mode.STABLE) {
            heroActionBtn.setText("暂停连接");
            heroActionBtn.setTextColor(UiGraphics.GREEN);
            heroActionBtn.setBackground(roundBg(0xffedf5f2, dp(99), 0, 0));
        } else if (mode == ConnectionHud.Mode.RECONNECTING) {
            heroActionBtn.setText("停止重连");
            heroActionBtn.setTextColor(UiGraphics.AMBER_DARK);
            heroActionBtn.setBackground(roundBg(UiGraphics.AMBER_SOFT, dp(99), 0, 0));
        } else if (mode == ConnectionHud.Mode.CONNECTING) {
            heroActionBtn.setText("取消连接");
            heroActionBtn.setTextColor(UiGraphics.BLUE_DARK);
            heroActionBtn.setBackground(roundBg(UiGraphics.BLUE_SOFT, dp(99), 0, 0));
        } else {
            heroActionBtn.setText(address.isEmpty() ? "选择设备" : "重选设备");
            heroActionBtn.setTextColor(UiGraphics.SLATE);
            heroActionBtn.setBackground(roundBg(UiGraphics.SLATE_SOFT, dp(99), 0, 0));
        }

        beanHeroView.setState(mode, mode == ConnectionHud.Mode.STABLE && RuntimeTelemetry.beanRecording);
        connectingStepper.setVisibility(mode == ConnectionHud.Mode.CONNECTING ? View.VISIBLE : View.GONE);
        stableMetricsRow.setVisibility(mode == ConnectionHud.Mode.STABLE ? View.VISIBLE : View.GONE);
        disconnectedBtnRow.setVisibility(mode == ConnectionHud.Mode.DISCONNECTED ? View.VISIBLE : View.GONE);
        reconnectingBox.setVisibility(mode == ConnectionHud.Mode.RECONNECTING ? View.VISIBLE : View.GONE);

        if (mode == ConnectionHud.Mode.CONNECTING) {
            heroStateTitle.setText("正在建立连接…");
            heroStateTitle.setTextColor(UiGraphics.BLUE_DARK);
            heroStateSub.setText("启动自检 · 协商本地 ECDH 加密通道");
            int step = Math.max(1, RuntimeTelemetry.handshakeStep);
            styleStep(stepBle, "蓝牙就绪", step > 1 ? 2 : 1);
            styleStep(stepKey, "密钥握手", step > 2 ? 2 : step == 2 ? 1 : 0);
            styleStep(stepSync, "同步豆内", step >= 3 ? 1 : 0);
        } else if (mode == ConnectionHud.Mode.STABLE) {
            if (RuntimeTelemetry.beanPaused) {
                heroStateTitle.setText("录音已暂停");
                heroStateSub.setText("继续或结束录音后自动保存原音");
            } else if (RuntimeTelemetry.beanRecording) {
                heroStateTitle.setText("正在录音 · 实时收音");
                heroStateSub.setText("再次按下录音豆实体键结束录制");
            } else {
                heroStateTitle.setText("已稳定连接");
                heroStateSub.setText("按实体键开始录音 · 再按结束自动同步");
            }
            heroStateTitle.setTextColor(UiGraphics.GREEN);
            metricBatIcon.setLevel(battery);
            metricBatVal.setText(battery >= 0 ? battery + "%" : "在线");
            int rssi = RuntimeTelemetry.rssiDbm;
            metricSigIcon.setLevel(ConnectionHud.signalBars(rssi));
            metricSigVal.setText(rssi != Integer.MIN_VALUE && rssi < 0 ? rssi + "dBm" : "极佳");
            metricSigLbl.setText(ConnectionHud.signalQuality(rssi));
            long since = RuntimeTelemetry.connectedSinceWallMs;
            long span = since > 0L ? System.currentTimeMillis() - since : 1000L;
            metricSpanVal.setText(ConnectionHud.formatStableSpan(span));
        } else if (mode == ConnectionHud.Mode.RECONNECTING) {
            heroStateTitle.setText("信号暂离 · 重试建立连接");
            heroStateTitle.setTextColor(UiGraphics.AMBER_DARK);
            if (lastStableMs > 0L) {
                heroStateSub.setText("此前已稳定连接 " + ConnectionHud.formatStableSpan(lastStableMs) + " · 靠近后自动恢复");
            } else {
                heroStateSub.setText("蓝牙链路中断 · 后台正在自动重试恢复");
            }
            int attemptNo = Math.max(1, RuntimeTelemetry.reconnectCount);
            long remainMs = Math.max(0L, RuntimeTelemetry.nextReconnectWallMs - System.currentTimeMillis());
            long totalDelay = Math.max(2000L, RuntimeTelemetry.reconnectDelayMs);
            long remainSec = (remainMs + 999L) / 1000L;
            if (RuntimeTelemetry.backgroundScanning) {
                rcHeadline.setText("正在自动扫描录音豆新广播…");
            } else {
                rcHeadline.setText(remainMs > 300L
                        ? String.format(Locale.ROOT, "第 %d 次退避重试 · %02ds 后发起", attemptNo, remainSec)
                        : String.format(Locale.ROOT, "第 %d 次退避重试 · 正在重连…", attemptNo));
            }
            float ratio = 1f - Math.min(1f, remainMs / (float) totalDelay);
            rcProgressBar.setProgress(ratio, UiGraphics.AMBER_BORDER, UiGraphics.AMBER, 0xfff59e0b);
            rcSubLeft.setText(battery >= 0 ? "上次电量 " + battery + "%" : "等待信号恢复");
        } else {
            heroStateTitle.setText("连接已断开");
            heroStateTitle.setTextColor(UiGraphics.SLATE);
            long lastOnline = p.getLong("last_online_at", 0L);
            if (address.isEmpty()) {
                heroStateSub.setText("首次使用请点击扫描并选择你的安克录音豆");
            } else if (!btOn) {
                heroStateSub.setText("手机蓝牙已关闭 · 开启蓝牙后自动恢复");
            } else if (lastOnline > 0L) {
                heroStateSub.setText("上次在线 " + shortClock(lastOnline) + " · 后台接收未开启");
            } else {
                heroStateSub.setText("开启连接守护后，锁屏或退出本页均保持收音");
            }
        }

        // Render Live Transfer Card vs Idle Card
        RuntimeTelemetry.Transfer liveUp = RuntimeTelemetry.httpUpload;
        RuntimeTelemetry.Transfer liveBle = RuntimeTelemetry.bleTransfer;
        RecordingStore.Row activeRow = findFeaturedTransferRow(allRows, liveUp, liveBle, oldestActionable);

        if (liveUp != null || liveBle != null || activeRow != null) {
            activeTransferCard.setVisibility(View.VISIBLE);
            idleSummaryCard.setVisibility(View.GONE);
            bindFeaturedTransferCard(mode, activeCount, allRows.size(), liveUp, liveBle, activeRow);
        } else {
            activeTransferCard.setVisibility(View.GONE);
            idleSummaryCard.setVisibility(View.VISIBLE);
            transferSecTitle.setText("同步状态");
            transferSecBadge.setText("✓ 全部已备份");
            transferSecBadge.setTextColor(UiGraphics.GREEN);
            transferSecBadge.setBackground(null);

            RecordingStore.Row latestSaved = null;
            for (RecordingStore.Row r : allRows) {
                if (r.saved()) { latestSaved = r; break; }
            }
            if (latestSaved == null) {
                idleTitleText.setText(mode == ConnectionHud.Mode.CONNECTING ? "连接后自动检查豆内录音" : "尚无录音记录");
                idleSubText.setText("无需停留在当前页面，按下录音豆按钮即可录制");
                idleBadgeText.setText("就绪");
            } else {
                idleTitleText.setText("最近同步 · " + shortTime(latestSaved.recordedAt));
                idleSubText.setText("时长 " + ConnectionHud.formatDurationClock(latestSaved.duration)
                        + " · " + ConnectionHud.formatMb(latestSaved.bytes)
                        + (latestSaved.cleanupState.equals("DELETED") ? " · 豆内已清理" : " · 已妥善保存"));
                idleBadgeText.setText("0 待办");
            }
        }

        // Bean storage mini row
        if (snap.fetchedAt == 0L) {
            beanMiniTitle.setText("录音豆存储");
            beanMiniSub.setText(mode == ConnectionHud.Mode.STABLE ? "正在读取豆内文件列表…" : "连接就绪后自动读取列表");
            beanIconBox.setBackground(roundBg(0xffedf5f2, dp(9), 0, 0));
            beanIconBox.setTextColor(UiGraphics.GREEN);
        } else if (snap.entries.isEmpty()) {
            beanMiniTitle.setText((mode == ConnectionHud.Mode.STABLE ? "录音豆存储 · " : "上次快照 · ") + "已清空 (0 条)");
            beanMiniSub.setText(shortClock(snap.fetchedAt) + " 读取 · 点击查看或刷新");
            beanIconBox.setBackground(roundBg(mode == ConnectionHud.Mode.STABLE ? 0xffedf5f2 : UiGraphics.SLATE_SOFT, dp(9), 0, 0));
            beanIconBox.setTextColor(mode == ConnectionHud.Mode.STABLE ? UiGraphics.GREEN : UiGraphics.SLATE_MUTED);
        } else {
            long estBytes = estimateBeanTotalBytes(snap);
            beanMiniTitle.setText("豆内文件 · " + snap.entries.size() + " 条 (" + ConnectionHud.formatMb(estBytes) + ")");
            beanMiniSub.setText(mode == ConnectionHud.Mode.STABLE ? "点击查看快照、下载或人工清豆" : "离线快照 · 重连后自动核对");
            beanIconBox.setBackground(roundBg(mode == ConnectionHud.Mode.RECONNECTING ? UiGraphics.AMBER_SOFT : 0xffedf5f2, dp(9), 0, 0));
            beanIconBox.setTextColor(mode == ConnectionHud.Mode.RECONNECTING ? UiGraphics.AMBER_DARK : UiGraphics.GREEN);
        }
    }

    private void styleStep(TextView tv, String label, int state) {
        if (state == 2) {
            tv.setText("✓ " + label);
            tv.setTextColor(UiGraphics.GREEN);
        } else if (state == 1) {
            tv.setText("● " + label);
            tv.setTextColor(UiGraphics.BLUE);
        } else {
            tv.setText("○ " + label);
            tv.setTextColor(UiGraphics.SLATE_LIGHT);
        }
    }

    private RecordingStore.Row findFeaturedTransferRow(List<RecordingStore.Row> rows,
                                                       RuntimeTelemetry.Transfer up,
                                                       RuntimeTelemetry.Transfer ble,
                                                       RecordingStore.Row oldestActionable) {
        if (up != null) {
            for (RecordingStore.Row r : rows) if (r.id.equals(up.recordingId)) return r;
            return store.get(up.recordingId);
        }
        if (ble != null) {
            for (RecordingStore.Row r : rows) if (r.id.equals(ble.recordingId)) return r;
            return store.get(ble.recordingId);
        }
        String cleaningId = RuntimeTelemetry.cleaningRecordingId;
        if (!cleaningId.isEmpty()) {
            for (RecordingStore.Row r : rows) if (r.id.equals(cleaningId)) return r;
            RecordingStore.Row cleaningRow = store.get(cleaningId);
            if (cleaningRow != null) return cleaningRow;
        }
        return oldestActionable;
    }

    private void bindFeaturedTransferCard(ConnectionHud.Mode mode, int activeCount, int totalCount,
                                          RuntimeTelemetry.Transfer up, RuntimeTelemetry.Transfer ble,
                                          RecordingStore.Row row) {
        boolean isReconnectWait = (mode == ConnectionHud.Mode.RECONNECTING && up == null && ble == null);
        boolean isCleaningBean = (up == null && ble == null && row != null
                && row.id.equals(RuntimeTelemetry.cleaningRecordingId));
        transferSecTitle.setText(isReconnectWait ? "断点续收队列" : "单条闭环同步");
        transferSecBadge.setText(Math.max(1, activeCount) + " 条待同步");
        transferSecBadge.setTextColor(isReconnectWait ? UiGraphics.AMBER_DARK : UiGraphics.GREEN);
        transferSecBadge.setBackground(roundBg(isReconnectWait ? UiGraphics.AMBER_SOFT : UiGraphics.GREEN_SOFT, dp(99), 0, 0));

        long fileId = up != null ? up.fileId : ble != null ? ble.fileId : row.fileId;
        long recAt = row != null ? row.recordedAt : (fileId >= 946684800L ? fileId * 1000L : System.currentTimeMillis());
        tfTimeText.setText(shortTime(recAt) + " 录音");

        long curBytes, totBytes, durMs;
        double speed = 0.0;
        int accent, softBg;
        UiGraphics.VectorIconView.Kind stageKind;
        String stageLabel;

        if (up != null) {
            curBytes = up.currentBytes;
            totBytes = up.totalBytes;
            durMs = up.durationMs;
            speed = up.bytesPerSec;
            accent = UiGraphics.GREEN;
            softBg = UiGraphics.GREEN_SOFT;
            stageKind = UiGraphics.VectorIconView.Kind.UPLOAD;
            stageLabel = up.cleanupVerify ? "核验云端副本" : "上传至中转";
        } else if (ble != null) {
            curBytes = ble.currentBytes;
            totBytes = ble.totalBytes;
            durMs = ble.durationMs;
            speed = ble.bytesPerSec;
            accent = UiGraphics.BLUE_DARK;
            softBg = UiGraphics.BLUE_SOFT;
            stageKind = UiGraphics.VectorIconView.Kind.DOWNLOAD;
            stageLabel = ble.liveRecording ? "实时接收原音" : ble.resumed ? "断点续收原音" : "从豆内接收";
        } else if (isCleaningBean) {
            curBytes = row.bytes;
            totBytes = row.bytes;
            durMs = row.duration;
            accent = UiGraphics.GREEN;
            softBg = UiGraphics.GREEN_SOFT;
            stageKind = UiGraphics.VectorIconView.Kind.CLOUD_OK;
            stageLabel = "核验清理豆内";
        } else if (row != null && row.state.equals("READY")) {
            curBytes = 0L;
            totBytes = row.bytes;
            durMs = row.duration;
            accent = UiGraphics.GREEN;
            softBg = UiGraphics.GREEN_SOFT;
            stageKind = UiGraphics.VectorIconView.Kind.UPLOAD;
            stageLabel = row.attempts > 0 ? "等待重试上传" : "排队待上传";
        } else {
            curBytes = row == null ? 0L : row.bytes;
            totBytes = estimateExpectedAudioBytes(row);
            durMs = estimateExpectedDurationMs(row);
            accent = UiGraphics.AMBER_DARK;
            softBg = UiGraphics.AMBER_SOFT;
            stageKind = UiGraphics.VectorIconView.Kind.CLOCK;
            stageLabel = mode == ConnectionHud.Mode.RECONNECTING
                    ? (curBytes > 0L ? "待重连断点续传" : "待重连补收")
                    : (curBytes > 0L ? "等待断点续传" : "等待补收");
        }

        boolean isLiveStream = (ble != null && ble.liveRecording && totBytes <= 0L);
        int pct = totBytes > 0L ? ConnectionHud.percent(curBytes, totBytes) : 0;
        float ratio = totBytes > 0L ? Math.min(1f, (float) curBytes / totBytes) : (isLiveStream ? 0.5f : 0f);

        activeTransferCard.setBackground(roundBg(Color.WHITE, dp(20), dp(1),
                accent == UiGraphics.AMBER_DARK ? UiGraphics.AMBER_BORDER : 0x28176b5b));
        tfStagePill.setBackground(roundBg(softBg, dp(99), 0, 0));
        tfStageIcon.setIcon(stageKind, accent);
        tfStageText.setTextColor(accent);
        tfStageText.setText(stageLabel);

        tfAudioPill.setBackground(roundBg(accent == UiGraphics.AMBER_DARK ? 0xfffffbeb : 0xfff4f8f6, dp(12), 0, 0));
        tfAudioIcon.setIcon(stageKind == UiGraphics.VectorIconView.Kind.UPLOAD
                ? UiGraphics.VectorIconView.Kind.PLAY : UiGraphics.VectorIconView.Kind.DOWNLOAD, accent);
        tfWaveform.bind(fileId, totBytes > 0L ? ratio : (curBytes > 0L ? 1f : 0f),
                accent == UiGraphics.AMBER_DARK ? UiGraphics.AMBER : accent, 0xffcbd5ce);
        tfDurationChip.setText(ConnectionHud.formatDurationClock(durMs));
        tfDurationChip.setTextColor(accent);
        tfDurationChip.setBackground(roundBg(softBg, dp(6), 0, 0));

        int barEnd = accent == UiGraphics.AMBER_DARK ? UiGraphics.AMBER
                : accent == UiGraphics.BLUE_DARK ? UiGraphics.BLUE : UiGraphics.GREEN_BRIGHT;
        tfProgressBar.setProgress(ratio, 0xffe6ece9, accent, barEnd);

        tfPercentText.setTextColor(accent);
        tfPercentText.setText(totBytes > 0L
                ? pct + "%" + (stageKind == UiGraphics.VectorIconView.Kind.CLOCK && curBytes > 0 ? " 已缓存" : "")
                : isLiveStream ? "实时接收" : curBytes > 0L ? "已缓存分片" : "0% 待同步");

        String speedText = ConnectionHud.formatSpeedMb(speed);
        String mbPair = ConnectionHud.formatMbPair(curBytes, totBytes);
        tfMbText.setText(mbPair + (!speedText.isEmpty() ? " · " + speedText
                : (mode == ConnectionHud.Mode.RECONNECTING ? " · 恢复即续收" : "")));
    }

    // -----------------------------------------------------------------------
    // Render Tab 1: Phone Copies
    // -----------------------------------------------------------------------

    private void renderCopiesTab(List<RecordingStore.Row> allRows, String deviceId,
                                 String endpoint, DeviceFilesStore.Snapshot snap) {
        long sumBytes = 0L;
        int inFlight = 0;
        List<RecordingStore.Row> filtered = new ArrayList<>();
        for (RecordingStore.Row r : allRows) {
            sumBytes += Math.max(0L, r.bytes);
            boolean active = isActionableRow(r, deviceId, endpoint, snap);
            if (active) inFlight++;
            boolean localOnly = r.state.equals("SERVER_DELETED")
                    || (r.state.equals("READY") && endpoint.isEmpty());
            if (copyFilter == 1 && !active) continue;
            if (copyFilter == 2 && !localOnly) continue;
            filtered.add(r);
        }
        copiesSummaryText.setText("共 " + allRows.size() + " 条 · " + ConnectionHud.formatMb(sumBytes));
        segActive.setText(inFlight > 0 ? "进行中 " + inFlight : "进行中");

        StringBuilder structKey = new StringBuilder().append(copyFilter).append('|');
        for (RecordingStore.Row r : filtered) {
            structKey.append(r.id).append(':').append(r.state).append(':')
                    .append(r.cleanupState).append(':').append(r.reconciled()).append(';');
        }

        if (!renderedCopiesKey.equals(structKey.toString())) {
            renderedCopiesKey = structKey.toString();
            copiesContainer.removeAllViews();
            rowHolders.clear();
            if (filtered.isEmpty()) {
                TextView empty = text(allRows.isEmpty()
                        ? "暂无手机录音副本\n连接录音豆后按实体键即可录制"
                        : "当前筛选条件下没有记录", 13.5f, UiGraphics.MUTED, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(16), dp(48), dp(16), dp(48));
                copiesContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            } else {
                for (RecordingStore.Row row : filtered) {
                    CopyRowHolder holder = createCopyRowHolder(row);
                    rowHolders.put(row.id, holder);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = dp(9);
                    copiesContainer.addView(holder.card, lp);
                }
                refreshDeleteHighlight();
            }
        }

        // Fast in-place update of dynamic values (live upload %, playback timer, bytes)
        RuntimeTelemetry.Transfer liveUp = RuntimeTelemetry.httpUpload;
        RuntimeTelemetry.Transfer liveBle = RuntimeTelemetry.bleTransfer;
        for (RecordingStore.Row row : filtered) {
            CopyRowHolder h = rowHolders.get(row.id);
            if (h != null) updateCopyRowDynamic(h, row, liveUp, liveBle, deviceId, endpoint, snap);
        }
    }

    private CopyRowHolder createCopyRowHolder(RecordingStore.Row row) {
        CopyRowHolder h = new CopyRowHolder();
        h.row = row;
        h.card = hBox();
        h.card.setGravity(Gravity.CENTER_VERTICAL);
        h.card.setPadding(dp(12), dp(11), dp(10), dp(11));
        h.card.setBackground(roundBg(Color.WHITE, dp(17), dp(1), 0x10172f29));

        // Left Play/Status Circle (Left-thumb hot zone)
        h.playCircle = new FrameLayout(this);
        h.playIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.PLAY, UiGraphics.GREEN);
        h.playCircle.addView(h.playIcon, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        pcLp.rightMargin = dp(10);
        h.playCircle.setOnClickListener(v -> {
            RecordingStore.Row latest = store.get(h.row.id);
            if (latest == null) latest = h.row;
            if (latest.id.equals(playingRowId)) {
                releasePlayer();
                renderAll();
            } else if (latest.saved()) {
                playLocal(store.file(latest.id, ".ogg"), latest.id);
            } else {
                recordingActions(latest);
            }
        });
        h.card.addView(h.playCircle, pcLp);

        // Middle Audio Track Content (Tap opens secondary actions: export Ogg/Opus, manual bean cleanup)
        LinearLayout mid = vBox();
        LinearLayout row1 = hBox();
        row1.setGravity(Gravity.CENTER_VERTICAL);
        h.timeText = text(shortTime(row.recordedAt), 13f, UiGraphics.INK, true);
        h.timeText.setSingleLine(true);
        row1.addView(h.timeText, new LinearLayout.LayoutParams(0, -2, 1f));

        h.badgeBox = hBox();
        h.badgeBox.setGravity(Gravity.CENTER_VERTICAL);
        h.badgeBox.setPadding(dp(6), dp(2), dp(6), dp(2));
        h.badgeIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.CLOUD_OK, UiGraphics.GREEN);
        LinearLayout.LayoutParams biLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        biLp.rightMargin = dp(3);
        h.badgeBox.addView(h.badgeIcon, biLp);
        h.badgeText = text("", 10.5f, UiGraphics.GREEN, true);
        h.badgeText.setSingleLine(true);
        h.badgeBox.addView(h.badgeText);
        row1.addView(h.badgeBox);
        mid.addView(row1, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout waveRow = hBox();
        waveRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wrLp = new LinearLayout.LayoutParams(-1, -2);
        wrLp.topMargin = dp(5);
        h.waveform = new UiGraphics.WaveformBarView(this, 14);
        LinearLayout.LayoutParams wfLp = new LinearLayout.LayoutParams(0, dp(15), 1f);
        wfLp.rightMargin = dp(6);
        waveRow.addView(h.waveform, wfLp);

        h.durChip = monoText("00:00", 10.5f, 0xff2a423b, true);
        h.durChip.setPadding(dp(6), dp(2), dp(6), dp(2));
        h.durChip.setBackground(roundBg(0xfff1f5f3, dp(5), 0, 0));
        h.durChip.setSingleLine(true);
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(-2, -2);
        dcLp.rightMargin = dp(6);
        waveRow.addView(h.durChip, dcLp);

        h.mbText = monoText("0.00 MB", 10.5f, UiGraphics.MUTED, false);
        h.mbText.setSingleLine(true);
        waveRow.addView(h.mbText);
        mid.addView(waveRow, wrLp);

        h.miniProg = new UiGraphics.SmoothProgressView(this);
        LinearLayout.LayoutParams mpLp = new LinearLayout.LayoutParams(-1, dp(4));
        mpLp.topMargin = dp(6);
        h.miniProg.setVisibility(View.GONE);
        mid.addView(h.miniProg, mpLp);

        h.noteText = text("", 10.5f, UiGraphics.MUTED, false);
        h.noteText.setSingleLine(true);
        h.noteText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ntLp = new LinearLayout.LayoutParams(-1, -2);
        ntLp.topMargin = dp(4);
        h.noteText.setVisibility(View.GONE);
        mid.addView(h.noteText, ntLp);

        mid.setOnClickListener(v -> {
            RecordingStore.Row latest = store.get(h.row.id);
            recordingActions(latest != null ? latest : h.row);
        });
        h.card.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        // Far-Right Minimalist Trash Icon Button (Cold anti-mis-touch zone)
        FrameLayout delHit = new FrameLayout(this);
        delHit.setPadding(dp(8), dp(4), dp(2), dp(4));
        h.delBtn = new FrameLayout(this);
        h.delBtn.setBackground(roundBg(0xfff8fafc, dp(9), dp(1), 0xffe2e8f0));
        h.delIcon = new UiGraphics.VectorIconView(this, UiGraphics.VectorIconView.Kind.TRASH, UiGraphics.SLATE_LIGHT);
        h.delBtn.addView(h.delIcon, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        delHit.addView(h.delBtn, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));

        delHit.setTag("delete:" + row.id);
        delHit.setClickable(true);
        delHit.setContentDescription("删除手机副本，录音 " + row.fileId);
        delHit.setOnClickListener(v -> {
            RecordingStore.Row latest = store.get(h.row.id);
            promptLocalDelete(latest != null ? latest : h.row);
        });
        h.card.addView(delHit, new LinearLayout.LayoutParams(-2, -2));

        return h;
    }

    private void updateCopyRowDynamic(CopyRowHolder h, RecordingStore.Row row,
                                      RuntimeTelemetry.Transfer liveUp,
                                      RuntimeTelemetry.Transfer liveBle,
                                      String deviceId, String endpoint,
                                      DeviceFilesStore.Snapshot snap) {
        h.row = row;
        boolean isPlaying = row.id.equals(playingRowId) && player != null;
        boolean isUp = liveUp != null && row.id.equals(liveUp.recordingId);
        boolean isBle = liveBle != null && row.id.equals(liveBle.recordingId);

        boolean focusedDel = pendingDeleteRow != null && row.id.equals(pendingDeleteRow.id);
        h.card.setBackground(roundBg(
                focusedDel ? 0xfffffdfd : isPlaying ? 0xfffafdfc : Color.WHITE,
                dp(17), dp(1),
                focusedDel ? UiGraphics.DANGER_BORDER : isPlaying ? UiGraphics.GREEN : 0x10172f29));

        if (isPlaying) {
            h.playCircle.setBackground(roundBg(UiGraphics.GREEN, dp(99), 0, 0));
            h.playIcon.setIcon(UiGraphics.VectorIconView.Kind.PAUSE, Color.WHITE);
        } else if (row.saved()) {
            h.playCircle.setBackground(roundBg(0xffe6f3ef, dp(99), 0, 0));
            h.playIcon.setIcon(UiGraphics.VectorIconView.Kind.PLAY, UiGraphics.GREEN);
        } else {
            h.playCircle.setBackground(roundBg(UiGraphics.AMBER_SOFT, dp(99), 0, 0));
            h.playIcon.setIcon(UiGraphics.VectorIconView.Kind.DOWNLOAD, UiGraphics.AMBER_DARK);
        }

        // Status badge & mini progress
        if (isUp) {
            int pct = ConnectionHud.percent(liveUp.currentBytes, liveUp.totalBytes);
            float ratio = liveUp.totalBytes > 0 ? (float) liveUp.currentBytes / liveUp.totalBytes : 0f;
            styleCopyBadge(h, UiGraphics.VectorIconView.Kind.UPLOAD, UiGraphics.BLUE_DARK, UiGraphics.BLUE_SOFT,
                    pct + "% · " + ConnectionHud.formatMbPair(liveUp.currentBytes, liveUp.totalBytes));
            h.waveform.bind(row.fileId, ratio, UiGraphics.BLUE, 0xffcbd5e1);
            h.durChip.setText(ConnectionHud.formatDurationClock(row.duration));
            h.durChip.setBackground(roundBg(0xfff1f5f3, dp(5), 0, 0));
            h.durChip.setTextColor(0xff2a423b);
            h.mbText.setText("上传中");
            h.miniProg.setVisibility(View.VISIBLE);
            h.miniProg.setProgress(ratio, 0xffe2e8f0, UiGraphics.BLUE, 0xff38bdf8);
        } else if (isBle || row.state.equals("RECEIVING")) {
            long cur = isBle ? liveBle.currentBytes : row.bytes;
            long tot = isBle ? liveBle.totalBytes : estimateExpectedAudioBytes(row);
            int pct = tot > 0 ? ConnectionHud.percent(cur, tot) : 0;
            float ratio = tot > 0 ? (float) cur / tot : 0.4f;
            styleCopyBadge(h, UiGraphics.VectorIconView.Kind.DOWNLOAD, UiGraphics.BLUE_DARK, UiGraphics.BLUE_SOFT,
                    tot > 0 ? "接收中 " + pct + "%" : "接收中");
            h.waveform.bind(row.fileId, ratio, UiGraphics.BLUE, 0xffcbd5e1);
            h.durChip.setText(ConnectionHud.formatDurationClock(isBle ? liveBle.durationMs : estimateExpectedDurationMs(row)));
            h.mbText.setText(ConnectionHud.formatMbPair(cur, tot));
            h.miniProg.setVisibility(View.VISIBLE);
            h.miniProg.setProgress(ratio, 0xffe2e8f0, UiGraphics.BLUE, 0xff38bdf8);
        } else if (row.state.equals("SENT")) {
            boolean cleaned = row.cleanupState.equals("DELETED") || row.cleanupState.equals("ABSENT");
            styleCopyBadge(h, UiGraphics.VectorIconView.Kind.CLOUD_OK, UiGraphics.GREEN, UiGraphics.GREEN_SOFT,
                    cleaned ? "已上云 · 豆已清" : "已上云");
            h.miniProg.setVisibility(View.GONE);
            bindSavedWaveAndDur(h, row, isPlaying, UiGraphics.GREEN);
        } else if (row.state.equals("READY")) {
            boolean noRelay = prefs().getString("endpoint", "").isEmpty();
            if (noRelay) {
                styleCopyBadge(h, null, UiGraphics.SLATE, UiGraphics.SLATE_SOFT, "仅手机保留");
            } else {
                styleCopyBadge(h, UiGraphics.VectorIconView.Kind.UPLOAD, UiGraphics.BLUE_DARK, UiGraphics.BLUE_SOFT,
                        row.attempts > 0 ? "待重试上传" : "排队待上传");
            }
            h.miniProg.setVisibility(View.GONE);
            bindSavedWaveAndDur(h, row, isPlaying, noRelay ? UiGraphics.SLATE_MUTED : UiGraphics.GREEN);
        } else if (row.state.equals("SERVER_DELETED")) {
            styleCopyBadge(h, null, UiGraphics.SLATE, UiGraphics.SLATE_SOFT, "仅手机保留");
            h.miniProg.setVisibility(View.GONE);
            bindSavedWaveAndDur(h, row, isPlaying, UiGraphics.SLATE_MUTED);
        } else if (row.reconciled()) {
            styleCopyBadge(h, UiGraphics.VectorIconView.Kind.CLOUD_OK, UiGraphics.GREEN, UiGraphics.GREEN_SOFT, "重复已核对");
            h.miniProg.setVisibility(View.GONE);
            h.waveform.bind(row.fileId, 1f, UiGraphics.SLATE_MUTED, 0xffcbd5ce);
            h.durChip.setText(ConnectionHud.formatDurationClock(estimateExpectedDurationMs(row)));
            h.mbText.setText(ConnectionHud.formatMb(row.bytes));
        } else if (row.state.equals("LOCAL_DELETING")) {
            styleCopyBadge(h, null, UiGraphics.DANGER, UiGraphics.DANGER_SOFT, "删除未完成");
            h.miniProg.setVisibility(View.GONE);
            h.waveform.bind(row.fileId, 0f, UiGraphics.SLATE_LIGHT, 0xffe2e8f0);
            h.durChip.setText(ConnectionHud.formatDurationClock(row.duration));
            h.mbText.setText("点右侧重试");
        } else {
            // WAITING
            boolean actionable = isActionableRow(row, deviceId, endpoint, snap);
            long tot = estimateExpectedAudioBytes(row);
            int pct = tot > 0 ? ConnectionHud.percent(row.bytes, tot) : 0;
            if (actionable) {
                styleCopyBadge(h, UiGraphics.VectorIconView.Kind.CLOCK, UiGraphics.AMBER_DARK, UiGraphics.AMBER_SOFT,
                        pct > 0 ? "待补收 " + pct + "%" : "待补收");
                h.waveform.bind(row.fileId, pct / 100f, UiGraphics.AMBER, 0xffe2e8f0);
            } else {
                styleCopyBadge(h, null, UiGraphics.SLATE, UiGraphics.SLATE_SOFT, "豆内已无 · 残片");
                h.waveform.bind(row.fileId, pct / 100f, UiGraphics.SLATE_LIGHT, 0xffe2e8f0);
            }
            h.miniProg.setVisibility(View.GONE);
            h.durChip.setText(ConnectionHud.formatDurationClock(estimateExpectedDurationMs(row)));
            h.mbText.setText(tot > 0 ? ConnectionHud.formatMbPair(row.bytes, tot) : ConnectionHud.formatMb(row.bytes));
        }

        // Show subtle 1-line note only for actionable warnings (unknown cleanup / readback proof / upload error / orphan)
        String note = "";
        if (row.cleanupState.equals("UNKNOWN") || row.cleanupState.equals("FAILED")) {
            note = row.cleanupDetail;
        } else if (row.readbackVerified && !row.cleanupState.equals("DELETED")) {
            note = "两次复读一致 · 自动清豆已跳过（可点此手动核验清理）";
        } else if (row.state.equals("READY") && !row.error.isEmpty()) {
            note = row.error;
        } else if (row.state.equals("WAITING") && !isActionableRow(row, deviceId, endpoint, snap)) {
            note = "录音豆内已无原件，无法续传；可点右侧删除此残片";
        }
        if (note.isEmpty()) {
            h.noteText.setVisibility(View.GONE);
        } else {
            h.noteText.setText(note);
            h.noteText.setVisibility(View.VISIBLE);
        }
    }

    private void bindSavedWaveAndDur(CopyRowHolder h, RecordingStore.Row row, boolean isPlaying, int waveColor) {
        if (isPlaying && player != null) {
            int pos = 0, dur = (int) Math.max(1L, row.duration);
            try {
                pos = player.getCurrentPosition();
                if (player.getDuration() > 0) dur = player.getDuration();
            } catch (IllegalStateException ignored) {}
            float ratio = Math.min(1f, pos / (float) dur);
            h.waveform.bind(row.fileId, ratio, UiGraphics.GREEN, 0xffcbd5ce);
            h.durChip.setText(ConnectionHud.formatDurationClock(pos) + "/" + ConnectionHud.formatDurationClock(dur));
            h.durChip.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(5), 0, 0));
            h.durChip.setTextColor(UiGraphics.GREEN);
        } else {
            h.waveform.bind(row.fileId, 1f, waveColor, 0xffcbd5ce);
            h.durChip.setText(ConnectionHud.formatDurationClock(row.duration));
            h.durChip.setBackground(roundBg(0xfff1f5f3, dp(5), 0, 0));
            h.durChip.setTextColor(0xff2a423b);
        }
        h.mbText.setText(ConnectionHud.formatMb(row.bytes));
    }

    private void styleCopyBadge(CopyRowHolder h, UiGraphics.VectorIconView.Kind kind,
                                int textColor, int bgColor, String label) {
        h.badgeBox.setBackground(roundBg(bgColor, dp(6), 0, 0));
        if (kind == null) {
            h.badgeIcon.setVisibility(View.GONE);
        } else {
            h.badgeIcon.setVisibility(View.VISIBLE);
            h.badgeIcon.setIcon(kind, textColor);
        }
        h.badgeText.setTextColor(textColor);
        h.badgeText.setText(label);
    }

    // -----------------------------------------------------------------------
    // Render Tab 2: Settings, Maintenance & Logs
    // -----------------------------------------------------------------------

    private void renderSettingsTab(SharedPreferences p, String deviceId) {
        String endpoint = p.getString("endpoint", "");
        if (endpoint.isEmpty()) {
            relaySubText.setText("未配置 · 录音仅保存在手机");
            relayBadgeText.setText("去配置 ▸");
            relayBadgeText.setTextColor(UiGraphics.SLATE);
            relayBadgeText.setBackground(roundBg(UiGraphics.SLATE_SOFT, dp(7), 0, 0));
        } else {
            relaySubText.setText(endpoint);
            relayBadgeText.setText("● 已接通 ▸");
            relayBadgeText.setTextColor(UiGraphics.GREEN);
            relayBadgeText.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(7), 0, 0));
        }

        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        if (exempt) {
            batterySubText.setText("系统后台豁免已就绪");
            batteryBadgeText.setText("✓ 正常");
            batteryBadgeText.setTextColor(UiGraphics.GREEN);
            batteryBadgeText.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(7), 0, 0));
        } else {
            batterySubText.setText("建议设为不受限制以防断连");
            batteryBadgeText.setText("去允许 ▸");
            batteryBadgeText.setTextColor(UiGraphics.AMBER_DARK);
            batteryBadgeText.setBackground(roundBg(UiGraphics.AMBER_SOFT, dp(7), 0, 0));
        }

        DeviceFilesStore.Snapshot snap = deviceFiles.read(deviceId);
        maintBeanSub.setText(snap.fetchedAt > 0
                ? "快照 " + snap.entries.size() + " 条 · 手动核验清豆"
                : "查看快照 · 手动核验清豆");
        String fw = p.getString("firmware", "");
        maintBindSub.setText((fw.isEmpty() ? "未读固件" : "固件 " + fw) + " · 发送绑定请求");

        boolean historyOn = p.getBoolean("history", false);
        maintHistoryBadge.setText(historyOn ? "已开启" : "默认关");
        maintHistoryBadge.setTextColor(historyOn ? UiGraphics.GREEN : UiGraphics.MUTED);

        String tail = EventLog.tail(this);
        if (!tail.equals(lastLogTail) || logLinesContainer.getChildCount() == 0) {
            lastLogTail = tail;
            logLinesContainer.removeAllViews();
            if (tail.trim().isEmpty()) {
                logLinesContainer.addView(monoText("暂无运行事件日志", 11f, 0xff7fa399, false));
            } else {
                String[] lines = tail.split("\n");
                int from = Math.max(0, lines.length - 16);
                for (int i = lines.length - 1; i >= from; i--) {
                    String raw = lines[i].trim();
                    if (raw.isEmpty()) continue;
                    logLinesContainer.addView(buildLogLineView(raw));
                }
            }
        }
    }

    private View buildLogLineView(String raw) {
        String clock = "--:--:--";
        String body = raw;
        int sp = raw.indexOf(' ');
        if (sp > 10) {
            try {
                clock = LOG_CLOCK.format(Instant.parse(raw.substring(0, sp)));
                body = raw.substring(sp + 1);
            } catch (Exception ignored) {}
        }
        int dotColor = UiGraphics.GREEN_BRIGHT;
        if (body.contains("中断") || body.contains("断开") || body.contains("失败")
                || body.contains("异常") || body.contains("超时") || body.contains("拒绝")) {
            dotColor = UiGraphics.AMBER;
        } else if (body.contains("文件头") || body.contains("接收") || body.contains("启动") || body.contains("补收")) {
            dotColor = 0xff38bdf8;
        }

        LinearLayout row = hBox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2.5f), 0, dp(2.5f));
        View dot = new View(this);
        dot.setBackground(roundBg(dotColor, dp(99), 0, 0));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(6.5f), dp(6.5f));
        dLp.rightMargin = dp(6);
        row.addView(dot, dLp);

        TextView tClock = monoText(clock, 10.5f, 0xff7fa399, false);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
        cLp.rightMargin = dp(7);
        row.addView(tClock, cLp);

        TextView tBody = monoText(body, 10.5f, 0xffe2ece9, false);
        tBody.setSingleLine(true);
        tBody.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(tBody, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    // -----------------------------------------------------------------------
    // Bean Internal Files Bottom Sheet (Replaces heavy secondary navigation)
    // -----------------------------------------------------------------------

    private void showBeanFilesSheet() {
        if (beanSheetDialog != null && beanSheetDialog.isShowing()) return;
        Dialog sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = vBox();
        box.setPadding(dp(16), dp(12), dp(16), dp(22));
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xfff6f8f5);
        float r = dp(26);
        sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(sheetBg);

        View handle = new View(this);
        handle.setBackground(roundBg(0xffcbd5e1, dp(99), 0, 0));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = dp(12);
        box.addView(handle, hLp);

        LinearLayout head = hBox();
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headLeft = vBox();
        TextView sheetTitle = text("录音豆内文件", 17f, UiGraphics.INK, true);
        TextView sheetSub = text("", 11.5f, UiGraphics.MUTED, false);
        headLeft.addView(sheetTitle);
        headLeft.addView(sheetSub);
        head.addView(headLeft, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout refBtn = hBox();
        refBtn.setGravity(Gravity.CENTER_VERTICAL);
        refBtn.setPadding(dp(11), dp(6), dp(11), dp(6));
        refBtn.setBackground(roundBg(0xffedf5f2, dp(99), 0, 0));
        UiGraphics.VectorIconView refIcon = new UiGraphics.VectorIconView(
                this, UiGraphics.VectorIconView.Kind.REFRESH, UiGraphics.GREEN);
        LinearLayout.LayoutParams riLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        riLp.rightMargin = dp(4);
        refBtn.addView(refIcon, riLp);
        refBtn.addView(text("刷新", 12f, UiGraphics.GREEN, true));
        refBtn.setOnClickListener(v -> {
            if (isRunning()) startService(new Intent(this, RecorderService.class).setAction(RecorderService.VIEW_FILES));
            else toast("请先在首页开启连接守护");
        });
        head.addView(refBtn);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        TextView actionNotice = text("", 11.5f, UiGraphics.GREEN, true);
        LinearLayout.LayoutParams anLp = new LinearLayout.LayoutParams(-1, -2);
        anLp.topMargin = dp(6);
        actionNotice.setVisibility(View.GONE);
        box.addView(actionNotice, anLp);

        ScrollView listScroll = new ScrollView(this);
        LinearLayout listCol = vBox();
        listCol.setPadding(0, dp(10), 0, dp(10));
        listScroll.addView(listCol);
        int maxSheetListH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.46f);
        box.addView(listScroll, new LinearLayout.LayoutParams(-1, maxSheetListH));

        TextView closeBtn = text("收起面板", 13.5f, 0xff334842, true);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(12), dp(11), dp(12), dp(11));
        closeBtn.setBackground(roundBg(0xffe7ece9, dp(12), 0, 0));
        closeBtn.setOnClickListener(v -> sheet.dismiss());
        box.addView(closeBtn, new LinearLayout.LayoutParams(-1, -2));

        final String[] sheetKey = {""};
        beanSheetRefresh = () -> {
            String addr = prefs().getString("address", "");
            String dev = Settings.activeDeviceId(this);
            DeviceFilesStore.Snapshot snap = deviceFiles.read(dev);
            boolean online = isRunning() && prefs().getBoolean("ble_ready", false)
                    && dev.equals(prefs().getString("ble_device", ""));
            sheetTitle.setText("录音豆内文件 (" + snap.entries.size() + " 条)");
            String readInfo = snap.fetchedAt > 0 ? shortClock(snap.fetchedAt) + " 读取" : "尚未读取";
            sheetSub.setText(readInfo
                    + (snap.firmware.isEmpty() ? "" : " · v" + snap.firmware)
                    + (online ? " · 在线" : " · 离线快照"));
            if (!snap.action.isEmpty()) {
                actionNotice.setText(snap.action);
                actionNotice.setVisibility(View.VISIBLE);
            } else {
                actionNotice.setVisibility(View.GONE);
            }

            Map<Long, RecordingStore.Row> localMap = new HashMap<>();
            for (RecordingStore.Row row : store.forDevice(dev)) localMap.put(row.fileId, row);
            StringBuilder k = new StringBuilder(dev).append('|').append(snap.fetchedAt).append('|').append(playingRowId);
            for (Protocol.Entry e : snap.entries.values()) k.append(e.id).append(':').append(e.size).append(';');
            for (RecordingStore.Row row : localMap.values()) k.append(row.id).append(row.state).append(row.cleanupState);
            if (sheetKey[0].equals(k.toString())) return;
            sheetKey[0] = k.toString();

            listCol.removeAllViews();
            if (snap.entries.isEmpty()) {
                TextView emp = text(snap.fetchedAt == 0
                        ? "尚未读取豆内列表，请连接录音豆后点击刷新"
                        : "录音豆内当前为 0 条文件（手机与云端已保存副本不受影响）", 13f, UiGraphics.MUTED, false);
                emp.setGravity(Gravity.CENTER);
                emp.setPadding(dp(12), dp(36), dp(12), dp(36));
                listCol.addView(emp, new LinearLayout.LayoutParams(-1, -2));
                return;
            }
            for (Protocol.Entry entry : snap.entries.values()) {
                RecordingStore.Row localRow = localMap.get(entry.id);
                View card = buildBeanEntryCard(dev, snap, entry, localRow);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(8);
                listCol.addView(card, lp);
            }
        };
        beanSheetRefresh.run();

        sheet.setContentView(box);
        Window sw = sheet.getWindow();
        if (sw != null) {
            sw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            sw.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sw.setGravity(Gravity.BOTTOM);
        }
        sheet.setOnDismissListener(d -> {
            beanSheetDialog = null;
            beanSheetRefresh = null;
        });
        beanSheetDialog = sheet;
        sheet.show();
        if (isRunning()) startService(new Intent(this, RecorderService.class).setAction(RecorderService.VIEW_FILES));
    }

    private View buildBeanEntryCard(String dev, DeviceFilesStore.Snapshot snap,
                                    Protocol.Entry entry, RecordingStore.Row row) {
        LinearLayout card = hBox();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(roundBg(Color.WHITE, dp(16), dp(1), 0x10172f29));

        boolean hasLocalOgg = row != null && row.saved() && store.file(row.id, ".ogg").isFile();
        String recId = RecordingStore.id(dev, entry.id);
        boolean isPlaying = recId.equals(playingRowId) && player != null;

        FrameLayout leftIcon = new FrameLayout(this);
        leftIcon.setBackground(roundBg(
                isPlaying ? UiGraphics.GREEN : hasLocalOgg ? 0xffe6f3ef : UiGraphics.BLUE_SOFT, dp(99), 0, 0));
        leftIcon.addView(new UiGraphics.VectorIconView(this,
                        isPlaying ? UiGraphics.VectorIconView.Kind.PAUSE
                                : hasLocalOgg ? UiGraphics.VectorIconView.Kind.PLAY
                                : UiGraphics.VectorIconView.Kind.DOWNLOAD,
                        isPlaying ? Color.WHITE : hasLocalOgg ? UiGraphics.GREEN : UiGraphics.BLUE_DARK),
                new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        leftIcon.setOnClickListener(v -> {
            if (isPlaying) releasePlayer();
            else if (hasLocalOgg) playLocal(store.file(recId, ".ogg"), recId);
            else triggerBeanDownload(dev, entry.id, row);
        });
        LinearLayout.LayoutParams liLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        liLp.rightMargin = dp(10);
        card.addView(leftIcon, liLp);

        LinearLayout mid = vBox();
        LinearLayout r1 = hBox();
        r1.setGravity(Gravity.CENTER_VERTICAL);
        String entryTitle = entry.id >= 946684800L && entry.id <= 4102444800L
                ? shortTime(entry.id * 1000L) : "ID " + entry.id;
        r1.addView(text(entryTitle, 13f, UiGraphics.INK, true), new LinearLayout.LayoutParams(0, -2, 1f));

        String backupTag;
        int bFg, bBg;
        if (hasLocalOgg && row.state.equals("SENT")) {
            backupTag = "手机+云均已备份"; bFg = UiGraphics.GREEN; bBg = UiGraphics.GREEN_SOFT;
        } else if (hasLocalOgg) {
            backupTag = "手机已存 · 待上云"; bFg = UiGraphics.BLUE_DARK; bBg = UiGraphics.BLUE_SOFT;
        } else if (row != null && row.localDeleted()) {
            backupTag = "手机副本已删"; bFg = UiGraphics.SLATE; bBg = UiGraphics.SLATE_SOFT;
        } else {
            backupTag = "尚未下载到手机"; bFg = UiGraphics.BLUE_DARK; bBg = UiGraphics.BLUE_SOFT;
        }
        TextView bChip = text(backupTag, 10.5f, bFg, true);
        bChip.setPadding(dp(6), dp(2), dp(6), dp(2));
        bChip.setBackground(roundBg(bBg, dp(6), 0, 0));
        r1.addView(bChip);
        mid.addView(r1, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout r2 = hBox();
        r2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(-1, -2);
        r2Lp.topMargin = dp(5);

        long durMs = RecordingSizeUnit.usesVerifiedTransportProtocol(snap.firmware) ? entry.size : (row != null ? row.duration : 0L);
        long bytesEst = row != null && row.bytes > 0 ? row.bytes : (durMs > 0 ? (durMs / 20L) * 160L : 0L);
        TextView durMeta = monoText(ConnectionHud.formatDurationClock(durMs) + " · " + ConnectionHud.formatMb(bytesEst),
                10.5f, 0xff2a423b, true);
        durMeta.setPadding(dp(6), dp(2), dp(6), dp(2));
        durMeta.setBackground(roundBg(0xfff1f5f3, dp(5), 0, 0));
        r2.addView(durMeta);
        r2.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        boolean canCleanBean = hasLocalOgg && row.state.equals("SENT");
        if (canCleanBean) {
            TextView cleanBtn = text("核验清豆", 11f, UiGraphics.AMBER_DARK, true);
            cleanBtn.setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f));
            cleanBtn.setBackground(roundBg(UiGraphics.AMBER_SOFT, dp(7), 0, 0));
            cleanBtn.setOnClickListener(v -> confirmBeanCleanup(row));
            r2.addView(cleanBtn);
        } else if (!hasLocalOgg) {
            TextView dlBtn = text("下载到手机", 11f, UiGraphics.GREEN, true);
            dlBtn.setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f));
            dlBtn.setBackground(roundBg(UiGraphics.GREEN_SOFT, dp(7), 0, 0));
            dlBtn.setOnClickListener(v -> triggerBeanDownload(dev, entry.id, row));
            r2.addView(dlBtn);
        }
        mid.addView(r2, r2Lp);
        card.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    private void triggerBeanDownload(String dev, long fileId, RecordingStore.Row row) {
        if (!isRunning()) {
            toast("请先在首页开启连接守护");
            return;
        }
        Runnable req = () -> startService(new Intent(this, RecorderService.class)
                .setAction(RecorderService.DOWNLOAD)
                .putExtra("device", dev)
                .putExtra("file_id", fileId)
                .putExtra("restore_deleted", row != null && row.localDeleted()));
        if (row != null && row.localDeleted()) {
            new AlertDialog.Builder(this)
                    .setTitle("重新下载已删除的手机副本？")
                    .setMessage("取消本地删除标记并从录音豆重新下载此录音。")
                    .setPositiveButton("重新下载", (d, w) -> req.run())
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            req.run();
        }
    }

    // -----------------------------------------------------------------------
    // Actions, Permissions, BLE Scan, Audio Player & Diagnostics
    // -----------------------------------------------------------------------

    private void startSessionOrScan() {
        if (prefs().getString("address", "").isEmpty()) {
            scan(true);
        } else {
            startSession();
        }
    }

    private void stopSession() {
        prefs().edit().putBoolean("enabled", false).commit();
        stopService(new Intent(this, RecorderService.class));
        RuntimeTelemetry.resetServiceStopped();
        renderAll();
    }

    private void triggerRefreshAndRetry() {
        store.retryUploads();
        store.retryDownloads();
        if (isRunning()) {
            serviceAction(RecorderService.REFRESH);
            serviceAction(RecorderService.RETRY);
            toast("已触发队列检查与重试");
        } else {
            toast("已重置重试队列；开启连接后继续同步");
        }
        renderAll();
    }

    private boolean isRunning() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(50)) {
            if (info.service.getClassName().equals(RecorderService.class.getName())) return true;
        }
        return false;
    }

    private boolean permissions(boolean forScan) {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
        } else if (forScan) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), forScan ? 10 : 11);
            return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        boolean allowed = true;
        for (int i = 0; i < names.length; i++) {
            if (!names[i].equals(Manifest.permission.POST_NOTIFICATIONS)
                    && results[i] != PackageManager.PERMISSION_GRANTED) {
                allowed = false;
            }
        }
        if (!allowed) {
            toast("请在系统设置中允许附近设备/蓝牙权限");
            return;
        }
        if (request == 10) scanAllowed();
        else if (request == 11) startAllowed();
    }

    private void scan(boolean autoStartAfterPick) {
        this.startAfterScan = autoStartAfterPick;
        if (permissions(true)) scanAllowed();
    }

    private void scanAllowed() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            toast("请先打开手机蓝牙");
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        stopScan();
        scanDevices.clear();
        scanAdvHashes.clear();
        seen.clear();
        scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        scanDialog = new AlertDialog.Builder(this)
                .setTitle("选择安克录音豆 · 扫描中")
                .setAdapter(scanAdapter, (dialog, which) -> {
                    BluetoothDevice d = scanDevices.get(which);
                    String pickedAdvHash = which < scanAdvHashes.size() ? scanAdvHashes.get(which) : "";
                    String oldAddr = prefs().getString("address", "");
                    String oldAdvHash = prefs().getString("adv_identity_hash", "");
                    boolean samePhysicalBean = !pickedAdvHash.isEmpty() && !oldAdvHash.isEmpty()
                            ? pickedAdvHash.equals(oldAdvHash) : oldAddr.equalsIgnoreCase(d.getAddress());
                    String stableId = samePhysicalBean
                            ? Settings.activeDeviceId(this)
                            : RecordingStore.deviceId(d.getAddress());
                    if (stableId.isEmpty()) stableId = RecordingStore.deviceId(d.getAddress());

                    SharedPreferences.Editor edit = prefs().edit()
                            .putString("address", d.getAddress())
                            .putString("name", d.getName() == null ? "Soundcore Work" : d.getName())
                            .putString("storage_device", stableId)
                            .putString("storage_bound_address", d.getAddress());
                    if (!pickedAdvHash.isEmpty()) edit.putString("adv_identity_hash", pickedAdvHash);
                    else if (!samePhysicalBean) edit.remove("adv_identity_hash");
                    if (!samePhysicalBean) {
                        edit.putLong("since", System.currentTimeMillis() / 1000L)
                                .remove("serial_hash").remove("firmware").remove("battery")
                                .remove("last_stable_ms").remove("last_online_at");
                        RuntimeTelemetry.lastStableDurationMs = 0L;
                    }
                    edit.apply();
                    stopScan();
                    renderAll();
                    if (isRunning()) {
                        startService(new Intent(this, RecorderService.class).setAction(RecorderService.SWITCH_DEVICE));
                    } else if (startAfterScan) {
                        startSession();
                    }
                })
                .setNegativeButton("取消", (d, w) -> stopScan())
                .create();
        scanDialog.setOnDismissListener(d -> stopScan());
        scanDialog.show();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            toast("蓝牙扫描不可用");
            return;
        }
        scanning = true;
        scanner.startScan(null, new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        main.postDelayed(() -> {
            stopScan();
            if (scanDialog != null && scanDialog.isShowing()) {
                scanDialog.setTitle(scanDevices.isEmpty() ? "未找到录音豆，请断开其他应用的蓝牙连接后重试" : "选择安克录音豆");
            }
        }, 15_000L);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> {
                if (!scanning) return;
                BluetoothDevice d = result.getDevice();
                ScanRecord record = result.getScanRecord();
                String name = record == null ? null : record.getDeviceName();
                if (name == null) name = d.getName();
                String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                boolean service = record != null && record.getServiceUuids() != null
                        && record.getServiceUuids().contains(new ParcelUuid(Protocol.SERVICE));
                if (!service && !lower.contains("soundcore") && !lower.contains("d3200")
                        && !lower.contains("anker") && !lower.contains("work")) return;
                if (!seen.add(d.getAddress())) return;
                scanDevices.add(d);
                scanAdvHashes.add(DeviceIdentity.extractAdvIdentityHash(record == null ? null : record.getBytes()));
                scanAdapter.add((name == null ? "Soundcore Work" : name)
                        + "\n" + d.getAddress() + " · " + result.getRssi() + " dBm");
            });
        }

        @Override public void onScanFailed(int code) {
            main.post(() -> {
                stopScan();
                toast("扫描失败（" + code + "），请检查蓝牙权限");
            });
        }
    };

    private void stopScan() {
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        }
        scanning = false;
        scanner = null;
    }

    private void startSession() {
        if (permissions(false)) startAllowed();
    }

    private void startAllowed() {
        if (prefs().getString("address", "").isEmpty()) {
            scan(true);
            return;
        }
        RuntimeTelemetry.handshakeStep = 1;
        prefs().edit().putBoolean("enabled", true).commit();
        try {
            startForegroundService(new Intent(this, RecorderService.class));
        } catch (RuntimeException e) {
            prefs().edit().putBoolean("enabled", false).apply();
            toast("后台启动失败，请检查权限与电池设置");
        }
        main.postDelayed(this::renderAll, 300L);
    }

    private void serviceAction(String action) {
        if (isRunning()) startService(new Intent(this, RecorderService.class).setAction(action));
        else toast("请先开启连接守护");
    }

    private void requestBatteryExemption() {
        Intent request = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(request);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void configureUpload() {
        LinearLayout content = vBox();
        content.setPadding(dp(22), dp(10), dp(22), 0);
        content.addView(text("填写自有 HTTPS 录音接收地址（默认不连接任何第三方云）。", 13f, UiGraphics.MUTED, false));
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("https://你的中转/v1/recordings");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs().getString("endpoint", ""));
        content.addView(url);

        EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setHint(prefs().contains("token") ? "原地址留空保留 Token，更换地址请重填" : "Bearer Token（可选）");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(token);

        CheckBox clear = new CheckBox(this);
        clear.setText("清除已保存的 Token");
        content.addView(clear);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Daybook 中转服务")
                .setView(content)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Settings.configureUpload(this, url.getText().toString(),
                        token.getText().toString(), clear.isChecked());
                store.retryUploads();
                if (isRunning()) serviceAction(RecorderService.RETRY);
                renderAll();
                dialog.dismiss();
            } catch (Exception e) {
                toast(e instanceof IllegalArgumentException ? "地址须为 HTTPS，且不含账号或查询参数" : "保存失败，请重试");
            }
        });
    }

    private void recordingActions(RecordingStore.Row row) {
        List<String> actions = new ArrayList<>();
        if (row.reconciled()) actions.add("查看已核对的完整副本");
        if (row.saved()) {
            actions.add("播放本地录音");
            actions.add("导出 Ogg 音频");
            actions.add("导出原始 Opus 分片");
        }
        if (row.state.equals("SENT") && !row.cleanupState.equals("DELETED")) {
            actions.add("重新核对并清理豆内副本");
        }
        actions.add("删除手机副本");

        new AlertDialog.Builder(this)
                .setTitle(shortTime(row.recordedAt) + " · " + ConnectionHud.formatMb(row.bytes))
                .setItems(actions.toArray(new String[0]), (d, w) -> {
                    String pick = actions.get(w);
                    if (pick.equals("查看已核对的完整副本")) {
                        RecordingStore.Row full = store.get(row.duplicateOf);
                        if (full != null && full.saved()) recordingActions(full);
                        else toast("完整副本状态已变化，请刷新查看");
                    } else if (pick.equals("播放本地录音")) {
                        playLocal(store.file(row.id, ".ogg"), row.id);
                    } else if (pick.equals("导出 Ogg 音频")) {
                        exportText = null;
                        exportFile = store.file(row.id, ".ogg");
                        createDocument("audio/ogg", exportFile.getName());
                    } else if (pick.equals("导出原始 Opus 分片")) {
                        exportText = null;
                        exportFile = store.file(row.id, ".opus.bin");
                        createDocument("application/octet-stream", exportFile.getName());
                    } else if (pick.equals("重新核对并清理豆内副本")) {
                        confirmBeanCleanup(row);
                    } else if (pick.equals("删除手机副本")) {
                        promptLocalDelete(row);
                    }
                })
                .show();
    }

    private void confirmBeanCleanup(RecordingStore.Row row) {
        new AlertDialog.Builder(this)
                .setTitle("清理这条豆内副本？")
                .setMessage("ID " + row.fileId + "\n\n"
                        + (row.readbackVerified ? row.manualReadbackNotice()
                        : "时长 " + ConnectionHud.formatDurationClock(row.duration) + " · " + ConnectionHud.formatMb(row.bytes) + "\n\n")
                        + "重新核对中转回执与豆内完整列表后永久删除豆内副本；手机与中转文件保留。")
                .setPositiveButton("核对并清理", (dialog, which) -> {
                    if (!isRunning()) {
                        toast("请先在首页开启连接守护");
                        return;
                    }
                    startService(new Intent(this, RecorderService.class)
                            .setAction(RecorderService.CLEANUP)
                            .putExtra("recording_id", row.id));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void playLocal(File f, String rowId) {
        releasePlayer();
        try {
            player = new MediaPlayer();
            playingRowId = rowId;
            player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                mp.start();
                renderAll();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                toast("系统播放器无法解码，可导出原音检查");
                releasePlayer();
                renderAll();
                return true;
            });
            player.setOnCompletionListener(mp -> {
                releasePlayer();
                renderAll();
            });
            player.prepareAsync();
        } catch (IOException e) {
            releasePlayer();
            toast("无法打开本地音频");
        }
    }

    private void releasePlayer() {
        playingRowId = "";
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
    }

    private String diagnosticText() {
        StringBuilder sb = new StringBuilder("Daybook 录音接收诊断\nApp ").append(appVersion())
                .append("\n手机 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
                .append("\n固件 ").append(prefs().getString("firmware", "未读取"))
                .append("\n").append(store.summary()).append("\n最近录音及全部待补收记录：\n");
        for (RecordingStore.Row row : store.diagnosticRows()) {
            sb.append("id=").append(row.fileId).append(" state=").append(row.state)
                    .append(" device=").append(row.device).append(" duplicateOf=").append(row.duplicateOf)
                    .append(" bytes=").append(row.bytes).append(" reportedSize=").append(row.expected)
                    .append(" durationMs=").append(row.duration).append(" readbackVerified=").append(row.readbackVerified)
                    .append(" reason=").append(row.error).append(" cleanup=").append(row.cleanupState)
                    .append(" cleanupAcknowledged=").append(row.cleanupAcknowledged)
                    .append(" cleanupDetail=").append(row.cleanupDetail).append('\n');
        }
        return sb.append("\n最近事件：\n").append(EventLog.tail(this)).toString();
    }

    private void createDocument(String type, String name) {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type)
                .putExtra(Intent.EXTRA_TITLE, name), 20);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        File file = exportFile;
        String txt = exportText;
        io.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException();
                if (file != null) {
                    try (InputStream in = new FileInputStream(file)) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    }
                } else {
                    out.write((txt == null ? "" : txt).getBytes(StandardCharsets.UTF_8));
                }
                main.post(() -> toast("已导出"));
            } catch (IOException e) {
                main.post(() -> toast("导出失败，请重试"));
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private SharedPreferences prefs() {
        return Settings.prefs(this);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "0.2.0";
        }
    }

    private static long estimateExpectedDurationMs(RecordingStore.Row row) {
        if (row == null) return 0L;
        if (row.duration > 0L) return row.duration;
        if (row.expected > OfflineReadback.FRAME_MILLIS && row.expected < 36_000_000L) return row.expected;
        return row.bytes > 0L ? (row.bytes / 160L) * 20L : 0L;
    }

    private static long estimateExpectedAudioBytes(RecordingStore.Row row) {
        if (row == null) return 0L;
        if (row.saved() && row.bytes > 0L) return row.bytes;
        if (row.expected > OfflineReadback.FRAME_MILLIS && row.expected < 36_000_000L) {
            return (row.expected / 20L) * 160L;
        }
        return 0L;
    }

    private static long estimateBeanTotalBytes(DeviceFilesStore.Snapshot snap) {
        long sum = 0L;
        for (Protocol.Entry e : snap.entries.values()) {
            if (RecordingSizeUnit.usesVerifiedTransportProtocol(snap.firmware) && e.size > 0) {
                sum += (e.size / 20L) * 160L;
            } else if (e.size > 0) {
                sum += e.size;
            }
        }
        return sum;
    }

    private static String shortTime(long millis) {
        if (millis <= 0L) return "--";
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) {
            return "今天 " + hm.format(new Date(millis));
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) {
            return "昨天 " + hm.format(new Date(millis));
        }
        return new SimpleDateFormat("MM/dd HH:mm", Locale.ROOT).format(new Date(millis));
    }

    private static String shortClock(long millis) {
        if (millis <= 0L) return "--:--";
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(millis));
    }

    private LinearLayout vBox() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout hBox() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    private View rowDivider() {
        View v = new View(this);
        v.setBackgroundColor(0xfff1f5f3);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.8f))));
        return v;
    }

    private TextView sectionHeader(String title, float topDp, float bottomDp) {
        TextView t = text(title, 12.5f, 0xff4a5e56, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = dp(3);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView monoText(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable roundBg(int fill, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    private int dp(float n) {
        return UiGraphics.dp(this, n);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
