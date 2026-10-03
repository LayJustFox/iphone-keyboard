package com.iphonekb;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Setup, every setting, and a live preview of the keyboard at the top. */
public final class SettingsActivity extends Activity implements KeyboardView.Listener {

    private Prefs prefs;
    private TextView status;
    private Button enableBtn, selectBtn;
    private boolean dark;
    private KeyboardView preview;
    private int previewMode = Layouts.LETTERS;
    private int previewShift = KeyboardView.SHIFT_OFF;
    private final ArrayList<CheckBox> langBoxes = new ArrayList<>();
    private final ArrayList<Runnable> sizeRefreshers = new ArrayList<>();
    private LinearLayout accentRow;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(pageBg());

        // Live preview pinned at the top
        preview = new KeyboardView(this, this, prefs);
        outer.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(32));
        scroll.addView(col);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildSetup(col);
        buildStyle(col);
        buildSize(col);
        buildAnimation(col);
        buildFeedback(col);
        buildTyping(col);
        buildLanguages(col);
        buildClipboard(col);
        buildTry(col);

        setContentView(outer);
        refreshPreview();
    }

    // ---------------------------------------------------------------- sections

    private void buildSetup(LinearLayout col) {
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(textColor());
        status.setPadding(dp(4), dp(12), dp(4), dp(4));
        col.addView(status);

        LinearLayout setup = card(col, "Установка");
        enableBtn = button(setup, "1. Включить клавиатуру", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });
        selectBtn = button(setup, "2. Выбрать её как основную", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }
        });
        note(setup, "Android предупредит, что клавиатура может собирать вводимый текст. "
                + "Это стандартное сообщение для любой сторонней клавиатуры. У этой клавиатуры "
                + "нет разрешения на интернет, поэтому она ничего никуда не отправляет.");
    }

    private void buildStyle(LinearLayout col) {
        LinearLayout c = card(col, "Внешний вид");
        segmented(c, "Стиль", new String[]{"Liquid Glass", "Классический iOS"},
                new String[]{Prefs.STYLE_GLASS, Prefs.STYLE_CLASSIC}, prefs.style, "style");
        segmented(c, "Тема", new String[]{"Как в системе", "Светлая", "Тёмная"},
                new String[]{Prefs.THEME_AUTO, Prefs.THEME_LIGHT, Prefs.THEME_DARK}, prefs.theme, "theme");

        label(c, "Цвет акцента");
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        accentRow = new LinearLayout(this);
        accentRow.setOrientation(LinearLayout.HORIZONTAL);
        accentRow.setPadding(0, dp(4), 0, dp(10));
        hs.addView(accentRow);
        c.addView(hs);
        buildAccents();

        boolean blurSupported = false;
        if (Build.VERSION.SDK_INT >= 31) {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            blurSupported = wm != null && wm.isCrossWindowBlurEnabled();
        }
        toggle(c, "Настоящее размытие фона", "blur", prefs.blur);
        slider(c, "Прозрачность стекла", "glass_opacity", 40, 100, prefs.glassOpacity, "%", null);
        note(c, blurSupported
                ? "Размытие показывает приложение под клавиатурой через стекло, как на iPhone. "
                + "Работает только в стиле Liquid Glass. Если размывается весь экран — выключите."
                : "Размытие фона требует Android 12 или новее и поддержки телефоном. "
                + "Без него стекло рисуется на светлой подложке.");
    }

    private void buildAccents() {
        accentRow.removeAllViews();
        for (final String[] a : Prefs.ACCENTS) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.parseColor(dark ? a[2] : a[1]));
            if (a[0].equals(prefs.accent)) g.setStroke(dp(3), dark ? Color.WHITE : 0xFF3A3A3C);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.setMargins(0, 0, dp(12), 0);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setString("accent", a[0]);
                    buildAccents();
                    refreshPreview();
                }
            });
            accentRow.addView(dot, lp);
        }
    }

    private void buildSize(LinearLayout col) {
        LinearLayout c = card(col, "Размер клавиатуры");
        segmented(c, "Как у iPhone", new String[]{"Авто", "SE", "iPhone", "Pro Max"},
                new String[]{Prefs.SIZE_AUTO, Prefs.SIZE_SE, Prefs.SIZE_IPHONE, Prefs.SIZE_MAX},
                prefs.sizePreset, "size_preset");
        segmented(c, "Другие", new String[]{"Компактный", "Большой"},
                new String[]{Prefs.SIZE_COMPACT, Prefs.SIZE_LARGE}, prefs.sizePreset, "size_preset");
        note(c, "«Авто» подбирает размер как у iPhone под ширину экрана (клавиши 42 или 45), "
                + "учитывает горизонтальный режим и планшеты.");
        slider(c, "Высота клавиш", "key_height", 60, 150, prefs.keyHeightPct, "%", null);
        slider(c, "Размер букв", "font", 60, 150, prefs.fontPct, "%", null);
        slider(c, "Промежутки между клавишами", "hgap", 30, 200, prefs.hGapPct, "%", null);
        slider(c, "Промежутки между рядами", "vgap", 30, 200, prefs.vGapPct, "%", null);
        slider(c, "Скругление углов", "radius", 0, 300, prefs.radiusPct, "%", null);
        slider(c, "Отступы по бокам", "side_pad", 0, 24, prefs.sidePadDp, " dp", null);
        slider(c, "Отступ снизу", "bottom_pad", 0, 40, prefs.bottomPadDp, " dp", null);
        toggle(c, "Строка с 🌐 под клавишами (как на iPhone)", "globe_row", prefs.globeRow);
        toggle(c, "Ряд цифр над буквами", "number_row", prefs.numberRow);
        toggle(c, "Оптимизация размера (не выше 45% экрана)", "limit_height", prefs.limitHeight);
        button(c, "Сбросить размеры", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.resetSizes();
                recreate();
            }
        });
    }

    private void buildAnimation(LinearLayout col) {
        LinearLayout c = card(col, "Анимации");
        segmentedInt(c, "Скорость", new String[]{"Плавные", "Быстрые", "Без анимаций"},
                new int[]{Prefs.ANIM_SMOOTH, Prefs.ANIM_FAST, Prefs.ANIM_OFF}, prefs.animSpeed, "anim_speed");
        toggle(c, "Увеличенная буква при нажатии", "popups", prefs.popups);
        toggle(c, "Эффект «жидкого стекла» под пальцем", "liquid_touch", prefs.liquidTouch);
    }

    private void buildFeedback(LinearLayout col) {
        LinearLayout c = card(col, "Звук и вибрация");
        toggle(c, "Звук клавиш", "sound", prefs.sound);
        slider(c, "Громкость", "sound_volume", 0, 100, prefs.soundVolume, "%", null);
        segmentedInt(c, "Вибрация", new String[]{"Выкл", "Слабая", "Средняя", "Сильная"},
                new int[]{0, 1, 2, 3}, prefs.haptic, "haptic");
    }

    private void buildTyping(LinearLayout col) {
        LinearLayout c = card(col, "Ввод");
        toggle(c, "Автопрописные", "autocap", prefs.autoCap);
        toggle(c, "Быстрая клавиша «.» (два пробела)", "double_space", prefs.doubleSpace);
        toggle(c, "Подсказки слов", "suggestions", prefs.suggestions);
        toggle(c, "Пробел как трекпад", "trackpad", prefs.trackpad);
        slider(c, "Задержка удержания", "long_press", 200, 700, prefs.longPressMs, " мс", null);
        button(c, "Забыть запомненные слова", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.bumpDictGeneration();
                Toast.makeText(SettingsActivity.this, "Словарь будет очищен", Toast.LENGTH_SHORT).show();
            }
        });
        note(c, "Слова запоминаются только на этом телефоне и не запоминаются "
                + "в полях паролей и в режиме инкогнито.");
    }

    private void buildLanguages(LinearLayout col) {
        LinearLayout langs = card(col, "Языки (🌐 переключает)");
        for (final String l : Layouts.ALL_LANGS) {
            CheckBox cb = new CheckBox(this);
            cb.setText(Layouts.settingsName(l));
            cb.setTextSize(16);
            cb.setTextColor(textColor());
            cb.setChecked(prefs.hasLang(l));
            cb.setTag(l);
            cb.setPadding(dp(6), dp(10), 0, dp(10));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    saveLangs(b);
                }
            });
            langs.addView(cb);
            langBoxes.add(cb);
        }
        note(langs, "Японский: печатайте ромадзи (konnichiha → こんにちは). Пробел переключает "
                + "хирагану, катакану и латиницу, клавиша 確定 подтверждает. Кандзи нет.");
    }

    private void buildClipboard(LinearLayout col) {
        LinearLayout c = card(col, "Буфер обмена");
        toggle(c, "Сохранять историю буфера", "clipboard", prefs.clipboard);
        String[] labels = new String[Prefs.CLIP_SIZES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = String.format("%,d", Prefs.CLIP_SIZES[i]).replace(',', ' ');
        segmentedInt(c, "Сколько хранить", labels, Prefs.CLIP_SIZES, prefs.clipMax, "clip_max");
        button(c, "Очистить историю (закреплённое останется)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.bumpClipGeneration();
                Toast.makeText(SettingsActivity.this, "История будет очищена", Toast.LENGTH_SHORT).show();
            }
        });
        note(c, "Кнопка 📋 слева над клавиатурой открывает историю: нажмите, чтобы вставить, "
                + "удерживайте, чтобы закрепить или удалить, 🔍 — поиск. Только что скопированное "
                + "появляется над клавиатурой для быстрой вставки. История хранится только на "
                + "телефоне; пароли, помеченные приложениями как секретные, не сохраняются.");
    }

    private void buildTry(LinearLayout col) {
        LinearLayout tryIt = card(col, "Попробовать");
        EditText et = new EditText(this);
        et.setHint("Нажмите сюда и печатайте");
        et.setTextColor(textColor());
        et.setHintTextColor(0xFF8E8E93);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        tryIt.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- preview

    private void refreshPreview() {
        prefs.reload();
        preview.applyPrefs(prefs);
        preview.setTheme(Theme.from(prefs, dark, false));
        String lang = prefs.currentLang;
        preview.setLocale(Layouts.locale(lang));
        preview.setLayout(Layouts.get(lang, previewMode, prefs.numberRow, !prefs.globeRow),
                previewMode == Layouts.LETTERS);
        preview.setShift(previewShift);
        preview.setSpaceLabel(Layouts.space(lang));
        preview.setReturn(Layouts.returnLabel(lang, Layouts.RET), false);
        preview.setSuggestions(null);
    }

    // The preview reacts to touches like the real keyboard but types nowhere.
    @Override public void onFeedback(int keyType) { }
    @Override public void onText(String text) {
        if (previewShift == KeyboardView.SHIFT_ON) {
            previewShift = KeyboardView.SHIFT_OFF;
            preview.setShift(previewShift);
        }
    }
    @Override public void onShift() {
        previewShift = previewShift == KeyboardView.SHIFT_OFF ? KeyboardView.SHIFT_ON : KeyboardView.SHIFT_OFF;
        preview.setShift(previewShift);
    }
    @Override public void onDelete(boolean word) { }
    @Override public void onModeKey() {
        previewMode = previewMode == Layouts.LETTERS ? Layouts.SYM1 : Layouts.LETTERS;
        refreshPreview();
    }
    @Override public void onMoreKey() {
        previewMode = previewMode == Layouts.SYM1 ? Layouts.SYM2 : Layouts.SYM1;
        refreshPreview();
    }
    @Override public void onSlideReturn() {
        previewMode = Layouts.LETTERS;
        refreshPreview();
    }
    @Override public void onSpace() { }
    @Override public void onReturn() { }
    @Override public void onEmoji() { }
    @Override public void onGlobe() {
        String[] l = prefs.langs;
        int idx = 0;
        for (int i = 0; i < l.length; i++) if (l[i].equals(prefs.currentLang)) idx = i;
        prefs.setCurrentLang(l[(idx + 1) % l.length]);
        previewMode = Layouts.LETTERS;
        refreshPreview();
        preview.flashLanguage(Layouts.name(prefs.currentLang));
    }
    @Override public void onGlobeLong() { }
    @Override public void onCursor(int dx, int dy) { }
    @Override public void onSuggestion(int index) { }
    @Override public void onClipboard() { }
    @Override public void onSettings() { }
    @Override public void onQuickPaste() { }

    // ---------------------------------------------------------------- status

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateStatus(); // after the keyboard picker closes
    }

    private void updateStatus() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        boolean enabled = false;
        if (imm != null) {
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (getPackageName().equals(info.getPackageName())) enabled = true;
            }
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");

        if (selected) status.setText("✅ Клавиатура включена и выбрана. Сверху — живой предпросмотр.");
        else if (enabled) status.setText("Почти готово: нажмите «Выбрать её как основную».");
        else status.setText("Чтобы начать, нажмите «Включить клавиатуру» и включите «iPhone Клавиатура».");

        enableBtn.setAlpha(enabled ? 0.5f : 1f);
        selectBtn.setAlpha(selected ? 0.5f : 1f);
    }

    private void saveLangs(CompoundButton changed) {
        List<String> chosen = new ArrayList<>();
        for (CheckBox cb : langBoxes) if (cb.isChecked()) chosen.add((String) cb.getTag());
        if (chosen.isEmpty()) {
            changed.setChecked(true);
            Toast.makeText(this, "Нужен хотя бы один язык", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setLangs(chosen);
        refreshPreview();
    }

    // ---------------------------------------------------------------- small UI helpers

    private int pageBg() {
        return dark ? Color.BLACK : 0xFFF2F2F7;
    }

    private int textColor() {
        return dark ? Color.WHITE : Color.BLACK;
    }

    private int accent() {
        return prefs.accentColor(dark);
    }

    private LinearLayout card(LinearLayout parent, String title) {
        TextView t = new TextView(this);
        t.setText(title.toUpperCase());
        t.setTextSize(13);
        t.setTextColor(0xFF8E8E93);
        t.setPadding(dp(14), dp(18), dp(14), dp(6));
        parent.addView(t);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF1C1C1E : Color.WHITE);
        bg.setCornerRadius(dp(12));
        box.setBackground(bg);
        parent.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void label(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(textColor());
        t.setPadding(dp(2), dp(12), dp(2), dp(4));
        parent.addView(t);
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent());
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.setMargins(0, dp(6), 0, dp(6));
        parent.addView(b, lp);
        return b;
    }

    private void toggle(LinearLayout parent, String text, final String key, boolean value) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextSize(15);
        s.setTextColor(textColor());
        s.setChecked(value);
        s.setPadding(dp(2), dp(11), dp(2), dp(11));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setBool(key, checked);
                refreshPreview();
            }
        });
        parent.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void slider(LinearLayout parent, final String title, final String key, final int min, int max,
                        int value, final String unit, Runnable after) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(10), dp(2), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(textColor());
        final TextView v = new TextView(this);
        v.setTextSize(15);
        v.setTextColor(0xFF8E8E93);
        v.setText(value + unit);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v);
        parent.addView(row);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                int val = progress + min;
                v.setText(val + unit);
                prefs.setInt(key, val);
                refreshPreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        parent.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** iOS-style segmented control writing a string setting. */
    private void segmented(LinearLayout parent, String title, String[] labels, final String[] values,
                           String current, final String key) {
        label(parent, title);
        final LinearLayout row = segmentRow(parent);
        for (int i = 0; i < labels.length; i++) {
            final String val = values[i];
            TextView seg = segment(row, labels[i], val.equals(current));
            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setString(key, val);
                    if ("size_preset".equals(key)) {
                        recreate(); // two rows share this setting
                        return;
                    }
                    selectSegment(row, v);
                    refreshPreview();
                }
            });
        }
    }

    private void segmentedInt(LinearLayout parent, String title, String[] labels, final int[] values,
                              int current, final String key) {
        label(parent, title);
        final LinearLayout row = segmentRow(parent);
        for (int i = 0; i < labels.length; i++) {
            final int val = values[i];
            TextView seg = segment(row, labels[i], val == current);
            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setInt(key, val);
                    selectSegment(row, v);
                    refreshPreview();
                }
            });
        }
    }

    private LinearLayout segmentRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(2), dp(2), dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF2C2C2E : 0xFFE9E9EB);
        bg.setCornerRadius(dp(9));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(0, dp(2), 0, dp(8));
        parent.addView(row, lp);
        return row;
    }

    private TextView segment(LinearLayout row, String text, boolean selected) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        styleSegment(t, selected);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return t;
    }

    private void styleSegment(TextView t, boolean selected) {
        if (selected) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(dark ? 0xFF636366 : Color.WHITE);
            g.setCornerRadius(dp(7));
            t.setBackground(g);
            t.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            t.setBackground(null);
            t.setTypeface(Typeface.DEFAULT);
        }
        t.setTextColor(textColor());
    }

    private void selectSegment(LinearLayout row, View chosen) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            styleSegment((TextView) v, v == chosen);
        }
    }

    private void note(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xFF8E8E93);
        t.setPadding(dp(2), dp(6), dp(2), dp(8));
        parent.addView(t);
    }
}
