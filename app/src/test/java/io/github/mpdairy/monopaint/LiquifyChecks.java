package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class LiquifyChecks {
    public static void main(String[] args) {
        pushRight();
        radiusBoundary();
        noMovement();
        exactUndo();
        transparentLayers();
        fastStrokeSubStepping();
        pageEdge();
        System.out.println("PASS: liquify forward warp, radius boundary, no-op/undo, transparent layer isolation, fast sub-stepping and page edges");
    }

    /** A small black square pushed right moves right (less than pen travel) without washing out. */
    private static void pushRight() {
        ToneDocument doc = new ToneDocument(200, 200);
        doc.begin();
        for (int y = 90; y < 110; y++) for (int x = 70; x < 90; x++) doc.paintTone(x, y, 0, 255);
        doc.finish();

        double[] before = centerOfMass(doc);
        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(64).minimum(64).strength(50);
        LiquifyStroke stroke = new LiquifyStroke(doc, settings);
        for (int x = 80; x <= 110; x += 5) stroke.sample(x, 100, .45f, 0, 0);
        check(stroke.finish(), "Rightward liquify stroke commits an edit");

        double[] after = centerOfMass(doc);
        double shiftX = after[0] - before[0], shiftY = after[1] - before[1];
        check(shiftX > 3 && shiftX < 30, "Square centre of mass moves right by less than pen travel: " + shiftX);
        check(Math.abs(shiftY) < 0.5, "Horizontal push stays vertically centered: " + shiftY);

        int darkest = 255;
        for (int y = 0; y < doc.height; y++) for (int x = 0; x < doc.width; x++)
            if (doc.opacity(x, y) > 200) darkest = Math.min(darkest, doc.tone(x, y));
        check(darkest <= 5, "Pushed black square stays dark without smudging: darkest=" + darkest);
    }

    /** Pixels farther than R plus one 4px displacement step from every sample point remain untouched. */
    private static void radiusBoundary() {
        int w = 160, h = 160;
        byte[] pattern = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pattern[y * w + x] = (byte)((x * 17 + y * 31) & 255);
        ToneDocument doc = new ToneDocument(w, h, pattern);

        float r = 20f;
        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(40).minimum(40).strength(80);
        float[][] samples = {{60, 80}, {65, 80}, {70, 80}, {75, 80}, {80, 80}};
        LiquifyStroke stroke = new LiquifyStroke(doc, settings);
        for (float[] s : samples) stroke.sample(s[0], s[1], .45f, 0, 0);
        check(stroke.finish(), "Stroke across patterned layer modifies pixels");

        int changedInside = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean outside = true;
            for (float[] s : samples) {
                double d = Math.hypot(x + .5 - s[0], y + .5 - s[1]);
                // The field blends nodes one 4px step apart, so the brush may reach that far beyond R.
                if (d <= r + 4) { outside = false; break; }
            }
            int expected = pattern[y * w + x] & 255;
            if (outside) check(doc.tone(x, y) == expected && doc.opacity(x, y) == 255,
                    "Pixel outside radius R unchanged at (" + x + "," + y + ")");
            else if (doc.tone(x, y) != expected) changedInside++;
        }
        check(changedInside > 50, "Pixels inside radius R actually changed");
    }

    /** No movement (or zero strength / invalid input) changes nothing and creates no undo step. */
    private static void noMovement() {
        byte[] pattern = new byte[96 * 96];
        for (int i = 0; i < pattern.length; i++) pattern[i] = (byte)((i * 29) & 255);
        ToneDocument doc = new ToneDocument(96, 96, pattern);
        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(48).minimum(48).strength(60);

        LiquifyStroke empty = new LiquifyStroke(doc, settings);
        check(!empty.finish() && !doc.canUndo(), "Empty stroke leaves no undo step");

        LiquifyStroke single = new LiquifyStroke(doc, settings);
        single.sample(48, 48, .45f, 0, 0);
        check(!single.finish() && !doc.canUndo(), "Single stationary sample leaves no undo step");

        LiquifyStroke repeated = new LiquifyStroke(doc, settings);
        repeated.sample(48, 48, .45f, 0, 0);
        repeated.sample(48, 48, .45f, 0, 0);
        repeated.sample(Float.NaN, 60, .45f, 0, 0);
        repeated.sample(60, Float.POSITIVE_INFINITY, .45f, 0, 0);
        repeated.sample(60, 48, Float.NaN, 0, 0);
        check(!repeated.finish() && !doc.canUndo(), "Repeated stationary and non-finite samples leave no undo step");

        LiquifyStroke zeroStrength = new LiquifyStroke(doc, settings.strength(0));
        zeroStrength.sample(32, 48, .45f, 0, 0);
        zeroStrength.sample(64, 48, .45f, 0, 0);
        check(!zeroStrength.finish() && !doc.canUndo(), "Zero strength changes nothing");

        check(Arrays.equals(pattern, doc.snapshot()), "Document pixels remained identical after no-op strokes");
    }

    /** Undo restores the exact raw layer pixels (tones and alpha) and snapshot; redo reapplies them. */
    private static void exactUndo() {
        int w = 128, h = 128;
        ToneDocument doc = new ToneDocument(w, h);
        doc.begin();
        for (int y = 30; y < 98; y++) for (int x = 30; x < 98; x++)
            doc.paintTone(x, y, (x * 3 + y * 5) & 255, ((x + y) % 3 == 0) ? 180 : 255);
        doc.finish();

        byte[] beforeTones = new byte[w * h], beforeAlpha = new byte[w * h];
        doc.copyLayer(0, 0, w, h, beforeTones, beforeAlpha);
        byte[] beforeSnap = doc.snapshot();

        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(56).minimum(56).strength(75);
        LiquifyStroke stroke = new LiquifyStroke(doc, settings);
        stroke.sample(40, 64, .45f, 0, 0);
        stroke.sample(60, 56, .45f, 0, 0);
        stroke.sample(88, 72, .45f, 0, 0);
        check(stroke.finish(), "Multi-sample stroke commits one undo step");

        byte[] afterTones = new byte[w * h], afterAlpha = new byte[w * h];
        doc.copyLayer(0, 0, w, h, afterTones, afterAlpha);
        check(!Arrays.equals(beforeTones, afterTones) || !Arrays.equals(beforeAlpha, afterAlpha), "Stroke modified raw layer");

        check(doc.undo(), "Undo succeeds");
        byte[] undoneTones = new byte[w * h], undoneAlpha = new byte[w * h];
        doc.copyLayer(0, 0, w, h, undoneTones, undoneAlpha);
        check(Arrays.equals(beforeTones, undoneTones) && Arrays.equals(beforeAlpha, undoneAlpha)
                && Arrays.equals(beforeSnap, doc.snapshot()), "Undo restores exact raw layer pixels and snapshot");

        check(doc.redo(), "Redo succeeds");
        byte[] redoneTones = new byte[w * h], redoneAlpha = new byte[w * h];
        doc.copyLayer(0, 0, w, h, redoneTones, redoneAlpha);
        check(Arrays.equals(afterTones, redoneTones) && Arrays.equals(afterAlpha, redoneAlpha), "Redo restores exact warped layer");
    }

    /** Pushing on an empty layer above a painted layer leaves the empty layer transparent and lower layer untouched. */
    private static void transparentLayers() {
        int w = 128, h = 128;
        ToneDocument doc = new ToneDocument(w, h);
        doc.begin();
        for (int y = 40; y < 88; y++) for (int x = 40; x < 88; x++) doc.paintTone(x, y, 20, 255);
        doc.finish();
        byte[] lowerTones = new byte[w * h], lowerAlpha = new byte[w * h];
        doc.copyLayer(0, 0, w, h, lowerTones, lowerAlpha);

        doc.addLayer(); // Layer 1, empty and transparent
        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(64).minimum(64).strength(80);
        LiquifyStroke stroke = new LiquifyStroke(doc, settings);
        stroke.sample(48, 64, .45f, 0, 0);
        stroke.sample(84, 64, .45f, 0, 0);
        check(!stroke.finish(), "Pushing an empty transparent layer makes no change");

        byte[] upperTones = new byte[w * h], upperAlpha = new byte[w * h];
        doc.copyLayer(0, 0, w, h, upperTones, upperAlpha);
        for (byte a : upperAlpha) check(a == 0, "Empty layer remains fully transparent");

        doc.selectLayer(0);
        byte[] checkTones = new byte[w * h], checkAlpha = new byte[w * h];
        doc.copyLayer(0, 0, w, h, checkTones, checkAlpha);
        check(Arrays.equals(lowerTones, checkTones) && Arrays.equals(lowerAlpha, checkAlpha), "Lower painted layer untouched");

        // Premultiplied sampling: pushing a black mark on a transparent layer never bleeds white halos into its pigment.
        doc.selectLayer(1);
        doc.begin();
        for (int y = 54; y < 74; y++) for (int x = 44; x < 64; x++) doc.paintTone(x, y, 0, 255);
        doc.finish();
        LiquifyStroke markPush = new LiquifyStroke(doc, settings);
        markPush.sample(54, 64, .45f, 0, 0);
        markPush.sample(74, 64, .45f, 0, 0);
        check(markPush.finish(), "Pushing a mark on a transparent layer edits it");
        doc.copyLayer(0, 0, w, h, upperTones, upperAlpha);
        for (int i = 0; i < upperAlpha.length; i++)
            if ((upperAlpha[i] & 255) > 0) check((upperTones[i] & 255) == 0, "Premultiplied sampling keeps pure black pigment without white halo");
    }

    /** A fast stroke (two samples 200px apart) sub-steps cleanly in the same direction as a slow stroke with no gap in the middle. */
    private static void fastStrokeSubStepping() {
        int w = 320, h = 160;
        // Single vertical bar dead-center at x=135..145 (100px from both stroke endpoints 40 and 240, far beyond R=24).
        ToneDocument fastDoc = centerBar(w, h, 135, 145);
        ToneDocument slowDoc = centerBar(w, h, 135, 145);
        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(48).minimum(48).strength(50);

        double beforeX = centerOfMass(fastDoc)[0];
        LiquifyStroke fast = new LiquifyStroke(fastDoc, settings);
        fast.sample(40, 80, .45f, 0, 0);
        fast.sample(240, 80, .45f, 0, 0);
        check(fast.finish(), "Fast 200px stroke commits");

        LiquifyStroke slow = new LiquifyStroke(slowDoc, settings);
        for (int x = 40; x <= 240; x += 6) slow.sample(x, 80, .45f, 0, 0);
        check(slow.finish(), "Slow stroke commits");

        double fastShift = centerOfMass(fastDoc)[0] - beforeX;
        double slowShift = centerOfMass(slowDoc)[0] - beforeX;
        check(fastShift > 5, "Fast stroke pushes middle bar right: " + fastShift);
        check(slowShift > 5, "Slow stroke pushes middle bar right: " + slowShift);
        check(Math.abs(fastShift - slowShift) < 2, "Fast and slow strokes match closely: fast=" + fastShift + " slow=" + slowShift);

        // On a smooth horizontal ramp, pushing right pulls darker left tones rightward across the entire 200px span with no gap.
        byte[] ramp = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) ramp[y * w + x] = (byte)(x * 255 / (w - 1));
        ToneDocument rampDoc = new ToneDocument(w, h, ramp);
        LiquifyStroke rampFast = new LiquifyStroke(rampDoc, settings);
        rampFast.sample(40, 80, .45f, 0, 0);
        rampFast.sample(240, 80, .45f, 0, 0);
        check(rampFast.finish(), "Fast stroke across ramp commits");
        for (int x = 50; x <= 230; x++) {
            int before = ramp[80 * w + x] & 255;
            check(rampDoc.tone(x, 80) < before, "No untouched gap along fast 200px stroke at x=" + x);
        }
    }

    /** Pushing near and across the page boundary treats outside-page pixels as transparent without error. */
    private static void pageEdge() {
        ToneDocument doc = new ToneDocument(64, 64);
        doc.begin();
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) doc.paintTone(x, y, 40, 255);
        doc.finish();
        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(40).minimum(40).strength(90);
        LiquifyStroke stroke = new LiquifyStroke(doc, settings);
        stroke.sample(0, 32, .45f, 0, 0);
        stroke.sample(20, 32, .45f, 0, 0);
        check(stroke.finish(), "Edge push commits cleanly");
        check(doc.opacity(0, 32) < 255, "Pulling inward from page edge draws in transparency");
    }

    private static ToneDocument centerBar(int w, int h, int x0, int x1) {
        ToneDocument doc = new ToneDocument(w, h);
        doc.begin();
        for (int y = 40; y < 120; y++) for (int x = x0; x < x1; x++) doc.paintTone(x, y, 0, 255);
        doc.finish();
        return doc;
    }

    private static double[] centerOfMass(ToneDocument doc) {
        double sumX = 0, sumY = 0, total = 0;
        for (int y = 0; y < doc.height; y++) for (int x = 0; x < doc.width; x++) {
            double mass = 255 - doc.compositeTone(x, y);
            if (mass > 0) { sumX += (x + .5) * mass; sumY += (y + .5) * mass; total += mass; }
        }
        return new double[]{sumX / total, sumY / total};
    }

    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
