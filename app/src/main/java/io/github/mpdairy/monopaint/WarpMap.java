package io.github.mpdairy.monopaint;

import java.util.Arrays;

/**
 * Tessellates a warped {@link FloatingSelection}'s bicubic Bézier patch into an N×N page-space
 * mesh, rasterizes it into a reusable page-to-piece coordinate buffer, and answers inverse lookups.
 * Pure Java (no Android imports) so desktop checks can exercise it directly.
 */
final class WarpMap {
    /** Barycentric slack so shared edges between adjacent mesh triangles leave no pixel gaps. */
    private static final double EPS = 1e-4;

    /** Interleaved (sx, sy) piece coordinates for the last rasterized rectangle; NaN where uncovered. */
    float[] coords = new float[0];
    int left, top, width, height;
    /** Page-space extents of all vertices in the last tessellated mesh. */
    float minX, minY, maxX, maxY;

    private float[] meshX = new float[0], meshY = new float[0];
    private double[] basis = new double[0];
    private int meshN;
    private int pieceWidth, pieceHeight;

    /** Grid subdivisions per axis for a piece of this size. */
    static int steps(int width, int height) {
        return Math.max(8, Math.min(48, Math.max(width, height) / 16));
    }

    /** Cubic Bernstein basis B_0..B_3 at {@code t} in [0, 1]. */
    static void bernstein(double t, double[] out) {
        double s = 1.0 - t;
        double s2 = s * s, t2 = t * t;
        out[0] = s2 * s;
        out[1] = 3.0 * s2 * t;
        out[2] = 3.0 * s * t2;
        out[3] = t2 * t;
    }

    /** Evaluates the 4×4 row-major bicubic Bézier patch at ({@code u}, {@code v}) into {@code out[0..1]}. */
    static void evalLocal(float[] patch, double u, double v, double[] out) {
        double[] bu = new double[4], bv = new double[4];
        bernstein(u, bu);
        bernstein(v, bv);
        double x = 0, y = 0;
        for (int i = 0; i < 4; i++) {
            double rx = 0, ry = 0;
            int row = i * 4;
            for (int j = 0; j < 4; j++) {
                int k = 2 * (row + j);
                rx += bu[j] * patch[k];
                ry += bu[j] * patch[k + 1];
            }
            x += bv[i] * rx;
            y += bv[i] * ry;
        }
        out[0] = x;
        out[1] = y;
    }

    /** Evaluates the piece's patch onto an N×N page-space vertex grid and records its extents. */
    void tessellate(FloatingSelection piece) {
        float[] patch = piece.patchPoints();
        int n = steps(piece.width, piece.height);
        int stride = n + 1;
        int count = stride * stride;
        if (meshX.length < count) {
            meshX = new float[count];
            meshY = new float[count];
        }
        if (meshN != n || basis.length < 4 * stride) {
            basis = new double[4 * stride];
            double[] tmp = new double[4];
            for (int i = 0; i <= n; i++) {
                bernstein(i / (double)n, tmp);
                System.arraycopy(tmp, 0, basis, 4 * i, 4);
            }
            meshN = n;
        }
        pieceWidth = piece.width;
        pieceHeight = piece.height;

        double cos = Math.cos(piece.angle), sin = Math.sin(piece.angle);
        double sx = piece.scaleX, sy = piece.scaleY;
        double cx = piece.centerX, cy = piece.centerY;
        double halfW = piece.width / 2.0, halfH = piece.height / 2.0;

        double[] rowX = new double[4], rowY = new double[4];
        float loX = Float.MAX_VALUE, loY = Float.MAX_VALUE;
        float hiX = -Float.MAX_VALUE, hiY = -Float.MAX_VALUE;

        for (int i = 0; i <= n; i++) {
            int bi = 4 * i;
            double bv0 = basis[bi], bv1 = basis[bi + 1], bv2 = basis[bi + 2], bv3 = basis[bi + 3];
            for (int c = 0; c < 4; c++) {
                int k0 = 2 * c, k1 = 2 * (4 + c), k2 = 2 * (8 + c), k3 = 2 * (12 + c);
                rowX[c] = bv0 * patch[k0] + bv1 * patch[k1] + bv2 * patch[k2] + bv3 * patch[k3];
                rowY[c] = bv0 * patch[k0 + 1] + bv1 * patch[k1 + 1] + bv2 * patch[k2 + 1] + bv3 * patch[k3 + 1];
            }
            int rowStart = i * stride;
            for (int j = 0; j <= n; j++) {
                int bj = 4 * j;
                double lx = basis[bj] * rowX[0] + basis[bj + 1] * rowX[1]
                        + basis[bj + 2] * rowX[2] + basis[bj + 3] * rowX[3];
                double ly = basis[bj] * rowY[0] + basis[bj + 1] * rowY[1]
                        + basis[bj + 2] * rowY[2] + basis[bj + 3] * rowY[3];
                double dx = (lx - halfW) * sx, dy = (ly - halfH) * sy;
                float px = (float)(cx + cos * dx - sin * dy);
                float py = (float)(cy + sin * dx + cos * dy);
                meshX[rowStart + j] = px;
                meshY[rowStart + j] = py;
                if (px < loX) loX = px;
                if (px > hiX) hiX = px;
                if (py < loY) loY = py;
                if (py > hiY) hiY = py;
            }
        }
        minX = loX; minY = loY; maxX = hiX; maxY = hiY;
    }

    /** Rasterizes the piece's warped mesh into {@link #coords} for the requested page rectangle. */
    void rasterize(FloatingSelection piece, int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        if (this.width == 0 || this.height == 0) return;

        tessellate(piece);
        int needed = 2 * this.width * this.height;
        if (coords.length < needed) coords = new float[needed];
        Arrays.fill(coords, 0, needed, Float.NaN);

        int n = meshN, stride = n + 1;
        double stepU = pieceWidth / (double)n, stepV = pieceHeight / (double)n;
        for (int i = 0; i < n; i++) {
            double qy0 = i * stepV, qy1 = (i + 1) * stepV;
            int r0 = i * stride, r1 = r0 + stride;
            for (int j = 0; j < n; j++) {
                double qx0 = j * stepU, qx1 = (j + 1) * stepU;
                int v00 = r0 + j, v01 = v00 + 1, v10 = r1 + j, v11 = v10 + 1;
                rasterizeTriangle(meshX[v00], meshY[v00], qx0, qy0,
                        meshX[v01], meshY[v01], qx1, qy0,
                        meshX[v11], meshY[v11], qx1, qy1);
                rasterizeTriangle(meshX[v00], meshY[v00], qx0, qy0,
                        meshX[v11], meshY[v11], qx1, qy1,
                        meshX[v10], meshY[v10], qx0, qy1);
            }
        }
    }

    private void rasterizeTriangle(double ax, double ay, double qax, double qay,
                                   double bx, double by, double qbx, double qby,
                                   double cx, double cy, double qcx, double qcy) {
        double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if (Math.abs(area) < 1e-6) return;

        int x0 = Math.max(left, (int)Math.floor(Math.min(ax, Math.min(bx, cx)) - 0.5));
        int x1 = Math.min(left + width - 1, (int)Math.ceil(Math.max(ax, Math.max(bx, cx)) - 0.5));
        int y0 = Math.max(top, (int)Math.floor(Math.min(ay, Math.min(by, cy)) - 0.5));
        int y1 = Math.min(top + height - 1, (int)Math.ceil(Math.max(ay, Math.max(by, cy)) - 0.5));
        if (x0 > x1 || y0 > y1) return;

        double invArea = 1.0 / area;
        double dw0dx = (by - cy) * invArea, dw0dy = (cx - bx) * invArea;
        double dw1dx = (cy - ay) * invArea, dw1dy = (ax - cx) * invArea;
        double px0 = x0 + 0.5, py0 = y0 + 0.5;
        double w0Row = ((cx - bx) * (py0 - by) - (px0 - bx) * (cy - by)) * invArea;
        double w1Row = ((ax - cx) * (py0 - cy) - (px0 - cx) * (ay - cy)) * invArea;

        for (int y = y0; y <= y1; y++) {
            double w0 = w0Row, w1 = w1Row;
            int idx = 2 * ((y - top) * width + (x0 - left));
            for (int x = x0; x <= x1; x++) {
                double w2 = 1.0 - w0 - w1;
                if (w0 >= -EPS && w1 >= -EPS && w2 >= -EPS) {
                    coords[idx] = (float)(w0 * qax + w1 * qbx + w2 * qcx);
                    coords[idx + 1] = (float)(w0 * qay + w1 * qby + w2 * qcy);
                }
                w0 += dw0dx;
                w1 += dw1dx;
                idx += 2;
            }
            w0Row += dw0dy;
            w1Row += dw1dy;
        }
    }

    /**
     * Finds the mesh triangle containing page point ({@code x}, {@code y}) and returns its
     * barycentric piece coordinates, or {NaN, NaN} when outside. Scans in reverse raster order
     * so folded sheets match the last-drawn (visible) layer.
     */
    float[] inverseLookup(FloatingSelection piece, float x, float y) {
        tessellate(piece);
        if (x < minX - 1 || x > maxX + 1 || y < minY - 1 || y > maxY + 1)
            return new float[]{Float.NaN, Float.NaN};

        int n = meshN, stride = n + 1;
        double stepU = pieceWidth / (double)n, stepV = pieceHeight / (double)n;
        double[] hit = new double[2];
        for (int i = n - 1; i >= 0; i--) {
            double qy0 = i * stepV, qy1 = (i + 1) * stepV;
            int r0 = i * stride, r1 = r0 + stride;
            for (int j = n - 1; j >= 0; j--) {
                double qx0 = j * stepU, qx1 = (j + 1) * stepU;
                int v00 = r0 + j, v01 = v00 + 1, v10 = r1 + j, v11 = v10 + 1;
                if (tryTriangle(x, y, meshX[v00], meshY[v00], qx0, qy0,
                        meshX[v11], meshY[v11], qx1, qy1,
                        meshX[v10], meshY[v10], qx0, qy1, hit)
                        || tryTriangle(x, y, meshX[v00], meshY[v00], qx0, qy0,
                        meshX[v01], meshY[v01], qx1, qy0,
                        meshX[v11], meshY[v11], qx1, qy1, hit)) {
                    float qx = (float)Math.max(0, Math.min(pieceWidth, hit[0]));
                    float qy = (float)Math.max(0, Math.min(pieceHeight, hit[1]));
                    return new float[]{qx, qy};
                }
            }
        }
        return new float[]{Float.NaN, Float.NaN};
    }

    private static boolean tryTriangle(double px, double py,
                                       double ax, double ay, double qax, double qay,
                                       double bx, double by, double qbx, double qby,
                                       double cx, double cy, double qcx, double qcy,
                                       double[] out) {
        double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if (Math.abs(area) < 1e-6) return false;
        double invArea = 1.0 / area;
        double w0 = ((cx - bx) * (py - by) - (px - bx) * (cy - by)) * invArea;
        if (w0 < -EPS) return false;
        double w1 = ((ax - cx) * (py - cy) - (px - cx) * (ay - cy)) * invArea;
        if (w1 < -EPS) return false;
        double w2 = 1.0 - w0 - w1;
        if (w2 < -EPS) return false;
        out[0] = w0 * qax + w1 * qbx + w2 * qcx;
        out[1] = w0 * qay + w1 * qby + w2 * qcy;
        return true;
    }

    /** Closed boundary polyline (4N page-space points, clockwise around the patch edges). */
    float[] boundary(FloatingSelection piece) {
        tessellate(piece);
        int n = meshN, stride = n + 1;
        float[] out = new float[8 * n];
        int p = 0;
        for (int j = 0; j < n; j++) { int v = j; out[p++] = meshX[v]; out[p++] = meshY[v]; }
        for (int i = 0; i < n; i++) { int v = i * stride + n; out[p++] = meshX[v]; out[p++] = meshY[v]; }
        for (int j = n; j > 0; j--) { int v = n * stride + j; out[p++] = meshX[v]; out[p++] = meshY[v]; }
        for (int i = n; i > 0; i--) { int v = i * stride; out[p++] = meshX[v]; out[p++] = meshY[v]; }
        return out;
    }
}
