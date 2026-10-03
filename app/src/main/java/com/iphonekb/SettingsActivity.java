package com.iphonekb;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Setup and settings screen (opened from the app icon). */
public final class SettingsActivity extends Activity {

    private Prefs prefs;
    private TextView status;
    private Button enableBtn, selectBtn;
    private boolean dark;
    private final ArrayList<CheckBox> langBoxes = new ArrayList<>();

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(dark ? Color.BLACK : 0xFFF2F2F7);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(col);

        // ---- Setup
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(textColor());
        status.setPadding(dp(4), dp(8), dp(4), dp(12));
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

        // ---- Languages
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

        // ---- Feedback
        LinearLayout fb = card(col, "Звук и вибрация");
        toggle(fb, "Звук клавиш", "sound", prefs.sound);
        toggle(fb, "Вибрация", "vibrate", prefs.vibrate);

        // ---- Typing
        LinearLayout typing = card(col, "Ввод");
        toggle(typing, "Автопрописные", "autocap", prefs.autoCap);
        toggle(typing, "Быстрая клавиша «.» (два пробела)", "double_space", prefs.doubleSpace);
        toggle(typing, "Подсказки слов", "suggestions", prefs.suggestions);
        button(typing, "Забыть запомненные слова", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.bumpDictGeneration();
                Toast.makeText(SettingsActivity.this, "Словарь будет очищен", Toast.LENGTH_SHORT).show();
            }
        });
        note(typing, "Слова запоминаются только на этом телефоне и не запоминаются "
                + "в полях паролей и в режиме инкогнито.");

        // ---- Theme
        LinearLayout th = card(col, "Тема");
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);
        String[][] opts = {
                {Prefs.THEME_AUTO, "Как в системе"},
                {Prefs.THEME_LIGHT, "Светлая"},
                {Prefs.THEME_DARK, "Тёмная"},
        };
        for (String[] o : opts) {
            RadioButton rb = new RadioButton(this);
            rb.setText(o[1]);
            rb.setTag(o[0]);
            rb.setTextSize(16);
            rb.setTextColor(textColor());
            rb.setId(View.generateViewId());
            rb.setPadding(dp(6), dp(10), 0, dp(10));
            rg.addView(rb);
            if (o[0].equals(prefs.theme)) rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                View v = group.findViewById(checkedId);
                if (v != null) prefs.setTheme((String) v.getTag());
            }
        });
        th.addView(rg);

        // ---- Try it
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

        setContentView(scroll);
    }

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

        if (selected) status.setText("✅ Клавиатура включена и выбрана. Можно печатать!");
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
    }

    // ---------------------------------------------------------------- small UI helpers

    private int textColor() {
        return dark ? Color.WHITE : Color.BLACK;
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

    private Button button(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF0A84FF : 0xFF007AFF);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(6), 0, dp(6));
        parent.addView(b, lp);
        return b;
    }

    private void toggle(LinearLayout parent, String text, final String key, boolean value) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextSize(16);
        s.setTextColor(textColor());
        s.setChecked(value);
        s.setPadding(dp(4), dp(12), dp(4), dp(12));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setBool(key, checked);
            }
        });
        parent.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void note(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xFF8E8E93);
        t.setTypeface(Typeface.DEFAULT);
        t.setPadding(dp(2), dp(6), dp(2), dp(8));
        parent.addView(t);
    }
}
