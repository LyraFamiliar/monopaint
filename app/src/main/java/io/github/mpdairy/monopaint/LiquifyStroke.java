package io.github.mpdairy.monopaint;

/**
 * Forward-warp push brush: displaces the active layer along the pen's path without blending
 * colors. Accumulates a coarse per-stroke displacement field and always resamples the
 * stroke-start layer content so repeated sub-steps within a stroke do not build up blur.
 */
final class LiquifyStroke implements DrawingStroke {
    /** Displacement grid spacing in page pixels. */
    private static final int G = 4;

    private final ToneDocument document;
    private final ToolSettings settings;
    private final int width, height, cols, rows;
    /** Output pixel p shows stroke-start content at p + D(p), bilinearly interpolated from this grid. */
    private float[] dx, dy;
    private float[] scratchDx = new float[0], scratchDy = new float[0];
    private boolean started;
    private float lastX, lastY, lastRadius;

    LiquifyStroke(ToneDocument document, ToolSettings settings) {
        this.document = document;
        this.settings = settings;
        this.width = document.width;
        this.height = document.height;
        this.cols = width / G + 2;
        this.rows = height / G + 2;
        document.begin();
    }

    @Override public void sample(float x, float y, float pressure, float tiltX, float tiltY) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) return;
        x = Math.max(-128, Math.min(width + 128, x));
        y = Math.max(-128, Math.min(height + 128, y));
        float radius = settings.diameter(pressure) / 2f;
        float p = ToolSettings.travel(pressure);
        if (!started) {
            started = true; lastX = x; lastY = y; lastRadius = radius;
            return;
        }
        float mx = x - lastX, my = y - lastY;
        float dist = (float)Math.hypot(mx, my);
        if (dist > 0 && settings.strength > 0 && p > 0) {
            ensureField();
            float maxStep = Math.max(.125f, Math.min(lastRadius, radius) / 4f);
            int steps = Math.max(1, (int)Math.ceil(dist / maxStep));
            float stepMx = mx / steps, stepMy = my / steps;
            float strength = settings.strength / 100f * p;
            for (int i = 1; i <= steps; i++) {
                float t = (float)i / steps;
                float cx = lastX + mx * t, cy = lastY + my * t;
                float r = lastRadius + (radius - lastRadius) * t;
                pushField(cx, cy, stepMx, stepMy, r, strength);
            }
            float c1x = lastX + stepMx, c1y = lastY + stepMy;
            float r1 = lastRadius + (radius - lastRadius) / steps;
            // Pixels up to one grid step beyond the brush blend in nodes it moved, so redraw them too.
            renderRegion(c1x, c1y, r1 + G, x, y, radius + G);
        }
        lastX = x; lastY = y; lastRadius = radius;
    }

    @Override public boolean finish() { return document.finish(); }

    private void ensureField() {
        if (dx == null) {
            dx = new float[cols * rows];
            dy = new float[cols * rows];
        }
    }

    /** Composes one forward-warp sub-step into the displacement field: D_new(p) = D_old(p - w*m) - w*m. */
    private void pushField(float cx, float cy, float mx, float my, float r, float strength) {
        float r2 = r * r;
        int copyMinX = Math.max(0, (int)Math.floor((cx - r) / G));
        int copyMinY = Math.max(0, (int)Math.floor((cy - r) / G));
        int copyMaxX = Math.min(cols - 1, (int)Math.floor((cx + r) / G) + 1);
        int copyMaxY = Math.min(rows - 1, (int)Math.floor((cy + r) / G) + 1);
        if (copyMinX > copyMaxX || copyMinY > copyMaxY) return;

        int cw = copyMaxX - copyMinX + 1, ch = copyMaxY - copyMinY + 1;
        int need = cw * ch;
        if (scratchDx.length < need) {
            scratchDx = new float[need];
            scratchDy = new float[need];
        }
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
        float maxGridX = (cols - 1) * G, maxGridY = (rows - 1) * G;
        for (int gy = updMinY; gy <= updMaxY; gy++) {
            float ny = gy * G, dyc = ny - cy, dyc2 = dyc * dyc;
            for (int gx = updMinX; gx <= updMaxX; gx++) {
                float nx = gx * G, dxc = nx - cx;
                float t2 = (dxc * dxc + dyc2) / r2;
                if (t2 >= 1f) continue;
                float f = 1f - t2;
                float w = strength * f * f;
                float wmx = w * mx, wmy = w * my;
                float qx = Math.max(0f, Math.min(maxGridX, nx - wmx));
                float qy = Math.max(0f, Math.min(maxGridY, ny - wmy));
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
                dx[idx] = oldDx - wmx;
                dy[idx] = oldDy - wmy;
            }
        }
    }

    /** Re-renders pixels swept by the sub-steps from (ax, ay, ra) to (bx, by, rb) from stroke-start content. */
    private void renderRegion(float ax, float ay, float ra, float bx, float by, float rb) {
        int left = Math.max(0, (int)Math.floor(Math.min(ax - ra, bx - rb)));
        int top = Math.max(0, (int)Math.floor(Math.min(ay - ra, by - rb)));
        int right = Math.min(width, (int)Math.ceil(Math.max(ax + ra, bx + rb)));
        int bottom = Math.min(height, (int)Math.ceil(Math.max(ay + ra, by + rb)));
        if (left >= right || top >= bottom) return;
        float segX = bx - ax, segY = by - ay, segLen2 = segX * segX + segY * segY;
        float ra2 = ra * ra;
        for (int y = top; y < bottom; y++) {
            float py = y + .5f;
            float gyf = py / G;
            int gy0 = (int)gyf, gy1 = gy0 + 1;
            float ty = gyf - gy0, ity = 1f - ty;
            int row0 = gy0 * cols, row1 = gy1 * cols;
            for (int x = left; x < right; x++) {
                float px = x + .5f;
                if (segLen2 == 0f) {
                    float dxc = px - ax, dyc = py - ay;
                    if (dxc * dxc + dyc * dyc >= ra2) continue;
                } else {
                    float u = Math.max(0f, Math.min(1f, ((px - ax) * segX + (py - ay) * segY) / segLen2));
                    float cx = ax + segX * u, cy = ay + segY * u, r = ra + (rb - ra) * u;
                    float dxc = px - cx, dyc = py - cy;
                    if (dxc * dxc + dyc * dyc >= r * r) continue;
                }
                float gxf = px / G;
                int gx0 = (int)gxf, gx1 = gx0 + 1;
                float tx = gxf - gx0, itx = 1f - tx;
                float w00 = itx * ity, w10 = tx * ity, w01 = itx * ty, w11 = tx * ty;
                int i00 = row0 + gx0, i10 = row0 + gx1, i01 = row1 + gx0, i11 = row1 + gx1;
                float ddx = dx[i00] * w00 + dx[i10] * w10 + dx[i01] * w01 + dx[i11] * w11;
                float ddy = dy[i00] * w00 + dy[i10] * w10 + dy[i01] * w01 + dy[i11] * w11;
                int pixel = sampleBase(px + ddx, py + ddy);
                if (pixel == 0) document.paintTone(x, y, 255, 0);
                else document.paintTone(x, y, pixel >>> 8, pixel & 255);
            }
        }
    }

    /** Premultiplied bilinear sample of the stroke-start active layer; outside the page is transparent. */
    private int sampleBase(float srcX, float srcY) {
        float qx = srcX - .5f, qy = srcY - .5f;
        int x0 = (int)Math.floor(qx), y0 = (int)Math.floor(qy);
        int x1 = x0 + 1, y1 = y0 + 1;
        if (x1 < 0 || y1 < 0 || x0 >= width || y0 >= height) return 0;
        return ToneDocument.blendBilinear(
                basePixel(x0, y0), basePixel(x1, y0), basePixel(x0, y1), basePixel(x1, y1),
                qx - x0, qy - y0);
    }

    private int basePixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        return document.strokeBasePixel(x, y);
    }
}
