package com.joel.redmiaudio;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native styling for the player, pairing and scanner screens. */
final class AppStyle {
    final Activity activity;
    final boolean dark;
    final int accentIndex;
    final int background, surface, text, muted, primary, onPrimary, container, onContainer;

    AppStyle(Activity activity) {
        this.activity = activity;
        dark = activity.getSharedPreferences("appearance", 0).getBoolean("dark", true);
        accentIndex = Math.max(0, Math.min(2, activity.getSharedPreferences("appearance", 0).getInt("accent", 0)));
        background = dark ? 0xff111318 : 0xfff9f9ff;
        surface = dark ? 0xff1d2026 : 0xffeeedf4;
        text = dark ? 0xffe3e2e9 : 0xff1b1b21;
        muted = dark ? 0xffc4c6d0 : 0xff44474f;
        int[][] colors = {
                {0xffadc6ff, 0xff002e69, 0xff284777, 0xffd8e2ff, 0xff445e91, 0xffd8e2ff, 0xff001a41},
                {0xffa8d5a2, 0xff12380f, 0xff2b5127, 0xffc3efbc, 0xff416b39, 0xffc3efbc, 0xff002200},
                {0xffd2bcff, 0xff381e72, 0xff4f378b, 0xffeaddff, 0xff6750a4, 0xffeaddff, 0xff21005d}
        };
        int[] color = colors[accentIndex];
        primary = dark ? color[0] : color[4];
        onPrimary = dark ? color[1] : 0xffffffff;
        container = dark ? color[2] : color[5];
        onContainer = dark ? color[3] : color[6];
    }

    void apply() {
        activity.setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        activity.getWindow().setStatusBarColor(background);
        activity.getWindow().setNavigationBarColor(background);
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    TextView label(String value, int size, boolean secondary) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(secondary ? muted : text);
        view.setFontFeatureSettings("kern");
        return view;
    }

    TextView title(String value, int size) {
        TextView view = label(value, size, false);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAccessibilityHeading(true);
        return view;
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout card(LinearLayout parent, String title, String description) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        card.setBackground(shape(surface, 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        parent.addView(card, params);
        card.addView(title(title, 22));
        if (!description.isEmpty()) {
            TextView subtitle = label(description, 14, true);
            subtitle.setPadding(0, dp(6), 0, dp(12));
            card.addView(subtitle);
        }
        return card;
    }

    Button button(String title, boolean prominent, Runnable action) {
        Button button = new Button(activity);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(prominent ? onPrimary : onContainer);
        button.setMinHeight(dp(52));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x22000000),
                shape(prominent ? primary : container, 28), null));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    Button addButton(LinearLayout parent, String title, boolean prominent, Runnable action) {
        Button button = button(title, prominent, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(button, params);
        return button;
    }
}
