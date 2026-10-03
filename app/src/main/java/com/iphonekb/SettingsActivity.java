package com.iphonekb;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings laid out like the iPhone Settings app: a large title, grouped rounded lists with
 * coloured icons and chevrons, sub-pages that slide in from the right, checkmark choices,
 * green switches – and a live keyboard preview docked at the bottom on the look pages.
 */
public final class SettingsActivity extends Activity implements KeyboardView.Listener {

    private Prefs prefs;
    private boolean dark;
    private FrameLayout host;
    private final ArrayList<View> stack = new ArrayList<>();
    private KeyboardView preview;
    private Backdrop backdrop;
    private boolean blurSupported;
    private int previewMode = Layouts.LETTERS;
    private int previewShift = KeyboardView.SHIFT_OFF;

    private TextView status;
    private TextView vStyle, vSize, vAnim, vSound, vLangs, vClip, vTrans;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ---------------------------------------------------------------- colours (iOS system)

    private int pageBg() {
        return dark ? Color.BLACK : 0xFFF2F2F7;
    }

    private int cellBg() {
        return dark ? 0xFF1C1C1E : Color.WHITE;
    }

    private int cellPressed() {
        return dark ? 0xFF2C2C2E : 0xFFD1D1D6;
    }

    private int label() {
        return dark ? Color.WHITE : Color.BLACK;
    }

    private int secondary() {
        return dark ? 0xFF8D8D93 : 0xFF8A8A8E;
    }

    private int separator() {
        return dark ? 0xFF38383A : 0xFFC6C6C8;
    }

    private int accent() {
        return prefs.accentColor(dark);
    }

    /** Accent for text (white accent becomes dark text on light pages). */
    private int accentInk() {
        int a = accent();
        return Theme.isLight(a) && !dark ? label() : a;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        getWindow().setStatusBarColor(pageBg());
        getWindow().setNavigationBarColor(pageBg());

        if (Build.VERSION.SDK_INT >= 31) {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            blurSupported = wm != null && wm.isCrossWindowBlurEnabled();
        }
        preview = new KeyboardView(this, this, prefs);
        backdrop = new Backdrop(this);
        backdrop.setDark(dark);
        host = new FrameLayout(this);
        host.setBackgroundColor(pageBg());
        setContentView(host);
        push(rootPage(), false);
        refreshPreview();
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

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (stack.size() > 1) pop();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- navigation (iOS push / pop)

    private void push(View page, boolean animate) {
        final View under = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        stack.add(page);
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (!animate || under == null) return;
        float w = host.getWidth() > 0 ? host.getWidth() : getResources().getDisplayMetrics().widthPixels;
        page.setTranslationX(w);
        page.animate().translationX(0).setDuration(460).setInterpolator(EmojiView.EASE_OUT).start();
        under.animate().translationX(-w * 0.3f).setDuration(460).setInterpolator(EmojiView.EASE_OUT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        under.setVisibility(View.INVISIBLE);
                    }
                }).start();
    }

    private void pop() {
        if (stack.size() < 2) return;
        final View top = stack.remove(stack.size() - 1);
        View under = stack.get(stack.size() - 1);
        float w = host.getWidth();
        under.setVisibility(View.VISIBLE);
        under.animate().setListener(null).translationX(0).alpha(1f).setDuration(420)
                .setInterpolator(EmojiView.EASE_OUT).start();
        top.animate().translationX(w).setDuration(420).setInterpolator(EmojiView.EASE_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        host.removeView(top);
                    }
                }).start();
        if (stack.size() == 1) refreshRootValues();
        // The preview may need to move back to the page underneath.
        dockPreview(under);
    }

    /** A page: optional iOS nav bar, scrolling grouped content, optional docked keyboard preview. */
    private View page(String title, LinearLayout content, boolean withPreview) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(pageBg());
        page.setClickable(true);

        if (title != null) {
            FrameLayout bar = new FrameLayout(this);
            TextView back = new TextView(this);
            back.setText("‹ Назад");
            back.setTextSize(17);
            back.setTextColor(accentInk());
            back.setGravity(Gravity.CENTER_VERTICAL);
            back.setPadding(dp(8), 0, dp(16), 0);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pop();
                }
            });
            bar.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(17);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(label());
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            bar.addView(t, tl);
            page.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout dock = new FrameLayout(this);
        dock.setTag("dock");
        page.addView(dock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.setTag(withPreview ? "preview" : null);
        if (withPreview) dockPreview(page);
        return page;
    }

    private void dockPreview(View page) {
        if (page == null || !"preview".equals(page.getTag())) return;
        FrameLayout dock = page.findViewWithTag("dock");
        if (dock == null || preview.getParent() == dock) return;
        if (preview.getParent() instanceof ViewGroup) ((ViewGroup) preview.getParent()).removeView(preview);
        if (backdrop.getParent() instanceof ViewGroup) ((ViewGroup) backdrop.getParent()).removeView(backdrop);
        // Moving "app content" behind the preview, blurred live like behind the real keyboard.
        dock.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        dock.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Soft fade-in instead of a hard first frame.
        dock.setAlpha(0f);
        dock.animate().alpha(1f).setStartDelay(120).setDuration(320).setInterpolator(EmojiView.EASE_OUT).start();
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(16), dp(36));
        return col;
    }

    // ---------------------------------------------------------------- pages

    private View rootPage() {
        LinearLayout col = column();
        TextView big = new TextView(this);
        big.setText("Клавиатура");
        big.setTextSize(34);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(label());
        big.setPadding(dp(4), dp(28), 0, dp(6));
        col.addView(big);

        LinearLayout setup = group(col, null);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(label());
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        addRow(setup, status, 16);
        action(setup, "Включить клавиатуру", accentInk(), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });
        action(setup, "Выбрать её как основную", accentInk(), new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }
        });
        footer(col, "Android покажет предупреждение о сборе текста — так он делает для любой "
                + "сторонней клавиатуры. Вводимый текст никуда не отправляется: интернет нужен "
                + "только переводчику, чтобы один раз скачать языки.");

        LinearLayout g = group(col, null);
        vStyle = nav(g, 0xFF007AFF, "🎨", "Внешний вид", new Runnable() {
            @Override
            public void run() {
                push(appearancePage(), true);
            }
        });
        vSize = nav(g, 0xFF5856D6, "📐", "Размер", new Runnable() {
            @Override
            public void run() {
                push(sizePage(), true);
            }
        });
        vAnim = nav(g, 0xFFFF9500, "✨", "Анимации", new Runnable() {
            @Override
            public void run() {
                push(animationPage(), true);
            }
        });
        vSound = nav(g, 0xFFFF2D55, "🔊", "Звуки и вибрация", new Runnable() {
            @Override
            public void run() {
                push(soundPage(), true);
            }
        });

        LinearLayout g2 = group(col, null);
        nav(g2, 0xFF8E8E93, "⌨️", "Ввод", new Runnable() {
            @Override
            public void run() {
                push(typingPage(), true);
            }
        });
        vLangs = nav(g2, 0xFF34C759, "🌐", "Языки", new Runnable() {
            @Override
            public void run() {
                push(languagesPage(), true);
            }
        });
        vClip = nav(g2, 0xFF30B0C7, "📋", "Буфер обмена", new Runnable() {
            @Override
            public void run() {
                push(clipboardPage(), true);
            }
        });
        vTrans = nav(g2, 0xFFFF9500, "🈯", "Переводчик", new Runnable() {
            @Override
            public void run() {
                push(translatorPage(), true);
            }
        });

        LinearLayout tryIt = group(col, "Попробовать");
        EditText et = new EditText(this);
        et.setHint("Нажмите сюда и печатайте");
        et.setTextColor(label());
        et.setHintTextColor(secondary());
        et.setTextSize(17);
        et.setBackground(null);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        addRow(tryIt, et, 16);

        refreshRootValues();
        return page(null, col, false);
    }

    private View appearancePage() {
        LinearLayout col = column();
        LinearLayout st = group(col, "Стиль");
        choices(st, new String[]{"Liquid Glass", "Классический iOS"},
                new String[]{Prefs.STYLE_GLASS, Prefs.STYLE_CLASSIC}, prefs.style, "style");
        footer(col, "Liquid Glass — стеклянные клавиши с бликом и мягкой тенью, как в iOS 26.");

        LinearLayout th = group(col, "Оформление");
        choices(th, new String[]{"Как в системе", "Светлое", "Тёмное"},
                new String[]{Prefs.THEME_AUTO, Prefs.THEME_LIGHT, Prefs.THEME_DARK}, prefs.theme, "theme");

        LinearLayout ac = group(col, "Цвет акцента");
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setPadding(dp(16), dp(12), dp(16), dp(12));
        hs.addView(dots);
        addRow(ac, hs, 16);
        buildAccents(dots);

        LinearLayout gl = group(col, "Живое стекло");
        TextView support = new TextView(this);
        support.setTextSize(15);
        support.setTextColor(blurSupported ? label() : 0xFFFF3B30);
        support.setPadding(dp(16), dp(12), dp(16), dp(12));
        support.setText(blurSupported
                ? "✓ Телефон поддерживает живое размытие"
                : Build.VERSION.SDK_INT >= 31
                ? "Размытие сейчас выключено системой (режим энергосбережения или ограничение производителя)"
                : "Живое размытие доступно на Android 12 и новее");
        addRow(gl, support, 16);
        switchRow(gl, "Размытие фона", "blur", prefs.blur);
        slider(gl, "Сила размытия", "blur_radius", 0, 80, prefs.blurRadius, " dp");
        slider(gl, "Прозрачность фона", "glass_transparency", 0, 92, 100 - prefs.glassOpacity, "%");
        slider(gl, "Прозрачность клавиш", "key_transparency", 0, 80, prefs.keyTransparency, "%");
        footer(col, "Как на iPhone: под клавиатурой в реальном времени размывается то, что на экране, "
                + "и всё меняется на лету. Внизу — живой предпросмотр поверх движущейся картинки. "
                + "Размытие работает в стиле Liquid Glass.");
        return page("Внешний вид", col, true);
    }

    private void buildAccents(final LinearLayout dots) {
        dots.removeAllViews();
        for (final String[] a : Prefs.ACCENTS) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.parseColor(dark ? a[2] : a[1]));
            if (a[0].equals(prefs.accent)) g.setStroke(dp(3), dark ? 0xFF8E8E93 : 0xFF8E8E93);
            else if ("white".equals(a[0]) && !dark) g.setStroke(Math.max(1, dp(1)), 0xFFD1D1D6);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.setMargins(0, 0, dp(14), 0);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setString("accent", a[0]);
                    buildAccents(dots);
                    refreshPreview();
                }
            });
            dots.addView(dot, lp);
        }
    }

    private View sizePage() {
        LinearLayout col = column();
        LinearLayout pr = group(col, "Размер как у iPhone");
        choices(pr, new String[]{"Авто", "iPhone SE", "iPhone", "iPhone Pro Max", "Компактный", "Большой"},
                new String[]{Prefs.SIZE_AUTO, Prefs.SIZE_SE, Prefs.SIZE_IPHONE, Prefs.SIZE_MAX,
                        Prefs.SIZE_COMPACT, Prefs.SIZE_LARGE}, prefs.sizePreset, "size_preset");
        footer(col, "«Авто» подбирает размер под ширину экрана (клавиши 42 или 45, как у iPhone), "
                + "учитывает горизонтальный режим и планшеты.");

        LinearLayout fine = group(col, "Тонкая настройка");
        slider(fine, "Высота клавиш", "key_height", 60, 150, prefs.keyHeightPct, "%");
        slider(fine, "Размер букв", "font", 60, 150, prefs.fontPct, "%");
        slider(fine, "Промежутки между клавишами", "hgap", 30, 200, prefs.hGapPct, "%");
        slider(fine, "Промежутки между рядами", "vgap", 30, 200, prefs.vGapPct, "%");
        slider(fine, "Скругление углов", "radius", 0, 300, prefs.radiusPct, "%");
        slider(fine, "Отступы по бокам", "side_pad", 0, 24, prefs.sidePadDp, " dp");
        slider(fine, "Отступ снизу", "bottom_pad", 0, 40, prefs.bottomPadDp, " dp");

        LinearLayout lay = group(col, "Раскладка");
        switchRow(lay, "🌐 внизу, рядом с полоской навигации", "globe_row", prefs.globeRow);
        switchRow(lay, "🌐 под клавишей ввода (справа)", "globe_right", prefs.globeRight);
        switchRow(lay, "Ряд цифр над буквами", "number_row", prefs.numberRow);
        switchRow(lay, "Оптимизация размера", "limit_height", prefs.limitHeight);
        footer(col, "Оптимизация не даёт клавиатуре занять больше 45% экрана. Если 🌐 внизу "
                + "выключен, кнопка 🌐 появляется в нижнем ряду, а клавиатура становится ниже.");

        LinearLayout reset = group(col, null);
        action(reset, "Сбросить размеры", 0xFFFF3B30, new Runnable() {
            @Override
            public void run() {
                prefs.resetSizes();
                refreshPreview();
                replaceTop(sizePage());
            }
        });
        return page("Размер", col, true);
    }

    private View animationPage() {
        LinearLayout col = column();
        LinearLayout sp = group(col, "Скорость");
        choicesInt(sp, new String[]{"Плавные", "Быстрые", "Без анимаций"},
                new int[]{Prefs.ANIM_SMOOTH, Prefs.ANIM_FAST, Prefs.ANIM_OFF}, prefs.animSpeed, "anim_speed");
        LinearLayout ef = group(col, "Эффекты");
        switchRow(ef, "Увеличенная буква при нажатии", "popups", prefs.popups);
        switchRow(ef, "«Жидкое стекло» под пальцем", "liquid_touch", prefs.liquidTouch);
        footer(col, "Анимации синхронизированы с частотой экрана: 60, 90 или 120 Гц.");
        return page("Анимации", col, true);
    }

    private View soundPage() {
        LinearLayout col = column();
        LinearLayout s = group(col, "Звук");
        switchRow(s, "Щелчки клавиатуры", "sound", prefs.sound);
        slider(s, "Громкость", "sound_volume", 0, 100, prefs.soundVolume, "%");
        LinearLayout v = group(col, "Тактильный отклик");
        choicesInt(v, new String[]{"Выключен", "Лёгкий", "Средний", "Сильный"},
                new int[]{0, 1, 2, 3}, prefs.haptic, "haptic");
        return page("Звуки и вибрация", col, false);
    }

    private View typingPage() {
        LinearLayout col = column();
        LinearLayout g = group(col, null);
        switchRow(g, "Автопрописные", "autocap", prefs.autoCap);
        switchRow(g, "Быстрая клавиша «.»", "double_space", prefs.doubleSpace);
        switchRow(g, "Подсказки слов", "suggestions", prefs.suggestions);
        switchRow(g, "Пробел как трекпад", "trackpad", prefs.trackpad);
        footer(col, "Двойной пробел ставит точку и пробел. Удержание пробела превращает "
                + "клавиатуру в трекпад для курсора.");
        LinearLayout sw = group(col, "Свайп по пробелу");
        choices(sw, new String[]{"Двигает курсор", "Меняет язык"},
                new String[]{Prefs.SWIPE_CURSOR, Prefs.SWIPE_LANG}, prefs.spaceSwipe, "space_swipe");
        footer(col, "Проведите по 🌐 вправо — следующий язык, влево — предыдущий. Удержание 🌐 "
                + "показывает список языков.");
        LinearLayout lp = group(col, null);
        slider(lp, "Задержка удержания", "long_press", 200, 700, prefs.longPressMs, " мс");
        LinearLayout w = group(col, null);
        action(w, "Забыть запомненные слова", 0xFFFF3B30, new Runnable() {
            @Override
            public void run() {
                prefs.bumpDictGeneration();
                Toast.makeText(SettingsActivity.this, "Словарь будет очищен", Toast.LENGTH_SHORT).show();
            }
        });
        footer(col, "Слова запоминаются только на этом телефоне и не запоминаются в полях "
                + "паролей и в режиме инкогнито.");
        return page("Ввод", col, false);
    }

    private View languagesPage() {
        LinearLayout col = column();
        LinearLayout g = group(col, "Клавиатуры");
        final ArrayList<TextView> checks = new ArrayList<>();
        for (final String l : Layouts.ALL_LANGS) {
            final TextView check = checkRow(g, Layouts.settingsName(l), prefs.hasLang(l));
            check.setTag(l);
            checks.add(check);
            ((View) check.getParent()).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean on = check.getVisibility() != View.VISIBLE;
                    List<String> chosen = new ArrayList<>();
                    for (TextView c : checks) {
                        boolean sel = c == check ? on : c.getVisibility() == View.VISIBLE;
                        if (sel) chosen.add((String) c.getTag());
                    }
                    if (chosen.isEmpty()) {
                        Toast.makeText(SettingsActivity.this, "Нужен хотя бы один язык", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                    prefs.setLangs(chosen);
                    refreshPreview();
                }
            });
        }
        footer(col, "🌐 переключает выбранные языки. Японский: печатайте ромадзи "
                + "(konnichiha → こんにちは); пробел переключает хирагану, катакану и латиницу, "
                + "確定 подтверждает. Кандзи нет.");
        return page("Языки", col, false);
    }

    private View clipboardPage() {
        LinearLayout col = column();
        LinearLayout g = group(col, null);
        switchRow(g, "История буфера обмена", "clipboard", prefs.clipboard);
        footer(col, "Кнопка 📋 над клавиатурой открывает историю: нажмите, чтобы вставить; "
                + "удерживайте, чтобы закрепить или удалить; 🔍 — поиск.");
        LinearLayout size = group(col, "Сколько хранить");
        String[] labels = new String[Prefs.CLIP_SIZES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = String.format("%,d", Prefs.CLIP_SIZES[i]).replace(',', ' ') + " записей";
        }
        choicesInt(size, labels, Prefs.CLIP_SIZES, prefs.clipMax, "clip_max");
        LinearLayout clear = group(col, null);
        action(clear, "Очистить историю", 0xFFFF3B30, new Runnable() {
            @Override
            public void run() {
                prefs.bumpClipGeneration();
                Toast.makeText(SettingsActivity.this, "История будет очищена", Toast.LENGTH_SHORT).show();
            }
        });
        footer(col, "Закреплённые записи останутся. История хранится только на телефоне; "
                + "пароли, помеченные приложениями как секретные, не сохраняются.");
        return page("Буфер обмена", col, false);
    }

    private View translatorPage() {
        LinearLayout col = column();
        LinearLayout h = group(col, "Удержание пробела");
        choices(h, new String[]{"Открывает переводчик", "Включает трекпад"},
                new String[]{Prefs.HOLD_TRANSLATE, Prefs.HOLD_TRACKPAD}, prefs.spaceHold, "space_hold");
        footer(col, "Проведите пальцем по пробелу — курсор двигается в любом режиме.");
        LinearLayout g = group(col, "Языковые пакеты");
        switchRow(g, "Скачивать только по Wi-Fi", "trans_wifi", prefs.translatorWifiOnly);
        footer(col, "Удерживайте пробел и печатайте — перевод появляется сразу, «Вставить» или "
                + "кнопка ввода вставляет его в поле. Нажмите на язык, чтобы выбрать другой, ⇄ меняет "
                + "языки местами. Перевод выполняется прямо на телефоне (Google ML Kit): текст никуда "
                + "не отправляется. Интернет нужен только один раз, чтобы скачать язык (~30 МБ).");
        return page("Переводчик", col, false);
    }

    private void replaceTop(View page) {
        View old = stack.remove(stack.size() - 1);
        host.removeView(old);
        stack.add(page);
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        dockPreview(page);
    }

    // ---------------------------------------------------------------- iOS list building blocks

    private LinearLayout group(LinearLayout col, String header) {
        if (header != null) {
            TextView t = new TextView(this);
            t.setText(header.toUpperCase());
            t.setTextSize(13);
            t.setTextColor(secondary());
            t.setPadding(dp(16), dp(22), dp(16), dp(7));
            col.addView(t);
        } else {
            View gap = new View(this);
            col.addView(gap, new LinearLayout.LayoutParams(1, dp(28)));
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cellBg());
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        box.setClipToOutline(true);
        col.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void footer(LinearLayout col, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(secondary());
        t.setPadding(dp(16), dp(7), dp(16), 0);
        col.addView(t);
    }

    /** Adds a row with an inset hairline above it (iOS separators). */
    private void addRow(LinearLayout box, View row, int insetDp) {
        if (box.getChildCount() > 0) {
            View sep = new View(this);
            sep.setBackgroundColor(separator());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, dp(0.5f)));
            lp.setMargins(dp(insetDp), 0, 0, 0);
            box.addView(sep, lp);
        }
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout rowBase() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(16), dp(4), dp(16), dp(4));
        return row;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void pressable(final View row, final Runnable onTap) {
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackgroundColor(cellPressed());
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.setBackgroundColor(Color.TRANSPARENT);
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTap.run();
            }
        });
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTextColor(label());
        return t;
    }

    /** Navigation row: coloured icon tile, title, grey value, chevron. Returns the value view. */
    private TextView nav(LinearLayout box, int tileColor, String glyph, String text, Runnable onTap) {
        LinearLayout row = rowBase();
        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextSize(15);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(tileColor);
        tile.setCornerRadius(dp(7));
        icon.setBackground(tile);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(30), dp(30));
        il.setMargins(0, 0, dp(14), 0);
        row.addView(icon, il);
        row.addView(title(text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(this);
        value.setTextSize(17);
        value.setTextColor(secondary());
        value.setSingleLine(true);
        row.addView(value);
        TextView chev = new TextView(this);
        chev.setText("  ›");
        chev.setTextSize(22);
        chev.setTextColor(dark ? 0xFF5A5A5F : 0xFFC4C4C7);
        row.addView(chev);
        pressable(row, onTap);
        addRow(box, row, 60);
        return value;
    }

    private void switchRow(LinearLayout box, String text, final String key, boolean value) {
        LinearLayout row = rowBase();
        row.addView(title(text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final IosSwitch sw = new IosSwitch(this, dark, accent());
        sw.setChecked(value);
        sw.setOnChange(new IosSwitch.OnChange() {
            @Override
            public void onChange(boolean on) {
                prefs.setBool(key, on);
                refreshPreview();
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMargins(dp(10), 0, 0, 0);
        row.addView(sw, sl);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        addRow(box, row, 16);
    }

    private void slider(LinearLayout box, String text, final String key, int min, int max, int value,
                        final String unit) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(16), dp(10), dp(16), dp(6));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(title(text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView v = new TextView(this);
        v.setTextSize(17);
        v.setTextColor(secondary());
        v.setText(value + unit);
        top.addView(v);
        wrap.addView(top);
        IosSlider s = new IosSlider(this, dark, min, max, value, accent());
        s.setOnChange(new IosSlider.OnChange() {
            @Override
            public void onChange(int val) {
                v.setText(val + unit);
                prefs.setInt(key, val);
                refreshPreview();
            }
        });
        wrap.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addRow(box, wrap, 16);
    }

    /** Row with a blue checkmark on the right (shown when selected). Returns the checkmark. */
    private TextView checkRow(LinearLayout box, String text, boolean selected) {
        LinearLayout row = rowBase();
        row.addView(title(text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView check = new TextView(this);
        check.setText("✓");
        check.setTextSize(19);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setTextColor(accentInk());
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check);
        pressable(row, new Runnable() {
            @Override
            public void run() {
            }
        });
        addRow(box, row, 16);
        return check;
    }

    /** Single-choice list with checkmarks, writing a string setting. */
    private void choices(LinearLayout box, String[] labels, final String[] values, String current, final String key) {
        final TextView[] checks = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            checks[i] = checkRow(box, labels[i], values[i].equals(current));
            final int idx = i;
            ((View) checks[i].getParent()).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < checks.length; j++) {
                        checks[j].setVisibility(j == idx ? View.VISIBLE : View.INVISIBLE);
                    }
                    prefs.setString(key, values[idx]);
                    refreshPreview();
                }
            });
        }
    }

    private void choicesInt(LinearLayout box, String[] labels, final int[] values, int current, final String key) {
        final TextView[] checks = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            checks[i] = checkRow(box, labels[i], values[i] == current);
            final int idx = i;
            ((View) checks[i].getParent()).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < checks.length; j++) {
                        checks[j].setVisibility(j == idx ? View.VISIBLE : View.INVISIBLE);
                    }
                    prefs.setInt(key, values[idx]);
                    refreshPreview();
                }
            });
        }
    }

    /** Text button row in a colour (blue actions, red destructive ones). */
    private void action(LinearLayout box, String text, int color, Runnable onTap) {
        LinearLayout row = rowBase();
        TextView t = title(text);
        t.setTextColor(color);
        row.addView(t);
        pressable(row, onTap);
        addRow(box, row, 16);
    }

    // ---------------------------------------------------------------- values & status

    private void refreshRootValues() {
        if (vStyle == null) return;
        prefs.reload();
        vStyle.setText(Prefs.STYLE_GLASS.equals(prefs.style) ? "Liquid Glass" : "Классический");
        String size;
        switch (prefs.sizePreset) {
            case Prefs.SIZE_SE: size = "iPhone SE"; break;
            case Prefs.SIZE_IPHONE: size = "iPhone"; break;
            case Prefs.SIZE_MAX: size = "Pro Max"; break;
            case Prefs.SIZE_COMPACT: size = "Компактный"; break;
            case Prefs.SIZE_LARGE: size = "Большой"; break;
            default: size = "Авто"; break;
        }
        vSize.setText(size);
        vAnim.setText(prefs.animSpeed == Prefs.ANIM_SMOOTH ? "Плавные"
                : prefs.animSpeed == Prefs.ANIM_FAST ? "Быстрые" : "Выкл.");
        String[] h = {"Без вибрации", "Лёгкая", "Средняя", "Сильная"};
        vSound.setText(h[Math.max(0, Math.min(3, prefs.haptic))]);
        vLangs.setText(String.valueOf(prefs.langs.length));
        vClip.setText(prefs.clipboard ? String.format("%,d", prefs.clipMax).replace(',', ' ') : "Выкл.");
        vTrans.setText(Prefs.HOLD_TRANSLATE.equals(prefs.spaceHold) ? "Пробел" : "Выкл.");
    }

    private void updateStatus() {
        if (status == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        boolean enabled = false;
        if (imm != null) {
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (getPackageName().equals(info.getPackageName())) enabled = true;
            }
        }
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");
        if (selected) status.setText("✅ Клавиатура включена и выбрана");
        else if (enabled) status.setText("Почти готово: выберите её как основную");
        else status.setText("Чтобы начать, включите «iPhone Клавиатура»");
    }

    // ---------------------------------------------------------------- live preview

    private void refreshPreview() {
        prefs.reload();
        preview.applyPrefs(prefs);
        boolean live = blurSupported && prefs.blur && Prefs.STYLE_GLASS.equals(prefs.style);
        preview.setTheme(Theme.from(prefs, dark, live));
        backdrop.setVisibility(live ? View.VISIBLE : View.GONE);
        if (live) {
            backdrop.setBlur(prefs.blurRadius);
            backdrop.invalidate();
        }
        String lang = prefs.currentLang;
        preview.setLocale(Layouts.locale(lang));
        preview.setLayout(Layouts.get(lang, previewMode, prefs.numberRow, !prefs.globeRow),
                previewMode == Layouts.LETTERS);
        String[] names = new String[prefs.langs.length];
        int cur = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = Layouts.name(prefs.langs[i]);
            if (prefs.langs[i].equals(lang)) cur = i;
        }
        preview.setLanguageMenu(names, cur);
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
    @Override public void onLanguagePicked(int index) {
        if (index >= 0 && index < prefs.langs.length) {
            prefs.setCurrentLang(prefs.langs[index]);
            previewMode = Layouts.LETTERS;
            refreshPreview();
            preview.flashLanguage(Layouts.name(prefs.currentLang));
        }
    }
    @Override public void onSpaceHold() { }
    @Override public void onLanguageSwipe(int direction) {
        String[] l = prefs.langs;
        int idx = 0;
        for (int i = 0; i < l.length; i++) if (l[i].equals(prefs.currentLang)) idx = i;
        onLanguagePicked(((idx + direction) % l.length + l.length) % l.length);
    }
}
