package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class SelectionChecks {
    public static void main(String[] args) {
        liftAndMove(); unmovedPlacesNothing(); loopShape(); quarterTurn(); scaleHoldsCorner();
        stretchAxis(); flipEdge(); flipCorner(); handles(); flippedKnob();
        importAsLayer(); importOntoLayer(); emptyLoop(); importedGrays();
        undistortedInverse(); distortCorner(); distortTrapezoid(); skewEdge();
        distortRejectsInvalid(); distortWithPose(); distortUnmovedAndMode(); distortHandles(); stretchDistortedHoldsEdge();
        warpSwitchChangesNothing(); warpZeroDragReproducesUnwarped(); warpPullBendsSurface();
        warpPointMovesControlPoint(); warpMapCoverage(); distortOnWarpedPiece(); warpHandles(); poseOpsOnWarpedPiece();
        System.out.println("Selection checks passed");
    }
    private static float[] box(float l, float t, float r, float b) { return new float[]{l, t, r, t, r, b, l, b}; }
    /** Stretching an edge of a distorted piece in Transform mode holds the opposite edge's middle still. */
    private static void stretchDistortedHoldsEdge() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(10, 10, 70, 60), 4);
        piece.setMode(FloatingSelection.DISTORT);
        float[] c = piece.corners();
        // Pull corner 0 (on the left edge) down and in, so that edge's middle leaves the piece's horizontal axis.
        piece.grab(FloatingSelection.CORNER);
        piece.drag(FloatingSelection.CORNER, c[0], c[1], c[0] + 6, c[1] + 8);
        piece.release();
        piece.setMode(FloatingSelection.TRANSFORM);
        float[] mid = piece.edgeMiddles();
        piece.grab(FloatingSelection.STRETCH + 1);
        piece.drag(FloatingSelection.STRETCH + 1, mid[2], mid[3], mid[2] + 15, mid[3]);
        piece.release();
        float[] after = piece.edgeMiddles();
        check(Math.abs(after[6] - mid[6]) < 1e-3 && Math.abs(after[7] - mid[7]) < 1e-3,
                "Stretching a distorted piece holds the opposite edge's middle");
        check(piece.scaleX > 1, "Stretch widened the distorted piece");
    }
    private static ToneDocument square() {
        ToneDocument doc = new ToneDocument(120, 100);
        doc.begin();
        for (int y = 20; y < 40; y++) for (int x = 30; x < 50; x++) doc.paintTone(x, y, 40 + x - 30);
        doc.finish();
        return doc;
    }
    private static void liftAndMove() {
        ToneDocument doc = square(); doc.addLayer(); doc.selectLayer(0);
        byte[] before = doc.snapshot();
        FloatingSelection piece = FloatingSelection.lift(doc, box(10, 10, 70, 60), 4);
        check(piece != null && piece.width == 20 && piece.height == 20, "Handles hug the lifted artwork");
        check(Arrays.equals(before, doc.snapshot()), "Lifting leaves the page untouched");
        piece.grab(FloatingSelection.MOVE);
        piece.drag(FloatingSelection.MOVE, 40, 30, 50.4f, 35.6f);
        check(piece.place(doc), "Moved piece is placed");
        check(doc.opacity(30, 20) == 0 && doc.opacity(39, 25) == 0, "Lifted area is cleared");
        check(doc.layerTone(40, 26) == 40 && doc.layerTone(59, 45) == 59 && doc.opacity(40, 26) == 255, "Moves by whole pixels exactly");
        check(doc.layerCount() == 2 && doc.activeLayer() == 0, "Stays on its layer");
        byte[] after = doc.snapshot();
        check(doc.undo() && Arrays.equals(before, doc.snapshot()), "One undo puts the piece back");
        check(doc.redo() && Arrays.equals(after, doc.snapshot()), "Redo moves it again");
    }
    private static void unmovedPlacesNothing() {
        ToneDocument doc = square();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        check(!piece.place(doc), "Unmoved piece changes nothing");
        int steps = 0; while (doc.undo()) steps++;
        check(steps == 1, "An unmoved piece adds no undo step");
    }
    private static void loopShape() {
        ToneDocument doc = square();
        // A triangle over the square's top-left half.
        FloatingSelection piece = FloatingSelection.lift(doc, new float[]{30, 20, 50, 20, 30, 40}, 3);
        piece.grab(FloatingSelection.MOVE); piece.drag(FloatingSelection.MOVE, 0, 0, 60, 0);
        piece.place(doc);
        check(doc.opacity(31, 21) == 0 && doc.opacity(48, 38) == 255, "Only pixels inside the loop lift");
        check(doc.opacity(91, 21) == 255 && doc.opacity(108, 38) == 0, "The loop's shape moves");
    }
    private static void quarterTurn() {
        ToneDocument doc = new ToneDocument(60, 60);
        doc.begin(); doc.paintTone(20, 20, 10); doc.paintTone(21, 20, 20); doc.paintTone(22, 20, 30);
        doc.paintTone(20, 21, 40); doc.paintTone(21, 21, 50); doc.paintTone(22, 21, 60); doc.finish();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 60, 60), 4);
        float[] knob = piece.knob(0, -1, 10, new float[]{0, 0, 60, 60});
        piece.grab(FloatingSelection.ROTATE);
        // A turn just short of a quarter settles on it.
        float cx = piece.centerX, cy = piece.centerY;
        piece.drag(FloatingSelection.ROTATE, cx, cy - 10, cx + 10, cy - 0.5f);
        piece.release();
        check(piece.angle == Math.PI / 2, "Turns snap to a quarter turn");
        float[] after = piece.knob(0, -1, 10, new float[]{0, 0, 60, 60});
        check(after[0] > piece.centerX + 5 && after[5] == 0, "The knob turns with the piece, staying where the pen left it");
        piece.place(doc);
        int[] expected = {40, 10, 50, 20, 60, 30};
        int found = 0;
        for (int y = 0; y < 60; y++) for (int x = 0; x < 60; x++) if (doc.opacity(x, y) != 0) {
            check(doc.opacity(x, y) == 255, "Quarter turns keep full coverage");
            check(doc.layerTone(x, y) == expected[found++], "Quarter turns keep every pixel");
        }
        check(found == 6, "Quarter turn moves no pixel twice");
    }
    private static void scaleHoldsCorner() {
        ToneDocument doc = square();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        float[] c = piece.corners();
        piece.grab(FloatingSelection.SCALE + 2);
        piece.drag(FloatingSelection.SCALE + 2, c[4], c[5], c[4] + 20, c[5] + 20);
        float[] after = piece.corners();
        check(Math.abs(piece.scaleX - 2) < 1e-4 && Math.abs(piece.scaleY - 2) < 1e-4
                && Math.abs(after[0] - c[0]) < 1e-3 && Math.abs(after[1] - c[1]) < 1e-3,
                "Scaling holds the opposite corner still");
        piece.place(doc);
        check(doc.opacity(30, 20) == 255 && doc.opacity(68, 58) == 255 && doc.opacity(71, 61) == 0, "Scaled piece covers twice the size");
    }
    private static void stretchAxis() {
        ToneDocument doc = square();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        float[] before = piece.corners(), mid = piece.edgeMiddles();
        // Drag right edge (edge 1) outward by 20px: widens only x, holds left edge (corners 0 and 3).
        piece.grab(FloatingSelection.STRETCH + 1);
        piece.drag(FloatingSelection.STRETCH + 1, mid[2], mid[3], mid[2] + 20, mid[3] + 15);
        piece.release();
        float[] after = piece.corners();
        check(Math.abs(piece.scaleX - 2) < 1e-4 && Math.abs(piece.scaleY - 1) < 1e-4, "Right edge stretches only x");
        check(Math.abs(after[0] - before[0]) < 1e-3 && Math.abs(after[1] - before[1]) < 1e-3
                && Math.abs(after[6] - before[6]) < 1e-3 && Math.abs(after[7] - before[7]) < 1e-3,
                "Stretching right edge holds left edge still");
        check(Math.abs(after[2] - (before[2] + 20)) < 1e-3 && Math.abs(after[3] - before[3]) < 1e-3,
                "Right edge moves by the perpendicular drag distance");

        // Rotate a quarter turn clockwise so edge 1 faces down: stretching edge 1 stretches page-vertically.
        FloatingSelection turned = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        turned.grab(FloatingSelection.ROTATE);
        turned.drag(FloatingSelection.ROTATE, turned.centerX, turned.centerY - 10, turned.centerX + 10, turned.centerY);
        turned.release();
        check(Math.abs(turned.angle - Math.PI / 2) < 1e-6, "Quarter turn before stretch");
        float[] rBefore = turned.corners(), rMid = turned.edgeMiddles();
        turned.grab(FloatingSelection.STRETCH + 1);
        turned.drag(FloatingSelection.STRETCH + 1, rMid[2], rMid[3], rMid[2] + 10, rMid[3] + 20);
        turned.release();
        float[] rAfter = turned.corners();
        check(Math.abs(turned.scaleX - 2) < 1e-4 && Math.abs(turned.scaleY - 1) < 1e-4,
                "Rotated right edge still stretches only piece x");
        check(Math.abs(rAfter[0] - rBefore[0]) < 1e-3 && Math.abs(rAfter[1] - rBefore[1]) < 1e-3
                && Math.abs(rAfter[6] - rBefore[6]) < 1e-3 && Math.abs(rAfter[7] - rBefore[7]) < 1e-3,
                "Rotated stretch holds the opposite edge still on the page");
        check(Math.abs(rAfter[2] - rBefore[2]) < 1e-3 && Math.abs(rAfter[3] - (rBefore[3] + 20)) < 1e-3,
                "Quarter-turned right edge stretches page-vertically");
    }
    private static void flipEdge() {
        ToneDocument doc = square();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        float[] before = piece.corners(), mid = piece.edgeMiddles();
        piece.grab(FloatingSelection.STRETCH + 1);
        // Right onto the opposite edge: never reaches zero size.
        piece.drag(FloatingSelection.STRETCH + 1, mid[2], mid[3], mid[6], mid[7]);
        check(piece.scaleX > 0 && Math.abs(piece.scaleX * piece.width - 4) < 1e-3, "Edge drag clamps to minimum size at zero");
        // Drag past the left edge by the piece's own width: exact 1:1 horizontal flip.
        piece.drag(FloatingSelection.STRETCH + 1, mid[2], mid[3], mid[6] - piece.width, mid[7]);
        piece.release();
        float[] after = piece.corners();
        check(Math.abs(piece.scaleX + 1) < 1e-4 && Math.abs(piece.scaleY - 1) < 1e-4, "Dragging edge past opposite edge flips x");
        check(Math.abs(after[0] - before[0]) < 1e-3 && Math.abs(after[6] - before[6]) < 1e-3, "Flipping holds the opposite edge");
        check(!piece.unmoved(), "A flipped piece counts as moved");
        check(piece.place(doc), "Flipped piece places");
        for (int y = 20; y < 40; y++) for (int x = 0; x < 20; x++) {
            check(doc.opacity(30 + x, y) == 0, "Original area cleared after flip");
            check(doc.opacity(10 + x, y) == 255, "Flipped pixel has full coverage");
            check(doc.layerTone(10 + x, y) == 59 - x, "1:1 horizontal flip mirrors pixels exactly");
        }
    }
    private static void flipCorner() {
        ToneDocument doc = square();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        // Stretch x to 2 first so we also check ratio preservation through the flip.
        float[] mid = piece.edgeMiddles();
        piece.grab(FloatingSelection.STRETCH + 1);
        piece.drag(FloatingSelection.STRETCH + 1, mid[2], mid[3], mid[2] + 20, mid[3]);
        piece.release();
        float[] c = piece.corners();
        piece.grab(FloatingSelection.SCALE + 2);
        // Through the opposite corner (corner 0) to the exact mirrored point.
        piece.drag(FloatingSelection.SCALE + 2, c[4], c[5], c[0] - (c[4] - c[0]), c[1] - (c[5] - c[1]));
        piece.release();
        float[] after = piece.corners();
        check(Math.abs(piece.scaleX + 2) < 1e-4 && Math.abs(piece.scaleY + 1) < 1e-4,
                "Corner drag through anchor flips both axes and keeps aspect ratio");
        check(Math.abs(after[0] - c[0]) < 1e-3 && Math.abs(after[1] - c[1]) < 1e-3,
                "Corner flip holds the anchor corner still");
    }
    private static void handles() {
        ToneDocument doc = square();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        piece.grab(FloatingSelection.SCALE + 2);
        piece.drag(FloatingSelection.SCALE + 2, 50, 40, 80, 70);
        piece.release();
        float[] knob = piece.knob(0, -1, 8, new float[]{0, 0, 120, 100});
        float[] middles = piece.edgeMiddles();
        check(Math.abs(knob[0] - 55) < 1e-3 && Math.abs(knob[1] - 12) < 1e-3, "Knob stands above the top edge");
        check(piece.hit(55, 13, knob, 4) == FloatingSelection.ROTATE, "Knob turns");
        check(piece.hit(79, 69, knob, 4) == FloatingSelection.SCALE + 2, "Corner scales");
        for (int i = 0; i < 4; i++)
            check(piece.hit(middles[2*i], middles[2*i+1], knob, 4) == FloatingSelection.STRETCH + i, "Edge middle stretches");
        check(piece.hit(55, 50, knob, 4) == FloatingSelection.MOVE, "Inside moves");
        check(piece.hit(5, 5, knob, 4) == FloatingSelection.NONE, "Outside finishes");
        FloatingSelection top = FloatingSelection.lift(square(), box(30, 20, 50, 23), 4);
        float[] inside = top.knob(0, -1, 30, new float[]{0, 0, 120, 100});
        check(inside[1] > 20 && inside[1] < 23 && inside[0] == 40 && inside[5] == 1, "Knob moves inside a piece whose top edge is at the top of the screen");
        check(top.knob(0, -1, 30, new float[]{0, -100, 120, 100})[5] == 1, "Once inside, the knob stays inside");
        // A turn that carries the knob off the screen leaves it under the pen until the pen lifts.
        FloatingSelection middle = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        float[] screen = {25, 0, 120, 100};
        check(middle.knob(0, -1, 8, screen)[5] == 0, "Knob starts outside where there is room");
        middle.grab(FloatingSelection.ROTATE);
        middle.drag(FloatingSelection.ROTATE, 40, 12, 32, 30);
        float[] turned = middle.knob(0, -1, 8, screen);
        check(turned[5] == 0 && turned[0] < 25, "Knob stays outside during a turn");
        middle.release();
        check(middle.knob(0, -1, 8, screen)[5] == 1, "Knob moves inside once the turn ends");
    }
    private static void flippedKnob() {
        float[] screen = {0, 0, 120, 100};
        // Knob already placed above top edge, then flipped horizontally: must stay outside above the top edge.
        FloatingSelection horiz = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        check(horiz.knob(0, -1, 8, screen)[1] < 20, "Knob starts above top edge");
        float[] mid = horiz.edgeMiddles();
        horiz.grab(FloatingSelection.STRETCH + 1);
        horiz.drag(FloatingSelection.STRETCH + 1, mid[2], mid[3], mid[6] - horiz.width, mid[7]);
        horiz.release();
        float[] afterHoriz = horiz.knob(0, -1, 8, screen);
        check(afterHoriz[5] == 0 && Math.abs(afterHoriz[0] - 20) < 1e-3 && Math.abs(afterHoriz[1] - 12) < 1e-3,
                "Knob stays outside the top edge when flipped horizontally");
        // Fresh piece flipped vertically before choosing knob edge: picks the edge facing up and stands outside it.
        FloatingSelection vert = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        float[] vMid = vert.edgeMiddles();
        vert.grab(FloatingSelection.STRETCH + 2);
        vert.drag(FloatingSelection.STRETCH + 2, vMid[4], vMid[5], vMid[0], vMid[1] - vert.height);
        vert.release();
        float[] afterVert = vert.knob(0, -1, 8, new float[]{0, -20, 120, 100});
        check(afterVert[5] == 0 && afterVert[4] == 2 && Math.abs(afterVert[0] - 40) < 1e-3 && Math.abs(afterVert[1] + 8) < 1e-3,
                "Knob stands outside the edge facing up when flipped vertically");
        // Without room above, the inside knob stays inside the piece using absolute size.
        float[] insideVert = vert.knob(0, -1, 8, screen);
        check(insideVert[5] == 1 && insideVert[1] > 0 && insideVert[1] < 20,
                "Inside knob stays inside a flipped piece");
        // Knob shown first, then flipped vertically: it moves back above the piece rather than below it.
        FloatingSelection shown = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        float[] room = {0, -20, 120, 100};
        check(shown.knob(0, -1, 8, room)[1] < 20, "Knob starts above the piece");
        float[] sMid = shown.edgeMiddles();
        shown.grab(FloatingSelection.STRETCH + 2);
        shown.drag(FloatingSelection.STRETCH + 2, sMid[4], sMid[5], sMid[0], sMid[1] - shown.height);
        shown.release();
        float[] afterShown = shown.knob(0, -1, 8, room);
        check(afterShown[5] == 0 && afterShown[1] < 0, "After a vertical flip the knob is above the piece again");
    }
    private static void importAsLayer() {
        ToneDocument doc = square();
        byte[] before = doc.snapshot();
        byte[] tones = new byte[4 * 3], alpha = new byte[12];
        Arrays.fill(tones, (byte)0); Arrays.fill(alpha, (byte)255); alpha[0] = 0; tones[0] = (byte)255;
        FloatingSelection picture = FloatingSelection.imported(doc, 4, 3, tones, alpha, 0, true);
        check(picture.bounds()[0] == 58 && picture.bounds()[1] == 49 && picture.bounds()[2] == 62, "Pictures arrive centered on whole pixels");
        check(picture.place(doc) && doc.layerCount() == 2 && doc.activeLayer() == 1, "Picture is placed on a new layer");
        check(doc.layerTone(59, 49) == 0 && doc.opacity(58, 49) == 0, "Transparent picture pixels stay clear");
        check(doc.undo() && doc.layerCount() == 1 && Arrays.equals(before, doc.snapshot()), "One undo removes the new layer");
        byte[] wide = new byte[6];
        FloatingSelection turned = FloatingSelection.imported(doc, 3, 2, wide, new byte[6], Math.PI / 2, true);
        int[] b = turned.bounds();
        check(b[2] - b[0] == 2 && b[3] - b[1] == 3, "A turned picture stands upright on the turned screen");
    }
    private static void importOntoLayer() {
        ToneDocument doc = square();
        byte[] tones = new byte[4], alpha = new byte[4];
        Arrays.fill(alpha, (byte)128);
        FloatingSelection picture = FloatingSelection.imported(doc, 2, 2, tones, alpha, 0, false);
        check(picture.place(doc) && doc.layerCount() == 1, "Picture is placed onto the current layer");
        check(doc.opacity(60, 50) == 128 && doc.layerTone(60, 50) == 127, "Translucent picture pixels lie over bare paper");
        check(doc.layerTone(40, 30) == 50, "Artwork outside the picture stays");
    }
    private static void emptyLoop() {
        ToneDocument doc = square();
        check(FloatingSelection.lift(doc, box(80, 60, 110, 90), 4) == null, "A loop around nothing lifts nothing");
        check(FloatingSelection.lift(doc, new float[]{1, 1, 5, 5}, 2) == null, "A line is not a loop");
    }
    private static void importedGrays() {
        for (int tone = 0; tone < 256; tone++) {
            int exported = DotPattern.exportGray(tone);
            check(DotPattern.exportGray(DotPattern.importGray(exported)) == exported, "An exported gray imports as the tone it came from");
        }
        check(DotPattern.importGray(0) == 0 && DotPattern.importGray(255) == 255, "Black and white import unchanged");
    }
    private static void undistortedInverse() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.grab(FloatingSelection.SCALE + 2);
        piece.drag(FloatingSelection.SCALE + 2, 50, 40, 70, 60);
        piece.release();
        piece.grab(FloatingSelection.ROTATE);
        piece.drag(FloatingSelection.ROTATE, piece.centerX, piece.centerY - 10, piece.centerX + 10, piece.centerY);
        piece.release();
        float[] m = piece.inverse();
        check(m.length == 9, "Inverse returns 9 floats");
        check(m[6] == 0f && m[7] == 0f && m[8] == 1f, "Undistorted inverse has g=h=0, i=1");
        double c = Math.cos(piece.angle), s = Math.sin(piece.angle);
        double a = c / piece.scaleX, b = s / piece.scaleX, d = -s / piece.scaleY, e = c / piece.scaleY;
        float[] expected = {(float)a, (float)b, (float)(piece.width / 2.0 - a * piece.centerX - b * piece.centerY),
                (float)d, (float)e, (float)(piece.height / 2.0 - d * piece.centerX - e * piece.centerY)};
        for (int i = 0; i < 6; i++)
            check(m[i] == expected[i], "Undistorted inverse matches affine entry " + i);
    }
    private static void distortCorner() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.DISTORT);
        float[] before = piece.corners();
        piece.grab(FloatingSelection.CORNER + 2);
        piece.drag(FloatingSelection.CORNER + 2, before[4], before[5], before[4] + 15, before[5] + 10);
        piece.release();
        float[] after = piece.corners();
        check(Math.abs(after[4] - (before[4] + 15)) < 1e-3 && Math.abs(after[5] - (before[5] + 10)) < 1e-3,
                "Dragged corner follows the pen");
        for (int i : new int[]{0, 1, 3})
            check(Math.abs(after[2*i] - before[2*i]) < 1e-3 && Math.abs(after[2*i+1] - before[2*i+1]) < 1e-3,
                    "Undragged corner " + i + " stays put");
        float[][] expectedPiece = {{0, 0}, {piece.width, 0}, {piece.width, piece.height}, {0, piece.height}};
        float[] inv = piece.inverse();
        for (int i = 0; i < 4; i++) {
            float px = after[2*i], py = after[2*i+1];
            float[] q = piece.piecePoint(px, py);
            check(Math.abs(q[0] - expectedPiece[i][0]) < 1e-3 && Math.abs(q[1] - expectedPiece[i][1]) < 1e-3,
                    "piecePoint maps page corner " + i + " back to piece corner");
            float w = inv[6] * px + inv[7] * py + inv[8];
            float ux = (inv[0] * px + inv[1] * py + inv[2]) / w;
            float uy = (inv[3] * px + inv[4] * py + inv[5]) / w;
            check(w > 0 && Math.abs(ux - expectedPiece[i][0]) < 1e-3 && Math.abs(uy - expectedPiece[i][1]) < 1e-3,
                    "inverse maps page corner " + i + " back to piece corner");
        }
    }
    private static void distortTrapezoid() {
        ToneDocument doc = new ToneDocument(120, 100);
        doc.begin();
        for (int y = 20; y < 60; y++) for (int x = 30; x < 70; x++) doc.paintTone(x, y, 50);
        doc.finish();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.DISTORT);
        float[] c = piece.corners();
        // Narrow top edge from 40px to 16px: move corner 0 right by 12, corner 1 left by 12.
        piece.grab(FloatingSelection.CORNER + 0);
        piece.drag(FloatingSelection.CORNER + 0, c[0], c[1], c[0] + 12, c[1]);
        piece.release();
        piece.grab(FloatingSelection.CORNER + 1);
        piece.drag(FloatingSelection.CORNER + 1, c[2], c[3], c[2] - 12, c[3]);
        piece.release();
        check(piece.place(doc), "Trapezoid places");
        int topRow = 0, bottomRow = 0, total = 0;
        for (int y = 0; y < 100; y++) for (int x = 0; x < 120; x++) if (doc.opacity(x, y) > 0) {
            total++;
            if (y == 22) topRow++;
            if (y == 57) bottomRow++;
        }
        check(topRow > 0 && bottomRow > topRow + 10, "Top rows of trapezoid have fewer covered pixels than bottom rows");
        double expectedArea = (16 + 40) * 0.5 * 40; // 1120
        check(Math.abs(total - expectedArea) / expectedArea < 0.05, "Trapezoid coverage matches area within 5%");
    }
    private static void skewEdge() {
        // Vertical bar: 6px wide, 30px tall at x=30..36, y=20..50.
        ToneDocument doc = new ToneDocument(120, 100);
        doc.begin();
        for (int y = 20; y < 50; y++) for (int x = 30; x < 36; x++) doc.paintTone(x, y, 40);
        doc.finish();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.DISTORT);
        float[] before = piece.corners(), mid = piece.edgeMiddles();
        piece.grab(FloatingSelection.EDGE + 0);
        piece.drag(FloatingSelection.EDGE + 0, mid[0], mid[1], mid[0] + 20, mid[1]);
        piece.release();
        float[] after = piece.corners();
        check(Math.abs(after[0] - (before[0] + 20)) < 1e-3 && Math.abs(after[1] - before[1]) < 1e-3
                && Math.abs(after[2] - (before[2] + 20)) < 1e-3 && Math.abs(after[3] - before[3]) < 1e-3,
                "Skewing top edge shifts corners 0 and 1 by 20px");
        check(Math.abs(after[4] - before[4]) < 1e-3 && Math.abs(after[5] - before[5]) < 1e-3
                && Math.abs(after[6] - before[6]) < 1e-3 && Math.abs(after[7] - before[7]) < 1e-3,
                "Skewing top edge leaves corners 2 and 3 still");
        check(piece.place(doc), "Skewed bar places");
        double topMean = rowCenterX(doc, 21), bottomMean = rowCenterX(doc, 48);
        check(topMean > bottomMean + 15, "Skewing shears vertical bar so top shifts right relative to bottom");
    }
    private static double rowCenterX(ToneDocument doc, int y) {
        double sum = 0; int count = 0;
        for (int x = 0; x < doc.width; x++) if (doc.opacity(x, y) > 0) { sum += x; count++; }
        check(count > 0, "Row " + y + " has pixels");
        return sum / count;
    }
    private static void distortRejectsInvalid() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.DISTORT);
        float[] c = piece.corners();
        piece.grab(FloatingSelection.CORNER + 0);
        // First drag to a valid slight inset.
        piece.drag(FloatingSelection.CORNER + 0, c[0], c[1], c[0] + 3, c[1] + 3);
        float[] lastValid = piece.corners();
        check(Math.abs(lastValid[0] - (c[0] + 3)) < 1e-3 && Math.abs(lastValid[1] - (c[1] + 3)) < 1e-3,
                "Valid distort drag applies");
        // Now drag corner 0 past the diagonal between corner 1 and corner 3 -> non-convex.
        piece.drag(FloatingSelection.CORNER + 0, c[0], c[1], c[4] - 2, c[5] - 2);
        float[] afterInvalid = piece.corners();
        for (int i = 0; i < 8; i++)
            check(afterInvalid[i] == lastValid[i], "Non-convex drag is rejected and keeps last valid quad");
        piece.release();
    }
    private static void distortWithPose() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.DISTORT);
        float[] c0 = piece.corners();
        piece.grab(FloatingSelection.CORNER + 2);
        piece.drag(FloatingSelection.CORNER + 2, c0[4], c0[5], c0[4] + 12, c0[5] + 6);
        piece.release();
        piece.setMode(FloatingSelection.TRANSFORM);
        float[] beforeMove = piece.corners();
        piece.grab(FloatingSelection.MOVE);
        piece.drag(FloatingSelection.MOVE, 40, 30, 70, 40);
        piece.release();
        float[] afterMove = piece.corners();
        for (int i = 0; i < 4; i++)
            check(Math.abs(afterMove[2*i] - (beforeMove[2*i] + 30)) < 1e-3
                    && Math.abs(afterMove[2*i+1] - (beforeMove[2*i+1] + 10)) < 1e-3,
                    "TRANSFORM MOVE shifts all distorted corners by (30, 10)");
        // Rotate by a quarter turn: all pairwise distances between corners stay equal.
        piece.grab(FloatingSelection.ROTATE);
        piece.drag(FloatingSelection.ROTATE, piece.centerX, piece.centerY - 10, piece.centerX + 10, piece.centerY);
        piece.release();
        check(Math.abs(piece.angle - Math.PI / 2) < 1e-6, "Distorted piece snaps to quarter turn");
        float[] afterTurn = piece.corners();
        for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) {
            double dBefore = Math.hypot(afterMove[2*j] - afterMove[2*i], afterMove[2*j+1] - afterMove[2*i+1]);
            double dAfter = Math.hypot(afterTurn[2*j] - afterTurn[2*i], afterTurn[2*j+1] - afterTurn[2*i+1]);
            check(Math.abs(dBefore - dAfter) < 1e-3, "Quarter turn preserves distorted corner distances");
        }
    }
    private static void distortUnmovedAndMode() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        check(piece.unmoved(), "Fresh piece is unmoved");
        float[] beforeSwitch = piece.corners();
        piece.setMode(FloatingSelection.DISTORT);
        check(Arrays.equals(beforeSwitch, piece.corners()), "Switching to DISTORT does not change corners");
        piece.grab(FloatingSelection.CORNER + 1);
        piece.drag(FloatingSelection.CORNER + 1, beforeSwitch[2], beforeSwitch[3], beforeSwitch[2] + 5, beforeSwitch[3]);
        piece.release();
        check(!piece.unmoved(), "Distorted piece is not unmoved");
        float[] distortedCorners = piece.corners();
        piece.setMode(FloatingSelection.TRANSFORM);
        check(Arrays.equals(distortedCorners, piece.corners()), "Switching back to TRANSFORM does not change corners");
    }
    private static void distortHandles() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        float[] knob = piece.knob(0, -1, 8, new float[]{0, 0, 120, 100});
        piece.setMode(FloatingSelection.DISTORT);
        float[] c = piece.corners(), m = piece.edgeMiddles();
        for (int i = 0; i < 4; i++) {
            check(piece.hit(c[2*i], c[2*i+1], knob, 4) == FloatingSelection.CORNER + i,
                    "DISTORT hit returns CORNER+" + i);
            check(piece.hit(m[2*i], m[2*i+1], knob, 4) == FloatingSelection.EDGE + i,
                    "DISTORT hit returns EDGE+" + i);
        }
        check(piece.hit(piece.centerX, piece.centerY, knob, 4) == FloatingSelection.MOVE,
                "DISTORT hit inside returns MOVE");
        check(piece.hit(knob[0], knob[1], knob, 4) == FloatingSelection.NONE,
                "DISTORT hit at knob position returns NONE");
        check(piece.hit(5, 5, knob, 4) == FloatingSelection.NONE,
                "DISTORT hit outside returns NONE");
    }
    private static void warpSwitchChangesNothing() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        float[] cornersBefore = piece.corners();
        int[] boundsBefore = piece.bounds();
        check(piece.unmoved(), "Fresh piece starts unmoved");
        piece.setMode(FloatingSelection.WARP);
        check(!piece.warped(), "Switching to WARP does not create patch");
        check(Arrays.equals(cornersBefore, piece.corners()), "Switching to WARP leaves corners unchanged");
        check(Arrays.equals(boundsBefore, piece.bounds()), "Switching to WARP leaves bounds unchanged");
        check(piece.unmoved(), "Switching to WARP leaves unmoved unchanged");
    }
    private static void warpZeroDragReproducesUnwarped() {
        ToneDocument unwarpedDoc = new ToneDocument(120, 100);
        unwarpedDoc.begin();
        for (int y = 20; y < 60; y++) for (int x = 30; x < 70; x++)
            unwarpedDoc.paintTone(x, y, 20 + (x - 30) * 3 + (y - 20) * 2);
        unwarpedDoc.finish();

        ToneDocument warpedDoc = new ToneDocument(120, 100);
        warpedDoc.begin();
        for (int y = 20; y < 60; y++) for (int x = 30; x < 70; x++)
            warpedDoc.paintTone(x, y, 20 + (x - 30) * 3 + (y - 20) * 2);
        warpedDoc.finish();

        // Move both identically so both place() calls write fresh pixels onto bare paper.
        FloatingSelection unwarped = FloatingSelection.lift(unwarpedDoc, box(0, 0, 120, 100), 4);
        unwarped.grab(FloatingSelection.MOVE);
        unwarped.drag(FloatingSelection.MOVE, 50, 40, 65, 50);
        unwarped.release();
        check(unwarped.place(unwarpedDoc), "Unwarped moved piece places");

        FloatingSelection warped = FloatingSelection.lift(warpedDoc, box(0, 0, 120, 100), 4);
        warped.grab(FloatingSelection.MOVE);
        warped.drag(FloatingSelection.MOVE, 50, 40, 65, 50);
        warped.release();
        warped.setMode(FloatingSelection.WARP);
        warped.grab(FloatingSelection.PULL);
        warped.drag(FloatingSelection.PULL, 65, 50, 65, 50);
        warped.release();
        check(warped.warped() && !warped.unmoved(), "Zero warp drag initializes patch");
        check(warped.place(warpedDoc), "Zero-drag warped piece places");

        int differing = 0;
        for (int y = 0; y < warpedDoc.height; y++) for (int x = 0; x < warpedDoc.width; x++) {
            if (warpedDoc.opacity(x, y) != unwarpedDoc.opacity(x, y)
                    || (warpedDoc.opacity(x, y) > 0 && warpedDoc.layerTone(x, y) != unwarpedDoc.layerTone(x, y))) {
                differing++;
            }
        }
        int area = warped.width * warped.height;
        check(differing < area * 0.02, "Zero-drag warp matches unwarped placement (<2% diff), got " + differing);
    }
    private static void warpPullBendsSurface() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.WARP);
        float cx = piece.centerX, cy = piece.centerY;
        piece.grab(FloatingSelection.PULL);
        piece.drag(FloatingSelection.PULL, cx, cy, cx, cy + 15);
        piece.release();
        float[] after = piece.toPage(piece.width / 2f, piece.height / 2f);
        check(Math.hypot(after[0] - cx, after[1] - (cy + 15)) < 0.5,
                "Pulled centre point follows pen within 0.5px");

        // Horizontal bar: 60px wide, 10px tall at x=20..80, y=30..40.
        ToneDocument bar = new ToneDocument(120, 100);
        bar.begin();
        for (int y = 30; y < 40; y++) for (int x = 20; x < 80; x++) bar.paintTone(x, y, 40);
        bar.finish();
        FloatingSelection barPiece = FloatingSelection.lift(bar, box(0, 0, 120, 100), 4);
        barPiece.setMode(FloatingSelection.WARP);
        barPiece.grab(FloatingSelection.PULL);
        barPiece.drag(FloatingSelection.PULL, 50, 35, 50, 50);
        barPiece.release();
        check(barPiece.place(bar), "Pulled bar places");
        int midLow = lowestCoveredRow(bar, 50);
        int leftLow = lowestCoveredRow(bar, 22);
        int rightLow = lowestCoveredRow(bar, 77);
        check(midLow > leftLow + 5 && midLow > rightLow + 5,
                "Pulled horizontal bar bends lower in the middle than at its ends");
    }
    private static int lowestCoveredRow(ToneDocument doc, int x) {
        for (int y = doc.height - 1; y >= 0; y--) if (doc.opacity(x, y) > 0) return y;
        throw new AssertionError("Column " + x + " has no covered pixels");
    }
    private static void warpPointMovesControlPoint() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.WARP);
        float[] before = piece.controlPoints();
        int[] boundsBefore = piece.bounds();
        // Small interior move keeps the patch within its original rectangle (padded by 1px).
        piece.grab(FloatingSelection.POINT + 5);
        piece.drag(FloatingSelection.POINT + 5, before[10], before[11], before[10] + 2, before[11] + 3);
        piece.release();
        float[] afterSmall = piece.controlPoints();
        check(Math.abs(afterSmall[10] - (before[10] + 2)) < 1e-3 && Math.abs(afterSmall[11] - (before[11] + 3)) < 1e-3,
                "Dragging control point 5 moves it by the local delta");
        for (int k = 0; k < 16; k++) if (k != 5) {
            check(Math.abs(afterSmall[2*k] - before[2*k]) < 1e-3 && Math.abs(afterSmall[2*k+1] - before[2*k+1]) < 1e-3,
                    "Undragged control point " + k + " stays put");
        }
        int[] boundsSmall = piece.bounds();
        check(boundsSmall[0] >= boundsBefore[0] - 1 && boundsSmall[1] >= boundsBefore[1] - 1
                && boundsSmall[2] <= boundsBefore[2] + 1 && boundsSmall[3] <= boundsBefore[3] + 1,
                "Small interior point drag keeps bounds within original padded rectangle");

        // Large drag pushes the surface past the bottom edge (B_1(1/3)^2 = 16/81) and expands bounds()[3].
        piece.grab(FloatingSelection.POINT + 5);
        piece.drag(FloatingSelection.POINT + 5, afterSmall[10], afterSmall[11], afterSmall[10], afterSmall[11] + 120);
        piece.release();
        int[] boundsBig = piece.bounds();
        check(boundsBig[3] > boundsBefore[3] + 5, "Large interior point drag expands bottom bound");
    }
    private static void warpMapCoverage() {
        ToneDocument doc = new ToneDocument(140, 120);
        doc.begin();
        for (int y = 20; y < 60; y++) for (int x = 30; x < 70; x++) doc.paintTone(x, y, 50);
        doc.finish();
        FloatingSelection piece = FloatingSelection.lift(doc, box(0, 0, 140, 120), 4);
        piece.setMode(FloatingSelection.WARP);
        piece.grab(FloatingSelection.PULL);
        piece.drag(FloatingSelection.PULL, 50, 40, 56, 52);
        piece.release();
        float[] cp = piece.controlPoints();
        piece.grab(FloatingSelection.POINT + 10);
        piece.drag(FloatingSelection.POINT + 10, cp[20], cp[21], cp[20] + 8, cp[21] - 6);
        piece.release();

        int[] b = piece.bounds();
        WarpMap map = new WarpMap();
        map.rasterize(piece, b[0], b[1], b[2] - b[0], b[3] - b[1]);
        float[] firstBuffer = map.coords;
        for (int vi = 3; vi <= 17; vi++) for (int ui = 3; ui <= 17; ui++) {
            float u = ui / 20f, v = vi / 20f;
            float[] p = piece.toPage(u * piece.width, v * piece.height);
            int px = (int)Math.floor(p[0]), py = (int)Math.floor(p[1]);
            check(px >= map.left && px < map.left + map.width && py >= map.top && py < map.top + map.height,
                    "Interior warped point lies inside bounds");
            int idx = 2 * ((py - map.top) * map.width + (px - map.left));
            float sx = map.coords[idx], sy = map.coords[idx + 1];
            check(Float.isFinite(sx) && Float.isFinite(sy) && sx >= 0 && sx < piece.width && sy >= 0 && sy < piece.height,
                    "WarpMap has no NaN holes strictly inside at (" + px + "," + py + ")");
        }
        // Buffer is reused (grow-only) across frames.
        map.rasterize(piece, b[0], b[1], Math.max(1, (b[2] - b[0]) / 2), Math.max(1, (b[3] - b[1]) / 2));
        check(map.coords == firstBuffer, "WarpMap reuses its coordinate buffer without reallocating");
    }
    private static void distortOnWarpedPiece() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.WARP);
        piece.grab(FloatingSelection.PULL);
        piece.drag(FloatingSelection.PULL, 40, 30, 40, 34);
        piece.release();

        piece.setMode(FloatingSelection.DISTORT);
        float[] before = piece.controlPoints();
        float[] c = piece.corners();
        piece.grab(FloatingSelection.CORNER + 0);
        piece.drag(FloatingSelection.CORNER + 0, c[0], c[1], c[0] + 10, c[1] - 6);
        piece.release();
        float[] after = piece.controlPoints();
        for (int k = 0; k < 16; k++) {
            boolean moved = k == 0 || k == 1 || k == 4;
            float dx = moved ? 10 : 0, dy = moved ? -6 : 0;
            check(Math.abs(after[2*k] - (before[2*k] + dx)) < 1e-3
                    && Math.abs(after[2*k+1] - (before[2*k+1] + dy)) < 1e-3,
                    "Distort corner 0 on warped piece updates control point " + k + " as expected");
        }
        // Dragging an edge in DISTORT mode moves all 4 control points of that edge.
        float[] beforeEdge = piece.controlPoints(), mid = piece.edgeMiddles();
        piece.grab(FloatingSelection.EDGE + 0);
        piece.drag(FloatingSelection.EDGE + 0, mid[0], mid[1], mid[0] + 5, mid[1] + 7);
        piece.release();
        float[] afterEdge = piece.controlPoints();
        for (int k = 0; k < 16; k++) {
            boolean moved = k < 4;
            float dx = moved ? 5 : 0, dy = moved ? 7 : 0;
            check(Math.abs(afterEdge[2*k] - (beforeEdge[2*k] + dx)) < 1e-3
                    && Math.abs(afterEdge[2*k+1] - (beforeEdge[2*k+1] + dy)) < 1e-3,
                    "Distort edge 0 on warped piece updates control point " + k + " as expected");
        }
    }
    private static void warpHandles() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        float[] knob = piece.knob(0, -1, 8, new float[]{0, 0, 120, 100});
        piece.setMode(FloatingSelection.WARP);
        float[] cp = piece.controlPoints();
        for (int k = 0; k < 16; k++) {
            check(piece.hit(cp[2*k], cp[2*k+1], knob, 3) == FloatingSelection.POINT + k,
                    "WARP hit returns POINT+" + k + " on control point");
        }
        check(piece.hit(piece.centerX, piece.centerY, knob, 3) == FloatingSelection.PULL,
                "WARP hit inside returns PULL");
        check(piece.hit(knob[0], knob[1], knob, 3) == FloatingSelection.NONE,
                "WARP hit at knob returns NONE");
        check(piece.hit(5, 5, knob, 3) == FloatingSelection.NONE,
                "WARP hit outside returns NONE");

        // After warping, hit() still finds control points, interior PULL, and outside NONE.
        piece.grab(FloatingSelection.PULL);
        piece.drag(FloatingSelection.PULL, piece.centerX, piece.centerY, piece.centerX, piece.centerY + 12);
        piece.release();
        float[] warpedCp = piece.controlPoints();
        for (int k = 0; k < 16; k++) {
            check(piece.hit(warpedCp[2*k], warpedCp[2*k+1], null, 2.5f) == FloatingSelection.POINT + k,
                    "Warped hit returns POINT+" + k);
        }
        float[] warpedCenter = piece.toPage(piece.width / 2f, piece.height / 2f);
        check(piece.hit(warpedCenter[0], warpedCenter[1], null, 2.5f) == FloatingSelection.PULL,
                "Warped hit at pulled centre returns PULL");
        check(piece.hit(5, 5, null, 2.5f) == FloatingSelection.NONE,
                "Warped hit outside returns NONE");
    }
    private static void poseOpsOnWarpedPiece() {
        FloatingSelection piece = FloatingSelection.lift(square(), box(0, 0, 120, 100), 4);
        piece.setMode(FloatingSelection.WARP);
        piece.grab(FloatingSelection.PULL);
        piece.drag(FloatingSelection.PULL, 40, 30, 40, 42);
        piece.release();

        piece.setMode(FloatingSelection.TRANSFORM);
        float[] before = piece.corners();
        piece.grab(FloatingSelection.MOVE);
        piece.drag(FloatingSelection.MOVE, 40, 30, 65, 20);
        piece.release();
        float[] after = piece.corners();
        for (int i = 0; i < 4; i++) {
            check(Math.abs(after[2*i] - (before[2*i] + 25)) < 1e-3
                    && Math.abs(after[2*i+1] - (before[2*i+1] - 10)) < 1e-3,
                    "MOVE by (25, -10) shifts warped corner " + i + " by exactly that");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
