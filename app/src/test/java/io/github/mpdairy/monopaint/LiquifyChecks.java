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
        sessionPersistence();
        sessionResetAndUndo();
        bloatPinchTwirl();
        reconstruct();
        documentEditsCounter();
        System.out.println("PASS: liquify modes (push/bloat/pinch/twirl/reconstruct), session continuity/reset, undo, edits() counter, radius boundary, transparent layers and page edges");
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
        // Strong enough for a clear push to compare; Strength eases in, so 50% would move it little.
        ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(48).minimum(48).strength(80);

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

    /** Two push strokes in a row share one session snapshot, avoiding compounded resampling blur. */
    private static void sessionPersistence() {
        int w = 160, h = 160;
        // High-contrast vertical stripes so resampling blur is clearly measurable.
        byte[] stripes = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            stripes[y * w + x] = (byte)(((x / 2) % 2 == 0) ? 0 : 255);
        ToneDocument sameSession = new ToneDocument(w, h, stripes);
        ToneDocument brokenSession = new ToneDocument(w, h, stripes);
        ToolSettings push = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(64).minimum(64).strength(80);

        // A session keeps one field and one original, so two strokes in a row along a path give
        // exactly what one stroke along the whole path gives. Pushing back the way you came is not
        // an exact inverse of a forward warp, so it can't serve as the measure here.
        ToneDocument oneStroke = new ToneDocument(w, h, stripes);
        LiquifyStroke whole = new LiquifyStroke(oneStroke, push);
        for (int x = 60; x <= 90; x += 5) whole.sample(x, 80, .45f, 0, 0);
        check(whole.finish(), "Single whole-path push commits");
        byte[] single = layerSnapshot(oneStroke);

        // Same path split into two strokes, with a no-op stroke between that must not break the session.
        LiquifyStroke s1 = new LiquifyStroke(sameSession, push);
        for (int x = 60; x <= 75; x += 5) s1.sample(x, 80, .45f, 0, 0);
        check(s1.finish(), "First session push commits");

        LiquifyStroke noop = new LiquifyStroke(sameSession, push);
        noop.sample(80, 80, .45f, 0, 0);
        check(!noop.finish(), "Stationary push no-op commits nothing");

        LiquifyStroke s2 = new LiquifyStroke(sameSession, push);
        for (int x = 75; x <= 90; x += 5) s2.sample(x, 80, .45f, 0, 0);
        check(s2.finish(), "Second push in the same session commits");
        check(Arrays.equals(single, layerSnapshot(sameSession)),
                "Two strokes in one session match one stroke along the whole path exactly");

        // The same split path with an unrelated edit between: the second stroke starts a new
        // session and resamples already-resampled pixels, so the result differs.
        LiquifyStroke b1 = new LiquifyStroke(brokenSession, push);
        for (int x = 60; x <= 75; x += 5) b1.sample(x, 80, .45f, 0, 0);
        check(b1.finish(), "First broken-session push commits");

        brokenSession.begin();
        brokenSession.paintTone(0, 0, 128, 255);
        check(brokenSession.finish(), "Unrelated edit commits and breaks session");

        LiquifyStroke b2 = new LiquifyStroke(brokenSession, push);
        for (int x = 75; x <= 90; x += 5) b2.sample(x, 80, .45f, 0, 0);
        check(b2.finish(), "Second push after a broken session commits");
        double brokenDiff = meanAbsDiff(single, brokenSession, 20, 40, 140, 120);
        check(brokenDiff > 1, "A broken session resamples from the edited pixels: diff=" + brokenDiff);
    }

    /** Other-tool edits, undo, and layer switches break the session; undo after two strokes undoes only the second. */
    private static void sessionResetAndUndo() {
        int w = 128, h = 128;
        ToolSettings push = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(48).minimum(48).strength(85);
        ToolSettings recon = push.liquify(ToolSettings.Liquify.RECONSTRUCT).strength(100);

        // 1. Undo after two session strokes undoes only the second, and breaks the session.
        ToneDocument doc = centerBar(w, h, 56, 68);
        LiquifyStroke first = new LiquifyStroke(doc, push);
        for (int x = 60; x <= 84; x += 4) first.sample(x, 64, .45f, 0, 0);
        check(first.finish(), "First push commits");
        byte[] afterFirst = doc.snapshot();

        LiquifyStroke second = new LiquifyStroke(doc, push);
        for (int x = 60; x <= 84; x += 4) second.sample(x, 64, .45f, 0, 0);
        check(second.finish(), "Second push commits");
        check(!Arrays.equals(afterFirst, doc.snapshot()), "Second push moves pixels further");

        check(doc.undo(), "Undo reverts only the second stroke");
        check(Arrays.equals(afterFirst, doc.snapshot()), "After one undo, layer matches state after first stroke");

        // Because undo incremented edits(), a new session starts from the undone pixels (zero field).
        LiquifyStroke reconAfterUndo = new LiquifyStroke(doc, recon);
        for (int i = 0; i < 10; i++) reconAfterUndo.sample(68, 64, .45f, 0, 0);
        check(!reconAfterUndo.finish(), "Undo breaks liquify session so reconstruct sees a fresh zero field");

        // 2. An edit by another tool breaks the session.
        LiquifyStroke third = new LiquifyStroke(doc, push);
        for (int x = 60; x <= 80; x += 4) third.sample(x, 64, .45f, 0, 0);
        check(third.finish(), "Push before other-tool edit commits");
        doc.begin();
        doc.paintTone(4, 4, 100, 255);
        check(doc.finish(), "Other-tool paint commits");
        LiquifyStroke reconAfterPaint = new LiquifyStroke(doc, recon);
        for (int i = 0; i < 10; i++) reconAfterPaint.sample(68, 64, .45f, 0, 0);
        check(!reconAfterPaint.finish(), "Other-tool edit breaks liquify session");

        // 3. Layer switch breaks the session (even switching away and back).
        doc.addLayer();
        doc.selectLayer(0);
        LiquifyStroke fourth = new LiquifyStroke(doc, push);
        for (int x = 60; x <= 80; x += 4) fourth.sample(x, 64, .45f, 0, 0);
        check(fourth.finish(), "Push on layer 0 commits");
        doc.selectLayer(1);
        doc.selectLayer(0);
        LiquifyStroke reconAfterLayerSwitch = new LiquifyStroke(doc, recon);
        for (int i = 0; i < 10; i++) reconAfterLayerSwitch.sample(68, 64, .45f, 0, 0);
        check(!reconAfterLayerSwitch.finish(), "Layer switch breaks liquify session");
    }

    /** Bloat expands a dot, Pinch shrinks a disc, Twirl rotates clockwise/CCW, all acting while stationary. */
    private static void bloatPinchTwirl() {
        int w = 160, h = 160;
        // BLOAT: small centered dot grows in covered pixel count from stationary samples.
        ToneDocument bloatDoc = new ToneDocument(w, h);
        bloatDoc.begin();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            if (Math.hypot(x + .5 - 80, y + .5 - 80) <= 10) bloatDoc.paintTone(x, y, 0, 255);
        bloatDoc.finish();
        int dotBefore = coveredPixels(bloatDoc);
        ToolSettings bloat = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).liquify(ToolSettings.Liquify.BLOAT)
                .size(100).minimum(100).strength(100);
        LiquifyStroke bloatStroke = new LiquifyStroke(bloatDoc, bloat);
        for (int i = 0; i < 15; i++) bloatStroke.sample(80, 80, .45f, 0, 0);
        check(bloatStroke.finish(), "Stationary bloat commits an edit");
        int dotAfter = coveredPixels(bloatDoc);
        check(dotAfter > dotBefore * 1.4, "Bloat enlarges centered dot: before=" + dotBefore + " after=" + dotAfter);

        // PINCH: large centered disc shrinks in covered pixel count from stationary samples.
        ToneDocument pinchDoc = new ToneDocument(w, h);
        pinchDoc.begin();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            if (Math.hypot(x + .5 - 80, y + .5 - 80) <= 28) pinchDoc.paintTone(x, y, 0, 255);
        pinchDoc.finish();
        int discBefore = coveredPixels(pinchDoc);
        ToolSettings pinch = bloat.liquify(ToolSettings.Liquify.PINCH);
        LiquifyStroke pinchStroke = new LiquifyStroke(pinchDoc, pinch);
        for (int i = 0; i < 15; i++) pinchStroke.sample(80, 80, .45f, 0, 0);
        check(pinchStroke.finish(), "Stationary pinch commits an edit");
        int discAfter = coveredPixels(pinchDoc);
        check(discAfter < discBefore * 0.85, "Pinch shrinks centered disc: before=" + discBefore + " after=" + discAfter);

        // TWIRL_RIGHT: horizontal bar through centre rotates clockwise (right end moves down, y increases).
        ToneDocument cwDoc = horizontalBar(w, h, 80, 48, 112, 6);
        double rightYBefore = halfCenterY(cwDoc, 88, 118);
        ToolSettings twirlRight = bloat.liquify(ToolSettings.Liquify.TWIRL_RIGHT);
        LiquifyStroke cwStroke = new LiquifyStroke(cwDoc, twirlRight);
        for (int i = 0; i < 12; i++) cwStroke.sample(80, 80, .45f, 0, 0);
        check(cwStroke.finish(), "Stationary twirl right commits an edit");
        double rightYAfter = halfCenterY(cwDoc, 88, 118);
        double leftYAfter = halfCenterY(cwDoc, 42, 72);
        check(rightYAfter > rightYBefore + 2.0, "Twirl right rotates right end down (clockwise): before=" + rightYBefore + " after=" + rightYAfter);
        check(leftYAfter < rightYBefore - 2.0, "Twirl right rotates left end up (clockwise): after=" + leftYAfter);

        // TWIRL_LEFT: rotates counter-clockwise (right end moves up, y decreases).
        ToneDocument ccwDoc = horizontalBar(w, h, 80, 48, 112, 6);
        ToolSettings twirlLeft = bloat.liquify(ToolSettings.Liquify.TWIRL_LEFT);
        LiquifyStroke ccwStroke = new LiquifyStroke(ccwDoc, twirlLeft);
        for (int i = 0; i < 12; i++) ccwStroke.sample(80, 80, .45f, 0, 0);
        check(ccwStroke.finish(), "Stationary twirl left commits an edit");
        double ccwRightY = halfCenterY(ccwDoc, 88, 118);
        check(ccwRightY < rightYBefore - 2.0, "Twirl left rotates right end up (counter-clockwise): after=" + ccwRightY);
    }

    /** Reconstruct in the same session relaxes a pushed bar back close to the original. */
    private static void reconstruct() {
        int w = 160, h = 160;
        ToneDocument doc = centerBar(w, h, 72, 84);
        byte[] original = doc.snapshot();
        ToolSettings push = ToolSettings.defaults(ToolSettings.Tool.LIQUIFY).size(72).minimum(72).strength(90);
        LiquifyStroke pushStroke = new LiquifyStroke(doc, push);
        for (int x = 76; x <= 104; x += 4) pushStroke.sample(x, 80, .45f, 0, 0);
        check(pushStroke.finish(), "Push bar commits");
        double pushedDiff = meanAbsDiff(original, doc, 30, 30, 140, 130);
        check(pushedDiff > 5.0, "Push significantly displaces bar: diff=" + pushedDiff);

        ToolSettings recon = push.liquify(ToolSettings.Liquify.RECONSTRUCT).size(100).minimum(100).strength(100);
        LiquifyStroke reconStroke = new LiquifyStroke(doc, recon);
        for (int pass = 0; pass < 40; pass++) {
            reconStroke.sample(76, 80, .45f, 0, 0);
            reconStroke.sample(96, 80, .45f, 0, 0);
        }
        check(reconStroke.finish(), "Reconstruct commits an edit");
        double reconDiff = meanAbsDiff(original, doc, 30, 30, 140, 130);
        check(reconDiff < pushedDiff * 0.15, "Reconstruct returns bar close to original: pushed=" + pushedDiff + " recon=" + reconDiff);
    }

    /** ToneDocument.edits() increments on every committed change and not on no-ops. */
    private static void documentEditsCounter() {
        ToneDocument doc = new ToneDocument(64, 64);
        long e = doc.edits();

        // No-op finish does not increment edits().
        doc.begin();
        check(!doc.finish(), "No-op finish returns false");
        check(doc.edits() == e, "No-op finish does not increment edits()");

        // Cancel does not increment edits().
        doc.begin();
        doc.paintTone(10, 10, 0, 255);
        doc.cancel();
        check(doc.edits() == e, "Cancel does not increment edits()");

        // Committed finish increments edits().
        doc.begin();
        doc.paintTone(10, 10, 0, 255);
        check(doc.finish(), "Paint finish returns true");
        check(doc.edits() > e, "finish(true) increments edits()");
        e = doc.edits();

        // finishContinuation(true) increments edits().
        doc.begin();
        doc.paintTone(11, 10, 0, 255);
        check(doc.finishContinuation(), "Continuation finish returns true");
        check(doc.edits() > e, "finishContinuation(true) increments edits()");
        e = doc.edits();

        // undo and redo increment edits().
        check(doc.undo(), "Undo succeeds");
        check(doc.edits() > e, "undo increments edits()");
        e = doc.edits();
        check(!doc.undo(), "No-op undo returns false");
        check(doc.edits() == e, "No-op undo does not increment edits()");
        check(doc.redo(), "Redo succeeds");
        check(doc.edits() > e, "redo increments edits()");
        e = doc.edits();

        // addLayer and selectLayer to a different layer increment edits(); selecting same layer does not.
        doc.addLayer();
        check(doc.edits() > e, "addLayer increments edits()");
        e = doc.edits();
        doc.selectLayer(doc.activeLayer());
        check(doc.edits() == e, "selectLayer to same layer does not increment edits()");
        doc.selectLayer(0);
        check(doc.edits() > e, "selectLayer to different layer increments edits()");
        e = doc.edits();

        // Layer metadata/stack/expand/clear/peelMask edits increment edits().
        doc.setLayerVisible(0, false);
        check(doc.edits() > e, "setLayerVisible increments edits()");
        e = doc.edits();
        doc.setLayerVisible(0, true);
        e = doc.edits();
        doc.setLayerOpacity(0, 50);
        check(doc.edits() > e, "setLayerOpacity increments edits()");
        e = doc.edits();
        doc.renameLayer("Sketch");
        check(doc.edits() > e, "renameLayer increments edits()");
        e = doc.edits();
        doc.moveLayer(1);
        check(doc.edits() > e, "moveLayer increments edits()");
        e = doc.edits();
        doc.removeLayer();
        check(doc.edits() > e, "removeLayer increments edits()");
        e = doc.edits();
        doc.expand(4, 0, 0, 0);
        check(doc.edits() > e, "expand increments edits()");
        e = doc.edits();
        doc.paintMaskingFluid(true);
        doc.begin();
        doc.paintTone(12, 12, 0, 255);
        check(doc.finish(), "Mask paint commits");
        doc.paintMaskingFluid(false);
        e = doc.edits();
        check(doc.peelMask(), "peelMask commits");
        check(doc.edits() > e, "peelMask increments edits()");
        // Clearing an empty layer rightly changes nothing, so give the active layer a mark first.
        doc.begin();
        doc.paintTone(5, 5, 0, 255);
        check(doc.finish(), "Mark before clear commits");
        e = doc.edits();
        check(doc.clear(), "clear commits");
        check(doc.edits() > e, "clear increments edits()");
    }

    private static ToneDocument horizontalBar(int w, int h, int cy, int x0, int x1, int halfThick) {
        ToneDocument doc = new ToneDocument(w, h);
        doc.begin();
        for (int y = cy - halfThick; y < cy + halfThick; y++)
            for (int x = x0; x < x1; x++) doc.paintTone(x, y, 0, 255);
        doc.finish();
        return doc;
    }

    private static double halfCenterY(ToneDocument doc, int x0, int x1) {
        double sumY = 0, total = 0;
        for (int y = 0; y < doc.height; y++) for (int x = x0; x < x1; x++) {
            double mass = 255 - doc.compositeTone(x, y);
            if (mass > 0) { sumY += (y + .5) * mass; total += mass; }
        }
        return sumY / total;
    }

    /** Every pixel's tone, row by row, as meanAbsDiff compares them. */
    private static byte[] layerSnapshot(ToneDocument doc) {
        byte[] tones = new byte[doc.width * doc.height];
        for (int y = 0; y < doc.height; y++) for (int x = 0; x < doc.width; x++) tones[y * doc.width + x] = (byte)doc.tone(x, y);
        return tones;
    }

    private static int coveredPixels(ToneDocument doc) {
        int count = 0;
        for (int y = 0; y < doc.height; y++) for (int x = 0; x < doc.width; x++)
            if (doc.opacity(x, y) > 127) count++;
        return count;
    }

    private static double meanAbsDiff(byte[] expected, ToneDocument doc, int x0, int y0, int x1, int y1) {
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            sum += Math.abs((expected[y * doc.width + x] & 255) - doc.compositeTone(x, y));
            n++;
        }
        return (double)sum / n;
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
