package com.fa.tvlauncher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Launcher estilo Apple TV. Todo el foco se maneja a mano (no con el foco de Android)
 * para que las flechas sean deterministas:
 *   zona 0 = barra superior (Entradas, Wi-Fi, Ajustes)
 *   zona 1 = dock
 *   zona 2 = cuadrícula
 */
public class MainActivity extends Activity {

    private static final int ZONE_TOP = 0, ZONE_DOCK = 1, ZONE_GRID = 2;

    static class AppItem {
        String pkg;
        CharSequence label;
        ComponentName cn;
        Drawable art;
        boolean banner;
        int glow;
        float x, y;
        FrameLayout tile;
        TextView name;
    }

    // ---- geometría / recursos
    private float d;
    private int W, H, cols;
    private float tileW, tileH, gap, pad, bandPad, bandW, bandH, bandLeft, rowStep, baseY;
    private Typeface fThin, fLight, fReg;

    // ---- vistas
    private FrameLayout root, content;
    private HighlightView hlContent, hlHeader;
    private GlowView glow;
    private TextView bigName, greeting;
    private final TextView[] pills = new TextView[3];
    private LinearLayout pillRow, menu;
    private ImageView wallpaperView;
    private View scrim, band;
    private Bitmap wallpaperBmp;
    private long wallpaperStamp = -1;

    // ---- datos
    private final List<AppItem> dock = new ArrayList<>();
    private final List<AppItem> grid = new ArrayList<>();

    // ---- estado de foco
    private int zone = ZONE_DOCK, topIdx = 0, dockIdx = 0, gRow = 0, gCol = 0;
    private AppItem focusedTile;

    // ---- menú Entradas
    private boolean menuOpen;
    private int menuIdx;
    private final List<TextView> menuViews = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();

    private BroadcastReceiver pkgReceiver;

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;

        fThin = font("Outfit-ExtraLight.ttf", "sans-serif-thin");
        fLight = font("Outfit-Light.ttf", "sans-serif-light");
        fReg = font("Outfit-Regular.ttf", "sans-serif");

        cols = Math.max(4, Math.min(7, getResources().getInteger(R.integer.columns)));
        buildLists();
        buildUi();
        restoreState();
        setContentView(root);
        root.post(new Runnable() {
            @Override public void run() { applyFocus(false); }
        });

        // Si se instala o desinstala una app, se reconstruye (por evento, sin consultas periódicas).
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { recreate(); }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWifi();
        updateGreeting();
        loadWallpaper();
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeMenu();
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putInt("zone", zone).putInt("topIdx", topIdx).putInt("dockIdx", dockIdx)
                .putInt("gRow", gRow).putInt("gCol", gCol).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
    }

    /** Se pulsó Home con el launcher ya abierto: vuelve al dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeMenu();
        zone = dock.isEmpty() ? ZONE_GRID : ZONE_DOCK;
        applyFocus(true);
    }

    private void restoreState() {
        SharedPreferences p = getSharedPreferences("state", MODE_PRIVATE);
        topIdx = clamp(p.getInt("topIdx", 0), 0, 2);
        dockIdx = clamp(p.getInt("dockIdx", 0), 0, Math.max(0, dock.size() - 1));
        int rows = gridRows();
        gRow = clamp(p.getInt("gRow", 0), 0, Math.max(0, rows - 1));
        gCol = clamp(p.getInt("gCol", 0), 0, Math.max(0, rowLen(gRow) - 1));
        int z = p.getInt("zone", ZONE_DOCK);
        if (z == ZONE_GRID && grid.isEmpty()) z = ZONE_DOCK;
        if (z == ZONE_DOCK && dock.isEmpty()) z = ZONE_GRID;
        if (z == ZONE_TOP) z = dock.isEmpty() ? ZONE_GRID : ZONE_DOCK;   // arranca siempre en contenido
        zone = z;
    }

    // =====================================================================
    // Datos
    // =====================================================================

    private void buildLists() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>();
        Collections.addAll(hidden, getResources().getStringArray(R.array.hidden_apps));

        // Primero las apps con banner de TV (LEANBACK); después las normales.
        Map<String, ResolveInfo> map = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || hidden.contains(p)) continue;
                if (!map.containsKey(p)) map.put(p, ri);
            }
        }

        for (String p : getResources().getStringArray(R.array.dock_apps)) {
            if (dock.size() >= cols) break;
            ResolveInfo ri = map.remove(p);
            if (ri != null) dock.add(makeItem(pm, ri));
        }
        List<AppItem> rest = new ArrayList<>();
        for (ResolveInfo ri : map.values()) rest.add(makeItem(pm, ri));
        Collections.sort(rest, new Comparator<AppItem>() {
            @Override public int compare(AppItem a, AppItem b) {
                return a.label.toString().compareToIgnoreCase(b.label.toString());
            }
        });
        grid.addAll(rest);
    }

    private AppItem makeItem(PackageManager pm, ResolveInfo ri) {
        AppItem a = new AppItem();
        a.pkg = ri.activityInfo.packageName;
        a.cn = new ComponentName(a.pkg, ri.activityInfo.name);
        a.label = ri.loadLabel(pm);
        Drawable bn = ri.activityInfo.loadBanner(pm);
        if (bn != null) {
            a.art = bn;
            a.banner = true;
        } else {
            a.art = ri.activityInfo.loadIcon(pm);
        }
        return a;
    }

    private int gridRows() { return (grid.size() + cols - 1) / cols; }

    private int rowLen(int r) { return Math.max(0, Math.min(cols, grid.size() - r * cols)); }

    private AppItem gridItem(int r, int c) {
        int i = r * cols + c;
        return (i >= 0 && i < grid.size()) ? grid.get(i) : null;
    }

    // =====================================================================
    // Interfaz
    // =====================================================================

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Fondo opcional (se carga desde un archivo; sin archivo queda negro puro).
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setVisibility(View.GONE);
        root.addView(wallpaperView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x59000000, 0xB3000000}));
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Geometría: 4 mosaicos 16:9 por defecto; se encogen si hay más columnas.
        gap = 24 * d;
        pad = 10 * d;
        bandPad = 28 * d;
        float avail = W - 2 * (64 * d) - 2 * bandPad - (cols - 1) * gap;
        tileW = Math.min(192 * d, avail / cols);
        tileH = tileW * 9f / 16f;
        bandW = cols * tileW + (cols - 1) * gap + 2 * bandPad;
        bandH = tileH + 2 * bandPad;
        bandLeft = (W - bandW) / 2f;
        rowStep = tileH + 46 * d;
        baseY = H - bandH - 44 * d;

        // ---- capa de contenido (dock + cuadrícula), se desplaza hacia arriba
        float contentH = bandH + 40 * d + gridRows() * rowStep + 100 * d;
        content = new FrameLayout(this);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(W, Math.round(contentH));
        clp.topMargin = Math.round(baseY);
        root.addView(content, clp);

        glow = new GlowView(this);
        content.addView(glow, lp(W, 300 * d, 0, -260 * d));

        band = new View(this);
        band.setBackground(rrect(0xFF1C1C1E, 36 * d));
        content.addView(band, lp(bandW, bandH, bandLeft, 0));

        bigName = new TextView(this);
        bigName.setTypeface(fLight);
        bigName.setTextColor(Color.WHITE);
        bigName.setTextSize(40);
        bigName.setGravity(Gravity.CENTER);
        bigName.setSingleLine(true);
        bigName.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(bigName, lp(W, 64 * d, 0, -98 * d));

        hlContent = new HighlightView(this, d);
        content.addView(hlContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < dock.size(); i++) {
            AppItem a = dock.get(i);
            a.x = bandLeft + bandPad + i * (tileW + gap);
            a.y = bandPad;
            addTile(a, false);
        }
        for (int i = 0; i < grid.size(); i++) {
            AppItem a = grid.get(i);
            int r = i / cols, c = i % cols;
            a.x = bandLeft + bandPad + c * (tileW + gap);
            a.y = bandH + 64 * d + r * rowStep;
            addTile(a, true);
        }

        // ---- cabecera fija: saludo, reloj, fecha
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        greeting = new TextView(this);
        greeting.setTypeface(fLight);
        greeting.setTextSize(20);
        greeting.setTextColor(0xCCFFFFFF);
        left.addView(greeting);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTypeface(fThin);
        clock.setTextSize(76);
        clock.setTextColor(Color.WHITE);
        clock.setIncludeFontPadding(false);
        left.addView(clock);

        TextClock date = new TextClock(this);
        String pat = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setFormat12Hour(pat);
        date.setFormat24Hour(pat);
        date.setTypeface(fLight);
        date.setTextSize(20);
        date.setTextColor(0xB3FFFFFF);
        left.addView(date);

        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        llp.leftMargin = Math.round(64 * d);
        llp.topMargin = Math.round(36 * d);
        root.addView(left, llp);

        // ---- resaltado de la barra superior (debajo de los botones)
        hlHeader = new HighlightView(this, d);
        root.addView(hlHeader, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- botones de la barra superior
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"Entradas", "Wi-Fi", "Ajustes"};
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTypeface(fReg);
            t.setTextSize(16);
            t.setTextColor(Color.WHITE);
            t.setSingleLine(true);
            t.setPadding(Math.round(22 * d), Math.round(10 * d), Math.round(22 * d), Math.round(10 * d));
            t.setBackground(rrect(0x33FFFFFF, 40 * d));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) p.leftMargin = Math.round(12 * d);
            pillRow.addView(t, p);
            pills[i] = t;
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        plp.rightMargin = Math.round(64 * d);
        plp.topMargin = Math.round(44 * d);
        root.addView(pillRow, plp);

        // ---- menú desplegable de Entradas
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(rrect(0xF0242426, 22 * d));
        menu.setPadding(Math.round(8 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addTile(final AppItem a, boolean withName) {
        final float radius = 12 * d;
        FrameLayout t = new FrameLayout(this);
        t.setBackgroundColor(0xFF2C2C2E);
        t.setClipToOutline(true);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.art);
        if (a.banner) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = Math.round(tileH * 0.20f);
            iv.setPadding(p, p, p, p);
        }
        t.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(t, lp(tileW, tileH, a.x, a.y));
        a.tile = t;

        if (withName) {
            TextView n = new TextView(this);
            n.setText(a.label);
            n.setTypeface(fLight);
            n.setTextSize(15);
            n.setTextColor(Color.WHITE);
            n.setGravity(Gravity.CENTER);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setAlpha(0f);
            content.addView(n, lp(tileW + gap, 24 * d, a.x - gap / 2f, a.y + tileH + 10 * d));
            a.name = n;
        }
    }

    // =====================================================================
    // Foco y desplazamiento
    // =====================================================================

    private void applyFocus(boolean anim) {
        AppItem cur = null;
        if (zone == ZONE_DOCK && !dock.isEmpty()) cur = dock.get(clamp(dockIdx, 0, dock.size() - 1));
        else if (zone == ZONE_GRID) cur = gridItem(gRow, gCol);

        if (focusedTile != null && focusedTile != cur) setTileFocus(focusedTile, false, anim);
        focusedTile = cur;

        if (cur != null) {
            setTileFocus(cur, true, anim);
            hlContent.show(new RectF(cur.x - pad, cur.y - pad,
                    cur.x + tileW + pad, cur.y + tileH + pad), anim);
        } else {
            hlContent.hide(anim);
        }

        if (zone == ZONE_TOP) {
            RectF r = pillRect(topIdx);
            if (r != null) hlHeader.show(r, anim);
        } else {
            hlHeader.hide(anim);
        }

        // La cuadrícula sube: la fila enfocada queda al 60 % de la altura de la pantalla.
        float off = 0;
        if (zone == ZONE_GRID) {
            float rowAbs = baseY + bandH + 64 * d + gRow * rowStep;
            off = Math.max(0, rowAbs - H * 0.60f);
        }
        content.animate().cancel();
        if (anim) {
            content.animate().translationY(-off).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        } else {
            content.setTranslationY(-off);
        }

        // Nombre grande y resplandor: solo cuando el foco está en el dock.
        boolean showDock = zone == ZONE_DOCK && cur != null;
        float a = showDock ? 1f : 0f;
        if (anim) {
            bigName.animate().alpha(a).setDuration(200).start();
            glow.animate().alpha(a).setDuration(300).start();
        } else {
            bigName.setAlpha(a);
            glow.setAlpha(a);
        }
        if (showDock) {
            bigName.setText(cur.label);
            glow.setColor(glowOf(cur), anim);
        }
    }

    private void setTileFocus(AppItem a, boolean on, boolean anim) {
        float s = on ? 1.08f : 1f;
        a.tile.animate().cancel();
        if (anim) a.tile.animate().scaleX(s).scaleY(s).setDuration(180).start();
        else { a.tile.setScaleX(s); a.tile.setScaleY(s); }
        if (a.name != null) {
            a.name.animate().cancel();
            if (anim) a.name.animate().alpha(on ? 1f : 0f).setDuration(160).start();
            else a.name.setAlpha(on ? 1f : 0f);
        }
    }

    private RectF pillRect(int i) {
        TextView p = pills[i];
        if (p.getWidth() == 0) return null;
        float l = pillRow.getLeft() + p.getLeft();
        float t = pillRow.getTop() + p.getTop();
        float m = 4 * d;
        return new RectF(l - m, t - m, l + p.getWidth() + m, t + p.getHeight() + m);
    }

    // =====================================================================
    // Teclas (D-pad determinista)
    // =====================================================================

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) handleKey(k, e.getRepeatCount());
        return true;   // se consumen siempre, también BACK (un Home no se "cierra")
    }

    private void handleKey(int k, int repeat) {
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;

        if (menuOpen) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) menuIdx = Math.max(0, menuIdx - 1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) menuIdx = Math.min(menuViews.size() - 1, menuIdx + 1);
            else if (k == KeyEvent.KEYCODE_BACK) { closeMenu(); return; }
            else if (ok && repeat == 0) {
                Runnable r = menuActions.get(menuIdx);
                closeMenu();
                if (r != null) r.run();
                return;
            }
            paintMenu();
            return;
        }

        boolean hasDock = !dock.isEmpty();
        boolean hasGrid = !grid.isEmpty();

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == ZONE_GRID) {
                    if (gRow > 0) gRow--;
                    else if (hasDock) { zone = ZONE_DOCK; dockIdx = Math.min(gCol, dock.size() - 1); }
                    else zone = ZONE_TOP;
                } else if (zone == ZONE_DOCK) {
                    zone = ZONE_TOP;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == ZONE_TOP) {
                    if (hasDock) zone = ZONE_DOCK; else if (hasGrid) zone = ZONE_GRID;
                } else if (zone == ZONE_DOCK) {
                    if (hasGrid) { zone = ZONE_GRID; gRow = 0; gCol = Math.min(dockIdx, rowLen(0) - 1); }
                } else if (zone == ZONE_GRID && gRow < gridRows() - 1) {
                    gRow++;
                    gCol = Math.min(gCol, rowLen(gRow) - 1);
                }
                break;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (zone == ZONE_TOP) topIdx = Math.max(0, topIdx - 1);
                else if (zone == ZONE_DOCK) dockIdx = Math.max(0, dockIdx - 1);
                else gCol = Math.max(0, gCol - 1);
                break;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (zone == ZONE_TOP) topIdx = Math.min(2, topIdx + 1);
                else if (zone == ZONE_DOCK) dockIdx = Math.min(dock.size() - 1, dockIdx + 1);
                else gCol = Math.min(rowLen(gRow) - 1, gCol + 1);
                break;

            case KeyEvent.KEYCODE_BACK:
                if (zone != ZONE_DOCK && hasDock) zone = ZONE_DOCK;
                break;

            default:   // OK / ENTER
                if (repeat == 0) activate();
                return;
        }
        applyFocus(true);
    }

    private void activate() {
        if (zone == ZONE_TOP) {
            if (topIdx == 0) openMenu();
            else if (topIdx == 1) startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else startSafe(new Intent(Settings.ACTION_SETTINGS));
        } else if (focusedTile != null) {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(focusedTile.cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startSafe(i);
        }
    }

    private void startSafe(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================================
    // Menú Entradas
    // =====================================================================

    private void openMenu() {
        menu.removeAllViews();
        menuViews.clear();
        menuActions.clear();

        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                for (final TvInputInfo info : tim.getTvInputList()) {
                    if (!info.isPassthroughInput()) continue;   // HDMI, AV...
                    CharSequence label = info.loadCustomLabel(this);
                    if (TextUtils.isEmpty(label)) label = info.loadLabel(this);
                    addMenuItem(label, new Runnable() {
                        @Override public void run() {
                            startSafe(new Intent(Intent.ACTION_VIEW,
                                    TvContract.buildChannelUriForPassthroughInput(info.getId())));
                        }
                    });
                }
            } catch (RuntimeException ignored) { }
        }

        final String live = getString(R.string.live_tv_package);
        final Intent liveIntent = getPackageManager().getLaunchIntentForPackage(live);
        if (liveIntent != null) {
            addMenuItem("TV en vivo", new Runnable() {
                @Override public void run() {
                    liveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startSafe(liveIntent);
                }
            });
        }
        if (menuViews.isEmpty()) addMenuItem("Sin entradas disponibles", null);

        menuIdx = 0;
        menuOpen = true;
        menu.setVisibility(View.VISIBLE);
        paintMenu();
        menu.post(new Runnable() {
            @Override public void run() {
                menu.setX(pillRow.getLeft() + pills[0].getLeft());
                menu.setY(pillRow.getTop() + pills[0].getBottom() + 10 * d);
            }
        });
    }

    private void addMenuItem(CharSequence text, Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(fReg);
        t.setTextSize(18);
        t.setTextColor(Color.WHITE);
        t.setMinWidth(Math.round(240 * d));
        t.setPadding(Math.round(20 * d), Math.round(12 * d), Math.round(20 * d), Math.round(12 * d));
        menu.addView(t);
        menuViews.add(t);
        menuActions.add(action);
    }

    private void paintMenu() {
        for (int i = 0; i < menuViews.size(); i++) {
            menuViews.get(i).setBackground(i == menuIdx ? rrect(0x40FFFFFF, 14 * d) : null);
        }
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        menu.setVisibility(View.GONE);
    }

    // =====================================================================
    // Saludo y Wi-Fi
    // =====================================================================

    /**
     * Fondo opcional: /sdcard/Android/data/com.fa.tvlauncher/files/wallpaper.jpg (o .png).
     * Se cambia con adb push, sin recompilar. Se reduce al tamaño de pantalla (RGB_565).
     */
    private void loadWallpaper() {
        File dir = getExternalFilesDir(null);
        File f = null;
        if (dir != null) {
            for (String n : new String[]{"wallpaper.jpg", "wallpaper.jpeg", "wallpaper.png"}) {
                File c = new File(dir, n);
                if (c.isFile()) { f = c; break; }
            }
        }
        long stamp = f != null ? f.lastModified() + f.length() : 0;
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;

        wallpaperView.setImageDrawable(null);
        if (wallpaperBmp != null) { wallpaperBmp.recycle(); wallpaperBmp = null; }

        Bitmap bm = null;
        if (f != null) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= W && o.outHeight / (s * 2) >= H) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            } catch (RuntimeException | OutOfMemoryError e) {
                bm = null;
            }
        }
        wallpaperBmp = bm;
        int vis = bm != null ? View.VISIBLE : View.GONE;
        if (bm != null) wallpaperView.setImageBitmap(bm);
        wallpaperView.setVisibility(vis);
        scrim.setVisibility(vis);
        band.setBackground(rrect(bm != null ? 0xD91C1C1E : 0xFF1C1C1E, 36 * d));
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 12 ? "Buenos días" : (h < 19 ? "Buenas tardes" : "Buenas noches"));
    }

    private void updateWifi() {
        String s = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            String ssid = wi != null ? wi.getSSID() : null;
            if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.equals("0x")) {
                s = "Wi-Fi · " + ssid.replace("\"", "");
            }
        } catch (RuntimeException ignored) { }
        pills[1].setText(s);
        pills[1].post(new Runnable() {
            @Override public void run() { if (zone == ZONE_TOP) applyFocus(false); }
        });
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    private Typeface font(String file, String fallback) {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/" + file);
        } catch (RuntimeException e) {
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }

    private FrameLayout.LayoutParams lp(float w, float h, float x, float y) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Math.round(w), Math.round(h));
        p.leftMargin = Math.round(x);
        p.topMargin = Math.round(y);
        return p;
    }

    private static GradientDrawable rrect(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int glowOf(AppItem a) {
        if (a.glow == 0) a.glow = sampleColor(a.art);
        return a.glow;
    }

    /** Color medio del banner (8x8 px), con más saturación y brillo para que "brille". */
    private int sampleColor(Drawable art) {
        try {
            Drawable dr = art.getConstantState() != null
                    ? art.getConstantState().newDrawable().mutate() : art;
            Bitmap bm = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            dr.setBounds(0, 0, 8, 8);
            dr.draw(new Canvas(bm));
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int p = bm.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
                }
            }
            bm.recycle();
            if (n == 0) return 0xFF8E8E93;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            if (hsv[2] < 0.12f || hsv[1] < 0.08f) return 0xFF8E8E93;   // casi negro o gris
            hsv[1] = Math.min(1f, hsv[1] * 1.3f);
            hsv[2] = Math.max(hsv[2], 0.80f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFF8E8E93;
        }
    }

    // =====================================================================
    // Vistas propias (ligeras: sin blur, sin layouts animados)
    // =====================================================================

    /** Resaltado de "cristal" que salta de mosaico en mosaico con un rebote suave. */
    static class HighlightView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
        private final float radius;
        private boolean shown;
        private ValueAnimator anim;

        HighlightView(Context c, float d) {
            super(c);
            radius = 20 * d;
            fill.setColor(0x40FFFFFF);
            stroke.setColor(0x80FFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * d);
            setAlpha(0f);
        }

        void show(RectF target, boolean animate) {
            to.set(target);
            if (anim != null) anim.cancel();
            if (!shown || !animate) {
                cur.set(target);
                invalidate();
            } else {
                from.set(cur);
                anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(300);
                anim.setInterpolator(new OvershootInterpolator(1.4f));
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        float f = (float) a.getAnimatedValue();
                        cur.left = from.left + (to.left - from.left) * f;
                        cur.top = from.top + (to.top - from.top) * f;
                        cur.right = from.right + (to.right - from.right) * f;
                        cur.bottom = from.bottom + (to.bottom - from.bottom) * f;
                        invalidate();
                    }
                });
                anim.start();
            }
            if (!shown) {
                shown = true;
                animate().cancel();
                if (animate) animate().alpha(1f).setDuration(160).start();
                else setAlpha(1f);
            }
        }

        void hide(boolean animate) {
            if (!shown) return;
            shown = false;
            animate().cancel();
            if (animate) animate().alpha(0f).setDuration(160).start();
            else setAlpha(0f);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cur.isEmpty()) return;
            c.drawRoundRect(cur, radius, radius, fill);
            c.drawRoundRect(cur, radius, radius, stroke);
        }
    }

    /** Resplandor ambiental sobre el dock, en el color del banner enfocado. */
    static class GlowView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = 0xFF8E8E93;
        private ValueAnimator anim;

        GlowView(Context c) { super(c); }

        void setColor(int target, boolean animate) {
            if (anim != null) anim.cancel();
            if (!animate || target == color) {
                color = target;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
            anim.setDuration(350);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    color = (int) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() * 0.80f, r = getWidth() * 0.36f;
            int rgb = color & 0x00FFFFFF;
            p.setShader(new RadialGradient(cx, cy, r, rgb | 0x70000000, rgb,
                    Shader.TileMode.CLAMP));
            c.save();
            c.scale(1f, 0.55f, cx, cy);
            c.drawCircle(cx, cy, r, p);
            c.restore();
        }
    }
}
