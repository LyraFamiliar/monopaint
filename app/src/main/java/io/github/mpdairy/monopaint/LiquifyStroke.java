package io.github.mpdairy.monopaint;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * Forward-warp liquify brush (Push, Bloat, Pinch, Twirl right/left and Reconstruct): reshapes
 * the active layer without blending colors. Consecutive Liquify strokes on the same layer share
 * one displacement field and one session-start layer snapshot, so repeated strokes never compound
 * blur and Reconstruct can relax paint back toward the original.
 *
 * <p>Pen samples only update the field, which is cheap; the pixels are resampled in
 * {@link #settle}, which the canvas calls after each batch of samples. Consecutive batches
 * cover nearly the same pixels, so settle paces itself: after a resample that took T it
 * waits at least T more before the next, folding the batches in between into one pass. A
 * fast tablet resamples every batch; a slow one stays with the pen instead of falling behind.
 */
final class LiquifyStroke implements DrawingStroke {
    /** Displacement grid spacing in page pixels. */
    private static final int G = 4;
    /** Gentle per-step scales at 100% strength and full pressure. */
    private static final float BLOAT_SCALE = 0.04f;
    private static final float TWIRL_RADIANS = (float)Math.toRadians(3.0);
    private static final float RECONSTRUCT_RATE = 0.15f;

    /**
     * Persistent liquify session shared across consecutive Liquify strokes on the same layer.
     * Reuses page-sized buffers so pen-downs avoid allocating each time. Runs on the UI thread.
     */
    private static final class Session {
        WeakReference<ToneDocument> document = new WeakReference<>(null);
        int layer = -1, width, height, cols, rows;
        long edits;
        byte[] baseTones = new byte[0], baseAlpha = new byte[0];
        /** Output pixel p shows session-start content at p + D(p), bilinearly interpolated from this grid. */
        float[] dx = new float[0], dy = new float[0];
        /**
         * The resample pass that last moved each node: 0 for nodes never moved in this session;
         * {@link #pass} for nodes moved since the last resample.
         */
        int[] moved = new int[0];
        int pass = 1;
        boolean hasField;
        float[] scratchDx = new float[0], scratchDy = new float[0];

        boolean matches(ToneDocument doc) {
            return document.get() == doc && layer == doc.activeLayer() && edits == doc.edits()
                    && width == doc.width && height == doc.height;
        }

        void reset(ToneDocument doc, int cols, int rows) {
            document = new WeakReference<>(doc);
            layer = doc.activeLayer();
            width = doc.width; height = doc.height;
            this.cols = cols; this.rows = rows;
            edits = doc.edits();
            int size = width * height;
            if (baseTones.length < size) { baseTones = new byte[size]; baseAlpha = new byte[size]; }
            doc.copyLayer(0, 0, width, height, baseTones, baseAlpha);
            pass = 1;
            hasField = false;
        }

        void ensureField() {
            if (hasField) return;
            int cells = cols * rows;
            if (dx.length < cells) {
                dx = new float[cells];
                dy = new float[cells];
                moved = new int[cells];
            } else {
                Arrays.fill(dx, 0, cells, 0f);
                Arrays.fill(dy, 0, cells, 0f);
                Arrays.fill(moved, 0, cells, 0);
            }
            hasField = true;
        }
    }

    private static final Session session = new Session();

    private final ToneDocument document;
    private final ToolSettings settings;
    private final int width, height, cols, rows;
    /** When the last resample ended and how long it took, for pacing; nanoseconds. */
    private long settledAt, settleCost;
    private boolean started;
    private float lastX, lastY, lastRadius;
    /** Nodes moved since the last {@link #settle}: an inclusive node rectangle, empty when minX > maxX. */
    private int pendMinX = Integer.MAX_VALUE, pendMinY = Integer.MAX_VALUE, pendMaxX = -1, pendMaxY = -1;

    LiquifyStroke(ToneDocument document, ToolSettings settings) {
        this.document = document;
        this.settings = settings;
        this.width = document.width;
        this.height = document.height;
        this.cols = width / G + 2;
        this.rows = height / G + 2;
        // Liquify reshapes artwork, so it works on the layer even while gestures lay masking fluid.
        document.beginLayer();
        if (!session.matches(document)) session.reset(document, cols, rows);
    }

    @Override public void sample(float x, float y, float pressure, float tiltX, float tiltY) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) return;
        x = Math.max(-128, Math.min(width + 128, x));
        y = Math.max(-128, Math.min(height + 128, y));
        float radius = settings.diameter(pressure) / 2f;
        float p = ToolSettings.travel(pressure);
        boolean push = settings.liquify == ToolSettings.Liquify.PUSH;
        if (!started) {
            started = true; lastX = x; lastY = y; lastRadius = radius;
            if (!push && settings.strength > 0 && p > 0) applyStep(x, y, 0, 0, radius, strength(p));
            return;
        }
        float mx = x - lastX, my = y - lastY;
        float dist = (float)Math.hypot(mx, my);
        if (settings.strength > 0 && p > 0 && (dist > 0 || !push)) {
            float strength = strength(p);
            if (dist == 0) {
                applyStep(x, y, 0, 0, radius, strength);
            } else {
                float maxStep = Math.max(.125f, Math.min(lastRadius, radius) / 4f);
                int steps = Math.max(1, (int)Math.ceil(dist / maxStep));
                float stepMx = mx / steps, stepMy = my / steps;
                for (int i = 1; i <= steps; i++) {
                    float t = (float)i / steps;
                    float r = lastRadius + (radius - lastRadius) * t;
                    applyStep(lastX + mx * t, lastY + my * t, stepMx, stepMy, r, strength);
                }
            }
        }
        lastX = x; lastY = y; lastRadius = radius;
    }

    private float strength(float p) {
        // A light touch already pushes firmly. Strength eases in, tuned on a Nomad: 50% moves
        // the paint about a third of the way with the pen, 80% about three quarters.
        float s = settings.strength / 100f;
        return (float)Math.pow(s, 1.4) * (.4f + .6f * (float)Math.sqrt(p));
    }

    private void applyStep(float cx, float cy, float mx, float my, float r, float strength) {
        if (settings.liquify == ToolSettings.Liquify.RECONSTRUCT) {
            if (session.hasField) reconstructField(cx, cy, r, strength);
        } else {
            session.ensureField();
            warpField(cx, cy, mx, my, r, strength);
        }
    }

    /** Resamples what moved, unless the last resample was too recent to be worth another yet. */
    @Override public void settle() {
        if (System.nanoTime() - settledAt < settleCost) return;
        resample();
    }

    /** Resamples the pixels around every node moved since the last pass. */
    private void resample() {
        if (pendMinX > pendMaxX || pendMinY > pendMaxY) return;
        long start = System.nanoTime();
        int current = session.pass++;
        float[] dx = session.dx, dy = session.dy;
        int[] moved = session.moved;
        // A pixel blends the nodes on either side of it, so it reaches one grid step past them.
        int left = Math.max(0, (pendMinX - 1) * G), top = Math.max(0, (pendMinY - 1) * G);
        int right = Math.min(width, (pendMaxX + 1) * G), bottom = Math.min(height, (pendMaxY + 1) * G);
        pendMinX = pendMinY = Integer.MAX_VALUE; pendMaxX = pendMaxY = -1;
        for (int y = top; y < bottom; y++) {
            float py = y + .5f, gyf = py / G;
            int gy0 = (int)gyf;
            float ty = gyf - gy0, ity = 1f - ty;
            int row0 = gy0 * cols, row1 = row0 + cols;
            for (int x = left; x < right; x++) {
                float px = x + .5f, gxf = px / G;
                int gx0 = (int)gxf;
                int i00 = row0 + gx0, i10 = i00 + 1, i01 = row1 + gx0, i11 = i01 + 1;
                // Only pixels beside a node moved since the last pass can change.
                if (moved[i00] != current && moved[i10] != current && moved[i01] != current && moved[i11] != current) continue;
                float tx = gxf - gx0, itx = 1f - tx;
                float w00 = itx * ity, w10 = tx * ity, w01 = itx * ty, w11 = tx * ty;
                float ddx = dx[i00] * w00 + dx[i10] * w10 + dx[i01] * w01 + dx[i11] * w11;
                float ddy = dy[i00] * w00 + dy[i10] * w10 + dy[i01] * w01 + dy[i11] * w11;
                int pixel = sampleBase(px + ddx, py + ddy);
                if (pixel == 0) document.paintTone(x, y, 255, 0);
                else document.paintTone(x, y, pixel >>> 8, pixel & 255);
            }
        }
        if (session.pass == Integer.MAX_VALUE) {
            Arrays.fill(moved, 0, cols * rows, 0);
            session.pass = 1;
        }
        settledAt = System.nanoTime(); settleCost = settledAt - start;
    }

    @Override public boolean finish() {
        resample();
        boolean changed = document.finish();
        session.edits = document.edits();
        return changed;
    }

    /** Composes one forward-warp sub-step into the displacement field: D_new(p) = D_old(p - V) - V. */
    private void warpField(float cx, float cy, float mx, float my, float r, float strength) {
        float r2 = r * r;
        int copyMinX = Math.max(0, (int)Math.floor((cx - r) / G));
        int copyMinY = Math.max(0, (int)Math.floor((cy - r) / G));
        int copyMaxX = Math.min(cols - 1, (int)Math.floor((cx + r) / G) + 1);
        int copyMaxY = Math.min(rows - 1, (int)Math.floor((cy + r) / G) + 1);
        if (copyMinX > copyMaxX || copyMinY > copyMaxY) return;

        int cw = copyMaxX - copyMinX + 1, ch = copyMaxY - copyMinY + 1;
        int need = cw * ch;
        if (session.scratchDx.length < need) {
            session.scratchDx = new float[need];
            session.scratchDy = new float[need];
        }
        float[] dx = session.dx, dy = session.dy, scratchDx = session.scratchDx, scratchDy = session.scratchDy;
        int[] moved = session.moved;
        for (int gy = copyMinY; gy <= copyMaxY; gy++) {
            int srcOff = gy * cols + copyMinX;
            int dstOff = (gy - copyMinY) * cw;
            System.arraycopy(dx, srcOff, scratchDx, dstOff, cw);
            System.arraycopy(dy, srcOff, scratchDy, dstOff, cw);
        }

        int updMinX = Math.max(0, (int)Math.ceil((cx - r) / G));
        int updMinY = Math.max(0, (int)Math.ceil((cy - r) / G));
        int updMaxX = Math.min(cols - 1, (int)Math.floor((cx + r) / G));
        int updMaxY = Math.min(rows - 1, (int)Math.floor((cy + r) / G));
        if (updMinX > updMaxX || updMinY > updMaxY) return;
        pendMinX = Math.min(pendMinX, updMinX); pendMinY = Math.min(pendMinY, updMinY);
        pendMaxX = Math.max(pendMaxX, updMaxX); pendMaxY = Math.max(pendMaxY, updMaxY);
        float maxGridX = (cols - 1) * G, maxGridY = (rows - 1) * G;
        ToolSettings.Liquify mode = settings.liquify;
        int current = session.pass;
        for (int gy = updMinY; gy <= updMaxY; gy++) {
            float ny = gy * G, dyc = ny - cy, dyc2 = dyc * dyc;
            for (int gx = updMinX; gx <= updMaxX; gx++) {
                float nx = gx * G, dxc = nx - cx;
                float t2 = (dxc * dxc + dyc2) / r2;
                if (t2 >= 1f) continue;
                float f = 1f - t2;
                float w = strength * f * f;
                float vx, vy;
                switch (mode) {
                    case PUSH:
                        vx = w * mx; vy = w * my;
                        break;
                    case BLOAT:
                        vx = w * BLOAT_SCALE * dxc; vy = w * BLOAT_SCALE * dyc;
                        break;
                    case PINCH:
                        vx = -w * BLOAT_SCALE * dxc; vy = -w * BLOAT_SCALE * dyc;
                        break;
                    case TWIRL_RIGHT: case TWIRL_LEFT: {
                        float angle = (mode == ToolSettings.Liquify.TWIRL_RIGHT ? w : -w) * TWIRL_RADIANS;
                        float cos = (float)Math.cos(angle), sin = (float)Math.sin(angle);
                        vx = dxc * (cos - 1f) - dyc * sin;
                        vy = dxc * sin + dyc * (cos - 1f);
                        break;
                    }
                    default:
                        continue;
                }
                float qx = Math.max(0f, Math.min(maxGridX, nx - vx));
                float qy = Math.max(0f, Math.min(maxGridY, ny - vy));
                float fx = qx / G, fy = qy / G;
                int ix0 = Math.max(copyMinX, Math.min(copyMaxX, (int)Math.floor(fx)));
                int iy0 = Math.max(copyMinY, Math.min(copyMaxY, (int)Math.floor(fy)));
                int ix1 = Math.min(copyMaxX, ix0 + 1);
                int iy1 = Math.min(copyMaxY, iy0 + 1);
                float tx = Math.max(0f, Math.min(1f, fx - ix0));
                float ty = Math.max(0f, Math.min(1f, fy - iy0));
                int i00 = (iy0 - copyMinY) * cw + (ix0 - copyMinX);
                int i10 = (iy0 - copyMinY) * cw + (ix1 - copyMinX);
                int i01 = (iy1 - copyMinY) * cw + (ix0 - copyMinX);
                int i11 = (iy1 - copyMinY) * cw + (ix1 - copyMinX);
                float w00 = (1f - tx) * (1f - ty), w10 = tx * (1f - ty), w01 = (1f - tx) * ty, w11 = tx * ty;
                float oldDx = scratchDx[i00] * w00 + scratchDx[i10] * w10 + scratchDx[i01] * w01 + scratchDx[i11] * w11;
                float oldDy = scratchDy[i00] * w00 + scratchDy[i10] * w10 + scratchDy[i01] * w01 + scratchDy[i11] * w11;
                int idx = gy * cols + gx;
                dx[idx] = oldDx - vx;
                dy[idx] = oldDy - vy;
                moved[idx] = current;
            }
        }
    }

    /** Relaxes displacements inside the brush toward zero (the session-start original). */
    private void reconstructField(float cx, float cy, float r, float strength) {
        float r2 = r * r;
        int updMinX = Math.max(0, (int)Math.ceil((cx - r) / G));
        int updMinY = Math.max(0, (int)Math.ceil((cy - r) / G));
        int updMaxX = Math.min(cols - 1, (int)Math.floor((cx + r) / G));
        int updMaxY = Math.min(rows - 1, (int)Math.floor((cy + r) / G));
        if (updMinX > updMaxX || updMinY > updMaxY) return;
        float[] dx = session.dx, dy = session.dy;
        int[] moved = session.moved;
        int current = session.pass;
        boolean changed = false;
        for (int gy = updMinY; gy <= updMaxY; gy++) {
            float ny = gy * G, dyc = ny - cy, dyc2 = dyc * dyc;
            for (int gx = updMinX; gx <= updMaxX; gx++) {
                float nx = gx * G, dxc = nx - cx;
                float t2 = (dxc * dxc + dyc2) / r2;
                if (t2 >= 1f) continue;
                int idx = gy * cols + gx;
                if (dx[idx] == 0f && dy[idx] == 0f) continue;
                float f = 1f - t2;
                float keep = 1f - strength * f * f * RECONSTRUCT_RATE;
                dx[idx] *= keep;
                dy[idx] *= keep;
                moved[idx] = current;
                changed = true;
            }
        }
        if (changed) {
            pendMinX = Math.min(pendMinX, updMinX); pendMinY = Math.min(pendMinY, updMinY);
            pendMaxX = Math.max(pendMaxX, updMaxX); pendMaxY = Math.max(pendMaxY, updMaxY);
        }
    }

    /** Premultiplied bilinear sample of the session-start active layer; outside the page is transparent. */
    private int sampleBase(float srcX, float srcY) {
        float qx = srcX - .5f, qy = srcY - .5f;
        int x0 = (int)Math.floor(qx), y0 = (int)Math.floor(qy);
        int x1 = x0 + 1, y1 = y0 + 1;
        if (x1 < 0 || y1 < 0 || x0 >= width || y0 >= height) return 0;
        int p00 = basePixel(x0, y0), p10 = basePixel(x1, y0), p01 = basePixel(x0, y1), p11 = basePixel(x1, y1);
        // Most of a page is flat paper or solid paint, where blending would change nothing.
        if (p00 == p10 && p00 == p01 && p00 == p11) return (p00 & 255) == 0 ? 0 : p00;
        return ToneDocument.blendBilinear(p00, p10, p01, p11, qx - x0, qy - y0);
    }

    private int basePixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        int i = y * width + x;
        return (session.baseTones[i] & 255) << 8 | (session.baseAlpha[i] & 255);
    }
}
