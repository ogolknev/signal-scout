package dev.ogolknev.signalscout;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends Activity {
    private static final int UNKNOWN = CellInfo.UNAVAILABLE;
    private static final int GRAPHITE = 0xFF242626;
    private static final int SURFACE = 0xFF333535;
    private static final int TEXT = 0xFFF2F1EC;
    private static final int SUBTLE = 0xFFB9BAB5;
    private static final int MUTED = 0xFF92958E;
    private static final int ACCENT = 0xFFF5F5F3;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<SubscriptionInfo> subscriptions = new ArrayList<>();
    private final List<SavedPoint> points = new ArrayList<>();
    private TelephonyManager telephony;
    private RadioCallback callback;
    private LinearLayout simChoices;
    private int selectedIndex = -1;
    private int selectedSubscriptionId = -1;
    private TextView headline, reason, numbers, comparison, freshness, speed;
    private LinearLayout pointList;
    private PointsChart chart;
    private Button saveButton, testButton;
    private int rsrp = UNKNOWN, rsrq = UNKNOWN, snr = UNKNOWN, band = UNKNOWN, cid = UNKNOWN;
    private String carrier = "—", technology = "—";
    private long updatedAt = 0L;
    private boolean testing = false;
    private boolean refreshScheduled = false;
    private final AtomicLong downloadedBytes = new AtomicLong();
    private volatile long downloadStartedAt;
    private static final long TEST_BYTES = 10L * 1024 * 1024;
    private final Runnable speedTicker = new Runnable() {
        @Override public void run() {
            if (!testing || isFinishing() || isDestroyed()) return;
            long bytes = downloadedBytes.get();
            if (downloadStartedAt == 0 || bytes == 0) speed.setText("Подключаюсь к тестовому серверу…");
            else {
                long ms = Math.max(1, SystemClock.elapsedRealtime() - downloadStartedAt);
                double mbps = bytes * 8.0 / ms / 1000.0;
                speed.setText(String.format(Locale.forLanguageTag("ru"),
                        "Сейчас: %.1f Мбит/с (средняя с начала теста)\nЗагружено: %.1f из 10 МБ",
                        mbps, bytes / 1048576.0));
            }
            handler.postDelayed(this, 200);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (telephony != null && hasPermissions()) {
                try {
                    int subscription = selectedSubscriptionId;
                    telephony.requestCellInfoUpdate(getMainExecutor(), new TelephonyManager.CellInfoCallback() {
                        @Override public void onCellInfo(List<CellInfo> cells) {
                            if (selectedSubscriptionId == subscription) acceptCells(cells);
                        }
                        @Override public void onError(int errorCode, Throwable detail) { show(); }
                    });
                } catch (SecurityException ignored) { show(); }
            }
            show();
            handler.postDelayed(this, 5000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(GRAPHITE);
        getWindow().setNavigationBarColor(GRAPHITE);
        buildUi();
        restorePoints();
        renderPoints();
        if (hasPermissions()) initSubscriptions();
        else requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    }

    @Override protected void onResume() {
        super.onResume();
        if (headline != null && hasPermissions() && subscriptions.isEmpty()) initSubscriptions();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (hasPermissions()) initSubscriptions();
        else { headline.setText("Нужны разрешения"); reason.setText("Разреши доступ к состоянию телефона и точному местоположению в настройках приложения."); }
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(GRAPHITE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(body);
        setContentView(scroll);

        label(body, "ПОИСК СИГНАЛА", 13, ACCENT, true);
        label(body, "SIM ДЛЯ ЗАМЕРА — ВЫБЕРИ ОПЕРАТОРА", 14, SUBTLE, true);
        simChoices = new LinearLayout(this);
        simChoices.setOrientation(LinearLayout.VERTICAL);
        body.addView(simChoices);
        headline = label(body, "Ожидаю сигнал", 36, TEXT, true);
        reason = label(body, "Данные телефона появятся здесь", 18, SUBTLE, false);
        numbers = label(body, "", 17, TEXT, false);
        freshness = label(body, "", 14, MUTED, false);

        saveButton = button(body, "Запомнить эту точку");
        styleButton(saveButton, ACCENT, GRAPHITE, 20);
        saveButton.setOnClickListener(v -> {
            if (updatedAt == 0 || !"LTE".equals(technology) || SystemClock.elapsedRealtime() - updatedAt > 15000) return;
            int order = 1;
            for (SavedPoint point : points)
                if (point.subscription == selectedSubscriptionId) order = Math.max(order, point.order + 1);
            // Freeze values at the moment of the tap while the name dialog is open.
            SavedPoint candidate = new SavedPoint(selectedSubscriptionId, carrier, order,
                    "Точка " + order, rsrp, rsrq, snr, band, cid, grade(rsrp, rsrq, snr));
            editName(candidate, true);
        });
        comparison = label(body, "Сохрани исходную точку для сравнения.", 17, SUBTLE, false);

        label(body, "СРАВНЕНИЕ ТОЧЕК · ОЦЕНКА 0–4", 14, SUBTLE, true);
        HorizontalScrollView chartScroll = new HorizontalScrollView(this);
        chartScroll.setHorizontalScrollBarEnabled(true);
        chartScroll.setBackground(rounded(SURFACE, 20));
        chart = new PointsChart();
        chartScroll.addView(chart, new android.widget.FrameLayout.LayoutParams(-2, dp(220)));
        body.addView(chartScroll, new LinearLayout.LayoutParams(-1, dp(220)));
        pointList = new LinearLayout(this);
        pointList.setOrientation(LinearLayout.VERTICAL);
        body.addView(pointList);

        testButton = button(body, "Проверить скорость (до 10 МБ)");
        styleButton(testButton, SURFACE, TEXT, 20);
        testButton.setOnClickListener(v -> runSpeedTest());
        speed = label(body, "Во время загрузки покажу текущую среднюю скорость.", 16, SUBTLE, false);
        label(body, "Оценка относится к сигналу телефона. В роутере результат может отличаться.", 14, MUTED, false);
    }

    private TextView label(LinearLayout parent, String value, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setPadding(0, dp(11), 0, dp(11));
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private Button button(LinearLayout parent, String title) {
        Button button = new Button(this);
        button.setText(title);
        styleButton(button, SURFACE, TEXT, 18);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.topMargin = dp(20);
        parent.addView(button, params);
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void styleButton(Button button, int background, int foreground, int radius) {
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(background, radius), null));
        button.setBackgroundTintList(null);
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private void initSubscriptions() {
        SubscriptionManager manager = getSystemService(SubscriptionManager.class);
        subscriptions.clear();
        try {
            List<SubscriptionInfo> list = manager.getActiveSubscriptionInfoList();
            if (list != null) subscriptions.addAll(list);
        } catch (SecurityException ignored) { }
        if (subscriptions.isEmpty()) {
            headline.setText("Нет активной SIM");
            reason.setText("Вставь SIM или включи eSIM и открой приложение снова.");
            return;
        }
        int defaultIndex = 0;
        for (int i = 0; i < subscriptions.size(); i++) {
            SubscriptionInfo info = subscriptions.get(i);
            if (info.getSubscriptionId() == SubscriptionManager.getDefaultDataSubscriptionId()) defaultIndex = i;
        }
        // Full-width buttons make the selection visible on small screens.
        simChoices.removeAllViews();
        for (int i = 0; i < subscriptions.size(); i++) {
            final int index = i;
            SubscriptionInfo info = subscriptions.get(i);
            Button button = button(simChoices, "SIM " + (info.getSimSlotIndex() + 1) + " · " + info.getCarrierName());
            button.setAllCaps(false);
            button.setOnClickListener(v -> selectSim(index));
        }
        selectSim(defaultIndex);
        if (!refreshScheduled) { refreshScheduled = true; handler.post(refresh); }
    }

    private void selectSim(int index) {
        if (index < 0 || index >= subscriptions.size()) return;
        selectedIndex = index;
        selectedSubscriptionId = subscriptions.get(index).getSubscriptionId();
        if (telephony != null && callback != null) telephony.unregisterTelephonyCallback(callback);
        telephony = getSystemService(TelephonyManager.class).createForSubscriptionId(selectedSubscriptionId);
        carrier = String.valueOf(subscriptions.get(index).getCarrierName());
        for (int i = 0; i < simChoices.getChildCount(); i++) {
            Button button = (Button) simChoices.getChildAt(i);
            styleButton(button, i == index ? ACCENT : SURFACE, i == index ? GRAPHITE : TEXT, 18);
        }
        rsrp = rsrq = snr = band = cid = UNKNOWN;
        technology = "—";
        updatedAt = 0;
        callback = new RadioCallback(selectedSubscriptionId);
        try {
            telephony.registerTelephonyCallback(getMainExecutor(), callback);
            List<CellInfo> cached = telephony.getAllCellInfo();
            if (cached != null) acceptCells(cached);
        } catch (SecurityException ignored) { }
        renderPoints();
        show();
    }

    private final class RadioCallback extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener, TelephonyCallback.CellInfoListener {
        private final int subscription;
        RadioCallback(int subscription) { this.subscription = subscription; }
        @Override public void onSignalStrengthsChanged(SignalStrength value) {
            if (subscription != selectedSubscriptionId) return;
            if ("LTE".equals(technology) || "—".equals(technology)) {
                List<CellSignalStrengthLte> lte = value.getCellSignalStrengths(CellSignalStrengthLte.class);
                if (!lte.isEmpty()) {
                    CellSignalStrengthLte s = lte.get(0);
                    rsrp = s.getRsrp(); rsrq = s.getRsrq(); snr = s.getRssnr();
                    if ("—".equals(technology)) technology = "LTE";
                    updatedAt = SystemClock.elapsedRealtime();
                    show();
                }
            }
        }
        @Override public void onCellInfoChanged(List<CellInfo> cells) {
            if (subscription == selectedSubscriptionId) acceptCells(cells);
        }
    }

    private void acceptCells(List<CellInfo> cells) {
        if (cells == null) return;
        CellInfo serving = null;
        for (CellInfo cell : cells) {
            if (cell.isRegistered() && cell instanceof CellInfoLte) { serving = cell; break; }
            if (cell.isRegistered() && serving == null) serving = cell;
        }
        if (serving == null) return;
        if (serving instanceof CellInfoLte) {
            CellInfoLte lte = (CellInfoLte) serving;
            CellSignalStrengthLte s = lte.getCellSignalStrength();
            CellIdentityLte identity = lte.getCellIdentity();
            technology = "LTE";
            rsrp = s.getRsrp(); rsrq = s.getRsrq(); snr = s.getRssnr();
            int[] bands = identity.getBands();
            band = bands.length > 0 ? bands[0] : UNKNOWN;
            cid = identity.getCi();
        } else {
            technology = serving.getClass().getSimpleName().replace("CellInfo", "");
            rsrp = rsrq = snr = band = cid = UNKNOWN;
        }
        updatedAt = SystemClock.elapsedRealtime();
        show();
    }

    private static boolean known(int value) { return value != UNKNOWN; }
    private static String number(int value, String unit) { return known(value) ? value + " " + unit : "нет данных"; }
    private static final String[] GRADES = {"Очень плохо", "Плохо", "Посредственно", "Хорошо", "Отлично"};
    private static final int[] GRADE_COLORS = {0xFFE99084, 0xFFE5AB7C, 0xFFC6C6C2, 0xFFDEDEDA, 0xFFF5F5F3};

    private static int grade(int r, int q, int s) {
        int result = r < -120 ? 0 : r < -110 ? 1 : r < -100 ? 2 : r < -90 ? 3 : 4;
        if (known(s) && s < 0) result = Math.min(result, 0);
        else if (known(s) && s < 5) result = Math.min(result, 1);
        if (known(q) && q < -17) result = Math.min(result, 1);
        return result;
    }

    private void show() {
        if (headline == null || !hasPermissions()) return;
        long age = updatedAt == 0 ? Long.MAX_VALUE : (SystemClock.elapsedRealtime() - updatedAt) / 1000;
        if (updatedAt == 0) {
            headline.setText("Ожидаю сеть"); headline.setTextColor(TEXT);
            reason.setText("Включи мобильную связь и геолокацию; проверь выбранную SIM.");
        } else if (age > 15) {
            headline.setText("Данные устарели"); headline.setTextColor(GRADE_COLORS[2]);
            reason.setText("Последнее обновление было более 15 секунд назад. Подожди, прежде чем сравнивать точки.");
        } else if (!"LTE".equals(technology)) {
            headline.setText("Сейчас " + technology); headline.setTextColor(GRADE_COLORS[2]);
            reason.setText("Для поиска точки под 4G сравнивай измерения LTE, а не полоски другой сети.");
        } else if (!known(rsrp)) {
            headline.setText("Нет силы сигнала"); headline.setTextColor(GRADE_COLORS[2]);
            reason.setText("Телефон пока не передал численные данные LTE.");
        } else {
            int grade = grade(rsrp, rsrq, snr);
            String why = rsrp < -120 ? "Очень слабый LTE-сигнал" : rsrp < -110 ? "Слабый LTE-сигнал" :
                    rsrp < -100 ? "Сигнал пограничный" : "По силе сигнал хороший";
            if (known(snr) && snr < 0) why = "Сильные помехи относительно сигнала";
            else if (known(snr) && snr < 5 && rsrp >= -110) why = "Мало запаса по помехам";
            if (known(rsrq) && rsrq < -17 && rsrp >= -110) why = "Плохое качество принятого сигнала";
            headline.setText(GRADES[grade]); headline.setTextColor(GRADE_COLORS[grade]);
            reason.setText(why + ". Это оценка радиосигнала, не скорости интернета.");
        }
        numbers.setText(carrier + " · " + technology + (known(band) ? " · B" + band : "") +
                "\nСила (RSRP): " + number(rsrp, "dBm") +
                "\nПомехи (SNR): " + number(snr, "dB") +
                "\nКачество (RSRQ): " + number(rsrq, "dB"));
        freshness.setText(updatedAt == 0 ? "Нет измерений" : "Последнее измерение: " + age + " с назад" +
                (known(cid) ? " · Сота " + cid : ""));
        saveButton.setEnabled(age <= 15 && "LTE".equals(technology) && known(rsrp));
        saveButton.setAlpha(saveButton.isEnabled() ? 1f : 0.48f);
        SavedPoint latest = latestPoint();
        comparison.setText(latest == null ? "Сохрани исходную точку для сравнения." : compare(latest));
    }

    private String compare(SavedPoint old) {
        String baseline = old.name + ": " + old.carrier + " B" + (known(old.band) ? old.band : "?") +
                ", " + number(old.rsrp, "dBm") + ", SNR " + number(old.snr, "dB") + ".\n";
        if (!"LTE".equals(technology) || !known(rsrp) || updatedAt == 0 || SystemClock.elapsedRealtime() - updatedAt > 15000)
            return baseline + "Жду свежего измерения LTE.";
        if (!old.carrier.equals(carrier)) return baseline + "Сейчас другая SIM/сеть: сравнение некорректно.";
        int dr = rsrp - old.rsrp;
        if (!known(old.snr) || !known(snr)) return baseline + (dr >= 4 ? "Сигнал сильнее, но SNR неизвестен." : dr <= -4 ? "Сигнал слабее, но SNR неизвестен." : "Сила сигнала почти та же; SNR неизвестен.");
        int ds = snr - old.snr;
        String verdict;
        if (dr >= 4 && ds >= -2 || ds >= 4 && dr >= -2) verdict = "Лучше";
        else if (dr <= -4 && ds <= 2 || ds <= -4 && dr <= 2) verdict = "Хуже";
        else if (Math.abs(dr) < 4 && Math.abs(ds) < 4) verdict = "Почти без изменений";
        else verdict = "Смешанный результат";
        String differentCell = old.band != band || old.cid != cid ? " Сота или диапазон сменились." : "";
        return baseline + verdict + ": сила " + signed(dr) + " dB, SNR " + signed(ds) + " dB." + differentCell;
    }

    private static String signed(int value) { return value > 0 ? "+" + value : String.valueOf(value); }

    private static class SavedPoint {
        final int subscription, order, grade;
        final int rsrp, rsrq, snr, band, cid;
        final String carrier;
        String name;
        SavedPoint(int subscription, String carrier, int order, String name, int r, int q, int s, int b, int c, int grade) {
            this.subscription = subscription; this.order = order; this.grade = grade;
            rsrp = r; rsrq = q; snr = s; band = b; cid = c; this.carrier = carrier;
            this.name = name;
        }
    }

    private void editName(SavedPoint point, boolean newPoint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(point.name);
        input.setSelection(input.length());
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(24), dp(8), dp(24), 0);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(newPoint ? "Название точки" : "Переименовать точку")
                .setView(content)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(newPoint ? "Сохранить" : "Готово", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("Введи название"); return; }
            point.name = name;
            if (newPoint) points.add(point);
            persistPoints(); renderPoints(); show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void confirmDelete(SavedPoint point) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить «" + point.name + "»?")
                .setMessage("Точка исчезнет из списка и с графика.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    points.remove(point);
                    persistPoints(); renderPoints(); show();
                }).show();
    }

    private SavedPoint latestPoint() {
        for (int i = points.size() - 1; i >= 0; i--)
            if (points.get(i).subscription == selectedSubscriptionId) return points.get(i);
        return null;
    }

    private List<SavedPoint> selectedPoints() {
        List<SavedPoint> result = new ArrayList<>();
        for (SavedPoint point : points)
            if (point.subscription == selectedSubscriptionId) result.add(point);
        return result;
    }

    private void renderPoints() {
        if (chart == null || pointList == null) return;
        List<SavedPoint> selected = selectedPoints();
        chart.setPoints(selected);
        chart.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                Math.max(dp(300), dp(84) * selected.size() + dp(24)), dp(220)));
        pointList.removeAllViews();
        if (selected.isEmpty()) {
            label(pointList, "Пока нет точек для этой SIM.", 15, SUBTLE, false);
            return;
        }
        for (int i = selected.size() - 1; i >= 0; i--) {
            SavedPoint p = selected.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(8), dp(14), dp(14));
            card.setBackground(rounded(SURFACE, 20));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.topMargin = dp(10);
            pointList.addView(card, cardParams);
            label(card, p.name + " · " + GRADES[p.grade], 17, GRADE_COLORS[p.grade], true);
            label(card, p.rsrp + " dBm · SNR " + number(p.snr, "dB") +
                    (known(p.band) ? " · B" + p.band : ""), 14, SUBTLE, false);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
            Button edit = new Button(this);
            edit.setText("Название");
            styleButton(edit, 0xFF464A46, TEXT, 14);
            edit.setOnClickListener(v -> editName(p, false));
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, -1, 1);
            editParams.rightMargin = dp(8);
            actions.addView(edit, editParams);
            Button delete = new Button(this);
            delete.setText("Удалить");
            styleButton(delete, 0xFF493838, 0xFFF0B1A9, 14);
            delete.setOnClickListener(v -> confirmDelete(p));
            actions.addView(delete, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private final class PointsChart extends LinearLayout {
        PointsChart() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.BOTTOM);
            setPadding(dp(12), dp(12), dp(12), dp(12));
        }

        void setPoints(List<SavedPoint> values) {
            removeAllViews();
            if (values.isEmpty()) {
                TextView empty = chartText("Сохрани точку, чтобы увидеть сравнение", 15, SUBTLE);
                addView(empty, new LinearLayout.LayoutParams(-1, -1));
                return;
            }
            for (SavedPoint point : values) {
                LinearLayout column = new LinearLayout(MainActivity.this);
                column.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                column.setOrientation(VERTICAL);
                LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(dp(84), -1);
                addView(column, columnParams);

                TextView score = chartText(point.grade + " / 4", 14, TEXT);
                column.addView(score, new LinearLayout.LayoutParams(-1, dp(26)));
                column.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(1, 0, 1));
                View bar = new View(MainActivity.this);
                bar.setBackground(rounded(GRADE_COLORS[point.grade], 7));
                LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(46), dp(22 + point.grade * 23));
                column.addView(bar, barParams);
                TextView name = chartText(shortName(point.name), 12, TEXT);
                column.addView(name, new LinearLayout.LayoutParams(-1, dp(32)));
            }
        }
    }

    private TextView chartText(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(sp);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return text;
    }

    private static String shortName(String name) {
        if (name.codePointCount(0, name.length()) <= 8) return name;
        return name.substring(0, name.offsetByCodePoints(0, 7)) + "…";
    }

    private void persistPoints() {
        JSONArray array = new JSONArray();
        for (SavedPoint p : points) {
            JSONObject json = new JSONObject();
            try {
                json.put("subscription", p.subscription); json.put("carrier", p.carrier); json.put("name", p.name);
                json.put("order", p.order); json.put("rsrp", p.rsrp); json.put("rsrq", p.rsrq);
                json.put("snr", p.snr); json.put("band", p.band); json.put("cid", p.cid);
                json.put("grade", p.grade); array.put(json);
            } catch (org.json.JSONException ignored) { }
        }
        getPreferences(MODE_PRIVATE).edit().putString("points", array.toString()).apply();
    }

    private void restorePoints() {
        try {
            JSONArray array = new JSONArray(getPreferences(MODE_PRIVATE).getString("points", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject p = array.getJSONObject(i);
                int order = p.getInt("order");
                points.add(new SavedPoint(p.getInt("subscription"), p.getString("carrier"), order,
                        p.optString("name", "Точка " + order),
                        p.getInt("rsrp"), p.getInt("rsrq"), p.getInt("snr"), p.getInt("band"),
                        p.getInt("cid"), p.getInt("grade")));
            }
        } catch (org.json.JSONException ignored) { points.clear(); }
    }

    private void runSpeedTest() {
        if (testing) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            speed.setText("Для теста отключи Wi-Fi и VPN и включи мобильные данные. Иначе измерится другой канал.");
            return;
        }
        if (selectedSubscriptionId != SubscriptionManager.getDefaultDataSubscriptionId()) {
            speed.setText("Для теста скорости выбери SIM, назначенную в Android для мобильного интернета.");
            return;
        }
        testing = true; testButton.setEnabled(false);
        downloadedBytes.set(0); downloadStartedAt = 0;
        speed.setText("Подключаюсь к тестовому серверу…");
        handler.post(speedTicker);
        new Thread(() -> {
            HttpURLConnection connection = null;
            String result;
            try {
                URL url = new URL("https://speed.cloudflare.com/__down?bytes=" + TEST_BYTES + "&seed=" + SystemClock.elapsedRealtime());
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(12000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");
                int status = connection.getResponseCode();
                if (status != 200) throw new Exception("сервер вернул HTTP " + status);
                long total = 0;
                downloadStartedAt = SystemClock.elapsedRealtime();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        total += n;
                        downloadedBytes.set(total);
                    }
                }
                if (total < 1048576) throw new Exception("ответ слишком короткий (" + total + " байт)");
                double mbps = total * 8.0 / Math.max(1, SystemClock.elapsedRealtime() - downloadStartedAt) / 1000.0;
                result = String.format(Locale.forLanguageTag("ru"), "Готово: %.1f Мбит/с · %.1f МБ. Это средняя скорость одного теста.", mbps, total / 1048576.0);
            } catch (Exception e) {
                result = "Скорость не измерена: " + e.getMessage() + ". Сервер может быть недоступен из-за ограничений сети.";
            } finally { if (connection != null) connection.disconnect(); }
            final String text = result;
            runOnUiThread(() -> {
                testing = false;
                handler.removeCallbacks(speedTicker);
                speed.setText(text);
                testButton.setEnabled(true);
            });
        }).start();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(speedTicker);
        refreshScheduled = false;
        if (telephony != null && callback != null) telephony.unregisterTelephonyCallback(callback);
        super.onDestroy();
    }
}
