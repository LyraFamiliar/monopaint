package io.github.mpdairy.monopaint;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import io.github.mpdairy.fasteink.PanelCoordinates;

/**
 * Floating mode bar shown at the top-centre of the canvas while a {@link FloatingSelection}
 * floats, switching between Transform, Distort and Warp.
 * Uses a non-focusable popup in the app's upright coordinates so taps never place the selection
 * and pen drags on the canvas stay active.
 */
final class SelectionModeBar {
    private static final String[] LABELS = {"Transform", "Distort", "Warp"};
    private static final int[] MODES = {FloatingSelection.TRANSFORM, FloatingSelection.DISTORT, FloatingSelection.WARP};

    private final PaintActivity app;
    private final LinearLayout bar;
    private final ToolButton[] buttons = new ToolButton[MODES.length];
    final PopupWindow popup;
    private final SelectionFeedback feedback = new SelectionFeedback();
    private final RectF panelArea = new RectF();

    SelectionModeBar(PaintActivity app) {
        this.app = app;
        bar = new LinearLayout(app);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(Ui.outline(Color.WHITE, dp(2), Color.BLACK, dp(8)));
        for (int i = 0; i < MODES.length; i++) {
            final int mode = MODES[i];
            ToolButton button = new ToolButton(app, app);
            button.setText(LABELS[i]);
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(true);
            button.setMinWidth(0); button.setMinimumWidth(0);
            button.setMinHeight(dp(44)); button.setMinimumHeight(dp(44));
            button.setPadding(dp(16), dp(6), dp(16), dp(6));
            button.setOnClickListener(v -> select(mode));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) params.leftMargin = dp(4);
            bar.addView(button, params);
            buttons[i] = button;
        }
        SettingsForm.style(bar, app.prefs.largeSettingsText());
        popup = Popups.create(bar, app.appTurn(), false, Color.TRANSPARENT);
        popup.setOnDismissListener(() -> {
            if (app.selectionModeBar == this) app.selectionModeBar = null;
            if (!feedback.takeFastInk().isEmpty()) app.pad.eraseFastInk(panelArea);
            feedback.close();
        });
    }
    private int dp(float value) { return app.dp(value); }

    void show() {
        if (app.root.getWindowToken() == null || app.pad.getWidth() <= 0 || app.pad.getHeight() <= 0) return;
        refreshSelection();
        RectF area = Popups.bounds(app.root, app.pad);
        int maxWidth = Math.max(1, Math.round(area.width() - dp(16)));
        bar.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.min(bar.getMeasuredWidth(), maxWidth);
        int height = bar.getMeasuredHeight();
        float low = area.left, high = Math.max(low, area.right - width);
        float left = Popups.clamp(area.centerX() - width / 2f, low, high);
        float top = Popups.clamp(area.top + dp(8), area.top, Math.max(area.top, area.bottom - height));
        RectF bounds = Popups.rect(left, top, width, height);
        panelArea.set(bounds);
        PanelCoordinates.fromView(app.root).mapRect(panelArea);
        Popups.show(popup, app.root, bounds);
    }

    void dismiss() {
        if (popup.isShowing()) popup.dismiss();
        else feedback.close();
    }

    /** Redraws the bar if needed after a selection drag may have written direct pixels beneath it. */
    void refresh() {
        if (popup.isShowing()) bar.invalidate();
    }

    private void select(int mode) {
        if (app.busy() || !app.pad.hasSelection()) return;
        app.pad.setSelectionMode(mode);
        if (bar.getWidth() > 0 && bar.getHeight() > 0)
            feedback.update(bar, new Rect(0, 0, bar.getWidth(), bar.getHeight()), this::refreshSelection, app.hasWindowFocus());
        else refreshSelection();
    }

    private void refreshSelection() {
        int current = app.pad.selectionMode();
        for (int i = 0; i < MODES.length; i++) {
            boolean selected = MODES[i] == current;
            ToolButton button = buttons[i];
            button.setBackground(Ui.outline(selected ? Color.BLACK : Color.WHITE, dp(1),
                    selected ? Color.BLACK : Color.WHITE, dp(6)));
            button.setTextColor(selected ? Color.WHITE : Color.BLACK);
            button.setSelected(selected);
            button.setContentDescription(LABELS[i] + (selected ? ", selected" : ""));
        }
    }
}
