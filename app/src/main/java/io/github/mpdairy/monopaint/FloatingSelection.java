package io.github.mpdairy.monopaint;

/**
 * A piece of the active layer lifted by the Lasso, or an imported picture, floating over the
 * page. It moves, scales, stretches, flips, turns and distorts; the page stays untouched
 * until {@link #place}, which lays it as one undo step.
 *
 * <p>A page point p shows piece point q where p = Pose(Local(q)), q in piece pixels:
 * {@code Local} is the projective map taking the piece rectangle's corners to a convex quad
 * in piece pixels (identity by default), and {@code Pose} is
 * center + R(angle) · diag(scaleX, scaleY) · (l − size/2). Negative scale mirrors along that axis.
 */
final class FloatingSelection {
    /** Interaction mode: affine pose transforms or local corner/edge distortion. */
    static final int TRANSFORM = 0, DISTORT = 1;
    /** What a pen-down at a point grabs: nothing, the piece itself, its turning knob, corner/edge in Transform or Distort. */
    static final int NONE = -1, MOVE = 0, ROTATE = 1, SCALE = 2, STRETCH = 6, CORNER = 10, EDGE = 14;
    /** Turns within this many degrees of a quarter turn settle on it. */
    private static final double SNAP = 4;
    final int width, height;
    /** The piece's raw tones and coverage, row by row; transparent outside what was lifted. */
    final byte[] tones, alpha;
    /** Lifted from the active layer at ({@link #cutX}, {@link #cutY}), which placing clears; an import cuts nothing. */
    final boolean lifted;
    final int cutX, cutY;
    /** Placed as a new layer above the active one rather than onto it. */
    final boolean newLayer;
    private final int pageWidth, pageHeight;
    int mode = TRANSFORM;
    float centerX, centerY, scaleX = 1, scaleY = 1;
    /** Radians, clockwise on the page. */
    double angle;
    /** The four corners of {@code Local} in piece pixels (x, y pairs); identity when equal to the piece rectangle. */
    private final float[] local;
    private final float[] startLocal = new float[8];
    // The pose when the current drag began, the held point on the opposite corner/edge, and the stretch direction.
    private float startX, startY, startScaleX, startScaleY, anchorX, anchorY, axisX, axisY;
    /** A stretch's held point in local piece coordinates, so it stays put on a distorted piece too. */
    private double anchorLocalX, anchorLocalY;
    private double startAngle;
    /**
     * The edge the knob stands beside, chosen when first shown and kept as the piece turns, so
     * the knob stays where the pen left it; -1 until then. Once inside the piece it stays inside.
     */
    private int knobEdge = -1;
    private boolean knobInside, turning;

    private FloatingSelection(int pageWidth, int pageHeight, int width, int height, byte[] tones, byte[] alpha,
                              boolean lifted, int x, int y, boolean newLayer) {
        this.pageWidth = pageWidth; this.pageHeight = pageHeight;
        this.width = width; this.height = height; this.tones = tones; this.alpha = alpha;
        this.lifted = lifted; cutX = x; cutY = y; this.newLayer = newLayer;
        centerX = x + width / 2f; centerY = y + height / 2f;
        local = new float[]{0, 0, width, 0, width, height, 0, height};
    }

    /** Switches between {@link #TRANSFORM} and {@link #DISTORT} without changing the shape. */
    void setMode(int mode) {
        if (mode != TRANSFORM && mode != DISTORT) throw new IllegalArgumentException("Invalid mode: " + mode);
        this.mode = mode;
    }

    /**
     * Lifts what the active layer has inside the closed loop through {@code count} points
     * ({@code x, y} pairs, page coordinates), or returns null when it has nothing there.
     */
    static FloatingSelection lift(ToneDocument document, float[] points, int count) {
        if (count < 3) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            minX = Math.min(minX, points[2*i]); maxX = Math.max(maxX, points[2*i]);
            minY = Math.min(minY, points[2*i+1]); maxY = Math.max(maxY, points[2*i+1]);
        }
        int left = Math.max(0, (int)Math.floor(minX)), top = Math.max(0, (int)Math.floor(minY));
        int right = Math.min(document.width, (int)Math.ceil(maxX)), bottom = Math.min(document.height, (int)Math.ceil(maxY));
        if (left >= right || top >= bottom) return null;
        int w = right - left, h = bottom - top;
        byte[] tones = new byte[w * h], alpha = new byte[w * h];
        document.copyLayer(left, top, w, h, tones, alpha);
        // Keep what lies inside the loop: pixel centers between pairs of edge crossings on their row.
        float[] crossings = new float[count];
        int l = w, t = h, r = 0, b = 0;
        for (int row = 0; row < h; row++) {
            float y = top + row + .5f;
            int found = 0;
            for (int i = 0, j = count - 1; i < count; j = i++) {
                float y0 = points[2*j+1], y1 = points[2*i+1];
                if ((y0 <= y) == (y1 <= y)) continue;
                float x0 = points[2*j], x1 = points[2*i];
                crossings[found++] = x0 + (y - y0) / (y1 - y0) * (x1 - x0);
            }
            java.util.Arrays.sort(crossings, 0, found);
            int pair = 0;
            for (int col = 0; col < w; col++) {
                float x = left + col + .5f;
                while (pair < found && crossings[pair] <= x) pair++;
                int i = row * w + col;
                if (pair % 2 == 0) { tones[i] = (byte)255; alpha[i] = 0; }
                else if (alpha[i] != 0) { l = Math.min(l, col); r = Math.max(r, col + 1); t = Math.min(t, row); b = Math.max(b, row + 1); }
            }
        }
        if (l >= r) return null;
        // Trim to what was found, so the handles hug the artwork.
        int cw = r - l, ch = b - t;
        byte[] pieceTones = new byte[cw * ch], pieceAlpha = new byte[cw * ch];
        for (int row = 0; row < ch; row++) {
            System.arraycopy(tones, (t + row) * w + l, pieceTones, row * cw, cw);
            System.arraycopy(alpha, (t + row) * w + l, pieceAlpha, row * cw, cw);
        }
        return new FloatingSelection(document.width, document.height, cw, ch, pieceTones, pieceAlpha, true, left + l, top + t, false);
    }

    /**
     * A picture to place, centered on the page and turned by {@code angle} so it stands upright
     * in the app's current orientation.
     */
    static FloatingSelection imported(ToneDocument document, int width, int height, byte[] tones, byte[] alpha,
                                      double angle, boolean newLayer) {
        if (width <= 0 || height <= 0 || tones.length != width * height || alpha.length != tones.length)
            throw new IllegalArgumentException("Invalid picture");
        FloatingSelection picture = new FloatingSelection(document.width, document.height, width, height, tones, alpha,
                false, 0, 0, newLayer);
        picture.centerX = document.width / 2f; picture.centerY = document.height / 2f;
        picture.angle = angle;
        picture.align();
        return picture;
    }

    // Geometry

    /** Whether {@link #local} differs from the piece's undistorted rectangle. */
    private boolean distorted() {
        return local[0] != 0 || local[1] != 0
                || local[2] != width || local[3] != 0
                || local[4] != width || local[5] != height
                || local[6] != 0 || local[7] != height;
    }
    /** At its own size, undistorted and at a quarter turn, keeps the piece on whole pixels so placing it copies them exactly. */
    private void align() {
        if (distorted()) return;
        double quarters = angle / (Math.PI / 2);
        if (Math.abs(scaleX) != 1 || Math.abs(scaleY) != 1 || quarters != Math.rint(quarters)) return;
        boolean sideways = Math.abs(Math.rint(quarters)) % 2 == 1;
        float across = sideways ? height : width, down = sideways ? width : height;
        centerX = Math.round(centerX - across / 2) + across / 2; centerY = Math.round(centerY - down / 2) + down / 2;
    }

    /** Applies the affine pose to local piece coordinates ({@code lx}, {@code ly}). */
    private float[] pose(double lx, double ly) {
        double c = Math.cos(angle), s = Math.sin(angle);
        double dx = (lx - width / 2.0) * scaleX, dy = (ly - height / 2.0) * scaleY;
        return new float[]{(float)(centerX + c * dx - s * dy), (float)(centerY + s * dx + c * dy)};
    }
    /** Linear part of the inverse affine pose applied to a page-space delta. */
    private double[] poseInverseDelta(double dx, double dy) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new double[]{(c * dx + s * dy) / scaleX, (-s * dx + c * dy) / scaleY};
    }
    /** Inverse of the affine pose only, mapping a page point to local piece coordinates. */
    private double[] poseInverse(double x, double y) {
        double[] d = poseInverseDelta(x - centerX, y - centerY);
        return new double[]{width / 2.0 + d[0], height / 2.0 + d[1]};
    }
    /** Row-major 3x3 inverse of the affine pose. */
    private double[] poseInverseMatrix() {
        double c = Math.cos(angle), s = Math.sin(angle);
        double a = c / scaleX, b = s / scaleX, d = -s / scaleY, e = c / scaleY;
        return new double[]{a, b, width / 2.0 - a * centerX - b * centerY,
                d, e, height / 2.0 - d * centerX - e * centerY, 0, 0, 1};
    }
    /** Row-major 3x3 homography mapping piece pixels (0..width, 0..height) to {@link #local} (Heckbert). */
    private double[] localMatrix() {
        double x0 = local[0], y0 = local[1], x1 = local[2], y1 = local[3];
        double x2 = local[4], y2 = local[5], x3 = local[6], y3 = local[7];
        double dx1 = x1 - x2, dy1 = y1 - y2, dx2 = x3 - x2, dy2 = y3 - y2;
        double sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3;
        double det = dx1 * dy2 - dx2 * dy1;
        double g = (sx * dy2 - dx2 * sy) / det;
        double h = (dx1 * sy - sx * dy1) / det;
        double a = x1 - x0 + g * x1, b = x3 - x0 + h * x3;
        double d = y1 - y0 + g * y1, e = y3 - y0 + h * y3;
        return new double[]{a / width, b / height, x0, d / width, e / height, y0, g / width, h / height, 1};
    }
    private static double[] invert3x3(double[] m) {
        double c00 = m[4] * m[8] - m[5] * m[7];
        double c01 = m[2] * m[7] - m[1] * m[8];
        double c02 = m[1] * m[5] - m[2] * m[4];
        double c10 = m[5] * m[6] - m[3] * m[8];
        double c11 = m[0] * m[8] - m[2] * m[6];
        double c12 = m[2] * m[3] - m[0] * m[5];
        double c20 = m[3] * m[7] - m[4] * m[6];
        double c21 = m[1] * m[6] - m[0] * m[7];
        double c22 = m[0] * m[4] - m[1] * m[3];
        double det = m[0] * c00 + m[1] * c10 + m[2] * c20;
        if (!Double.isFinite(det) || Math.abs(det) < 1e-12) return null;
        return new double[]{c00 / det, c01 / det, c02 / det,
                c10 / det, c11 / det, c12 / det,
                c20 / det, c21 / det, c22 / det};
    }
    private static double[] mul3x3(double[] a, double[] b) {
        double[] out = new double[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
            out[3*r+c] = a[3*r] * b[c] + a[3*r+1] * b[3+c] + a[3*r+2] * b[6+c];
        return out;
    }
    private static float[] toFloat(double[] m) {
        float[] out = new float[m.length];
        for (int i = 0; i < m.length; i++) out[i] = (float)m[i];
        return out;
    }
    /** Whether {@code q} is strictly convex, positively oriented, and at least 4px in every edge and area. */
    private boolean quadValid(float[] q) {
        for (float v : q) if (!Float.isFinite(v)) return false;
        double minEdge = Math.min(4.0, Math.min(width, height));
        double minArea = Math.min(4.0, (double)width * height);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4, k = (i + 2) % 4;
            double ax = q[2*j] - q[2*i], ay = q[2*j+1] - q[2*i+1];
            double bx = q[2*k] - q[2*j], by = q[2*k+1] - q[2*j+1];
            if (Math.hypot(ax, ay) < minEdge) return false;
            if (ax * by - ay * bx <= 1e-3) return false;
        }
        double area = 0.5 * ((q[4] - q[0]) * (q[7] - q[3]) - (q[6] - q[2]) * (q[5] - q[1]));
        return area >= minArea;
    }

    /** The page point showing piece point ({@code x}, {@code y}). */
    private float[] toPage(float x, float y) {
        if (!distorted()) return pose(x, y);
        double[] m = localMatrix();
        double w = m[6] * x + m[7] * y + m[8];
        return pose((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w);
    }
    /** The four corners on the page, from the piece's top left around its edges: x, y pairs. */
    float[] corners() {
        float[] corners = new float[8];
        for (int i = 0; i < 4; i++) {
            float[] at = pose(local[2*i], local[2*i+1]);
            corners[2*i] = at[0]; corners[2*i+1] = at[1];
        }
        return corners;
    }
    /** The middles of the four edges on the page, edge {@code i} between corners i and i + 1: x, y pairs. */
    float[] edgeMiddles() { return edgeMiddles(corners()); }
    private static float[] edgeMiddles(float[] c) {
        float[] middles = new float[8];
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            middles[2*i] = (c[2*i] + c[2*next]) / 2; middles[2*i+1] = (c[2*i+1] + c[2*next+1]) / 2;
        }
        return middles;
    }
    /**
     * The page-to-piece 3x3 homography, {a, b, c, d, e, f, g, h, i}: page pixel (x, y)'s center
     * shows piece point (a·x'+b·y'+c)/(g·x'+h·y'+i), (d·x'+e·y'+f)/(g·x'+h·y'+i), where x' = x + ½
     * and y' = y + ½. Undistorted, g = h = 0 and i = 1.
     */
    float[] inverse() {
        double[] pInv = poseInverseMatrix();
        if (!distorted()) return toFloat(pInv);
        double[] lInv = invert3x3(localMatrix());
        if (lInv == null) return new float[]{0, 0, -1, 0, 0, -1, 0, 0, 1};
        return toFloat(mul3x3(lInv, pInv));
    }
    /** The page pixels the piece covers now: left, top, right, bottom, clipped to the page; empty when off it. */
    int[] bounds() {
        float[] c = corners();
        float minX = Math.min(Math.min(c[0], c[2]), Math.min(c[4], c[6])), maxX = Math.max(Math.max(c[0], c[2]), Math.max(c[4], c[6]));
        float minY = Math.min(Math.min(c[1], c[3]), Math.min(c[5], c[7])), maxY = Math.max(Math.max(c[1], c[3]), Math.max(c[5], c[7]));
        // Ignore rounding error in a quarter turn's corners, which lie on whole pixels.
        float slack = 1e-3f;
        return new int[]{Math.max(0, (int)Math.floor(minX + slack)), Math.max(0, (int)Math.floor(minY + slack)),
                Math.min(pageWidth, (int)Math.ceil(maxX - slack)), Math.min(pageHeight, (int)Math.ceil(maxY - slack))};
    }
    /** Where the piece was lifted from, which shows as a hole until it is placed; null for an import. */
    int[] cutBounds() { return lifted ? new int[]{cutX, cutY, cutX + width, cutY + height} : null; }

    /**
     * The turning knob, {@code distance} beyond the middle of its edge: at first the edge that
     * faces up the screen, ({@code upX}, {@code upY}) on the page. It moves just inside that
     * edge, for good, once beyond it would leave {@code visible} (left, top, right, bottom: the
     * page area on screen), but not during a turn, which would take it from under the pen.
     * @return the knob and the edge's middle: x, y, x, y; then the edge, and 1 when inside
     */
    float[] knob(float upX, float upY, float distance, float[] visible) {
        float[] c = corners();
        if (knobEdge < 0) {
            float best = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float[] normal = outward(c, i);
                float facing = normal[0] * upX + normal[1] * upY;
                if (facing > best) { best = facing; knobEdge = i; }
            }
        }
        float[] knob = knobAt(c, knobEdge, distance, false);
        if (!knobInside && !turning && (knob[0] < visible[0] || knob[1] < visible[1] || knob[0] > visible[2] || knob[1] > visible[3]))
            knobInside = true;
        return knobInside ? knobAt(c, knobEdge, distance, true) : knob;
    }
    private float[] knobAt(float[] c, int edge, float distance, boolean inside) {
        float[] normal = outward(c, edge);
        int next = (edge + 1) % 4;
        float midX = (c[2*edge] + c[2*next]) / 2, midY = (c[2*edge+1] + c[2*next+1]) / 2;
        // Inside, stay within the piece's nearer half.
        if (inside) distance = -Math.min(distance, (edge % 2 == 0 ? height * Math.abs(scaleY) : width * Math.abs(scaleX)) / 2);
        return new float[]{midX + normal[0] * distance, midY + normal[1] * distance, midX, midY, edge, inside ? 1 : 0};
    }
    /** Unit normal pointing out of the piece from edge {@code i}, corner i to corner i + 1. */
    private float[] outward(float[] c, int i) {
        int next = (i + 1) % 4;
        float dx = c[2*next] - c[2*i], dy = c[2*next+1] - c[2*i+1], length = (float)Math.hypot(dx, dy);
        // Corners run clockwise when both scales share a sign, counter-clockwise when one is flipped.
        float sign = scaleX * scaleY < 0 ? -1 : 1;
        return length == 0 ? new float[]{0, -1} : new float[]{sign * dy / length, -sign * dx / length};
    }

    /**
     * What a pen-down at ({@code x}, {@code y}) grabs, given the knob (or null in Distort mode)
     * and how far, in page pixels, a handle reaches.
     */
    int hit(float x, float y, float[] knob, float reach) {
        if (mode == TRANSFORM && knob != null && Math.hypot(x - knob[0], y - knob[1]) <= reach) return ROTATE;
        float[] q = piecePoint(x, y);
        boolean inside = q[0] >= 0 && q[1] >= 0 && q[0] <= width && q[1] <= height;
        float[] c = corners();
        if (mode == DISTORT) {
            int corner = nearest(x, y, c, reach, CORNER);
            if (corner != NONE) return corner;
            int edge = nearest(x, y, edgeMiddles(c), reach, EDGE);
            if (edge != NONE) return edge;
            return inside ? MOVE : NONE;
        }
        // A small piece is all handle, so it moves instead of scaling from inside itself.
        if (inside && Math.min(width * Math.abs(scaleX), height * Math.abs(scaleY)) < 3 * reach) return MOVE;
        int corner = nearest(x, y, c, reach, SCALE);
        if (corner != NONE) return corner;
        int edge = nearest(x, y, edgeMiddles(c), reach, STRETCH);
        if (edge != NONE) return edge;
        return inside ? MOVE : NONE;
    }
    private static int nearest(float x, float y, float[] points, float reach, int base) {
        int found = NONE; double best = reach;
        for (int i = 0; i < 4; i++) {
            double distance = Math.hypot(x - points[2*i], y - points[2*i+1]);
            if (distance <= best) { best = distance; found = base + i; }
        }
        return found;
    }
    /** The piece pixel coordinate shown at page point ({@code x}, {@code y}), or NaN beyond the vanishing line. */
    float[] piecePoint(float x, float y) {
        double[] l = poseInverse(x, y);
        if (!distorted()) return new float[]{(float)l[0], (float)l[1]};
        double[] inv = invert3x3(localMatrix());
        if (inv == null) return new float[]{Float.NaN, Float.NaN};
        double w = inv[6] * l[0] + inv[7] * l[1] + inv[8];
        if (w <= 0) return new float[]{Float.NaN, Float.NaN};
        return new float[]{(float)((inv[0] * l[0] + inv[1] * l[1] + inv[2]) / w),
                (float)((inv[3] * l[0] + inv[4] * l[1] + inv[5]) / w)};
    }

    // Dragging

    /** Clamps {@code value}'s magnitude to [{@code least}, {@code limit}], keeping its sign (or {@code fallback}'s at zero). */
    private static double clampSigned(double value, double least, double limit, double fallback) {
        double sign = value == 0 ? Math.signum(fallback) : Math.signum(value);
        if (sign == 0) sign = 1;
        return sign * Math.max(least, Math.min(limit, Math.abs(value)));
    }
    /** Starts dragging {@code handle}. */
    void grab(int handle) {
        startX = centerX; startY = centerY; startScaleX = scaleX; startScaleY = scaleY; startAngle = angle;
        System.arraycopy(local, 0, startLocal, 0, 8);
        turning = handle == ROTATE;
        if (handle >= SCALE && handle < STRETCH) {
            float[] c = corners(); int opposite = (handle - SCALE + 2) % 4;
            anchorX = c[2*opposite]; anchorY = c[2*opposite+1];
        } else if (handle >= STRETCH && handle < CORNER) {
            float[] c = corners(); int edge = handle - STRETCH, opposite = (edge + 2) % 4;
            float[] middles = edgeMiddles(c);
            anchorX = middles[2*opposite]; anchorY = middles[2*opposite+1];
            double[] held = poseInverse(anchorX, anchorY);
            anchorLocalX = held[0]; anchorLocalY = held[1];
            float[] normal = outward(c, edge);
            axisX = normal[0]; axisY = normal[1];
        }
    }
    /** Follows a drag of {@code handle} that began at ({@code fromX}, {@code fromY}) and is now at ({@code x}, {@code y}). */
    void drag(int handle, float fromX, float fromY, float x, float y) {
        if (handle == MOVE) {
            // Whole-pixel moves keep an unscaled, unturned piece exactly as it was.
            centerX = startX + Math.round(x - fromX); centerY = startY + Math.round(y - fromY);
        } else if (handle == ROTATE) {
            double turned = startAngle + Math.atan2(y - startY, x - startX) - Math.atan2(fromY - startY, fromX - startX);
            double quarter = Math.PI / 2, nearest = Math.round(turned / quarter) * quarter;
            angle = Math.abs(turned - nearest) <= Math.toRadians(SNAP) ? nearest : turned;
            angle = Math.IEEEremainder(angle, 2 * Math.PI);
            if (Math.abs(angle) < 1e-9) angle = 0;
            centerX = startX; centerY = startY; align();
        } else if (handle >= SCALE && handle < STRETCH) {
            // Scale proportionally along the diagonal, holding the opposite corner; crossing it mirrors both axes.
            float gx = fromX - anchorX, gy = fromY - anchorY;
            double along = ((x - anchorX) * gx + (y - anchorY) * gy) / Math.max(1e-6, (double)gx * gx + (double)gy * gy);
            double spanX = width * Math.abs(startScaleX), spanY = height * Math.abs(startScaleY);
            double largest = Math.max(spanX, spanY), smallest = Math.min(spanX, spanY);
            double limit = 2.0 * Math.max(pageWidth, pageHeight) / largest, least = Math.max(4 / smallest, 1 / largest);
            double ratio = clampSigned(along, least, limit, 1);
            scaleX = (float)(startScaleX * ratio); scaleY = (float)(startScaleY * ratio);
            centerX = (float)(anchorX + (startX - anchorX) * ratio); centerY = (float)(anchorY + (startY - anchorY) * ratio);
        } else if (handle >= STRETCH && handle < CORNER) {
            // Stretch only along the grabbed edge's normal, holding the opposite edge; crossing it flips that axis.
            boolean horizontal = (handle - STRETCH) % 2 == 1;
            int size = horizontal ? width : height;
            float start = horizontal ? startScaleX : startScaleY;
            double span = size * Math.abs(start);
            double delta = (x - fromX) * axisX + (y - fromY) * axisY;
            double along = (span + delta) / span;
            double limit = 2.0 * Math.max(pageWidth, pageHeight) / size, least = 4.0 / size;
            double scaled = clampSigned(start * along, least, limit, start);
            if (horizontal) scaleX = (float)scaled; else scaleY = (float)scaled;
            // Solve for the center that keeps the held point where it was. Undistorted, that point
            // lies on the stretch axis and this matches scaling the center about it.
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double hx = (anchorLocalX - width / 2.0) * scaleX, hy = (anchorLocalY - height / 2.0) * scaleY;
            centerX = (float)(anchorX - (cos * hx - sin * hy)); centerY = (float)(anchorY - (sin * hx + cos * hy));        } else if (handle >= CORNER) {
            boolean edge = handle >= EDGE;
            int i = handle - (edge ? EDGE : CORNER);
            double[] d = poseInverseDelta(x - fromX, y - fromY);
            float[] next = startLocal.clone();
            shiftLocal(next, i, d);
            if (edge) shiftLocal(next, (i + 1) % 4, d);
            if (quadValid(next)) System.arraycopy(next, 0, local, 0, 8);
        }
    }
    private static void shiftLocal(float[] quad, int corner, double[] d) {
        quad[2*corner] = (float)(quad[2*corner] + d[0]);
        quad[2*corner+1] = (float)(quad[2*corner+1] + d[1]);
    }
    /** Ends a drag. */
    void release() {
        turning = false;
        // A flip carries the knob's edge to the far side; choose again the edge that faces up.
        if (Math.signum(scaleX) != Math.signum(startScaleX) || Math.signum(scaleY) != Math.signum(startScaleY)) knobEdge = -1;
    }
    /** Whether the piece would land just as it was lifted, changing nothing. */
    boolean unmoved() {
        return lifted && !distorted() && angle == 0 && scaleX == 1 && scaleY == 1
                && centerX == cutX + width / 2f && centerY == cutY + height / 2f;
    }

    // Placing

    /**
     * The piece's tone and coverage at page pixel ({@code x}, {@code y}) as placed, blended
     * between its nearest pixels, packed as tone << 8 | coverage; 0 where it leaves the page bare.
     */
    private int sample(float[] m, int x, int y) {
        float px = x + .5f, py = y + .5f;
        float w = m[6] * px + m[7] * py + m[8];
        if (w <= 0) return 0;
        float ux = (m[0] * px + m[1] * py + m[2]) / w, uy = (m[3] * px + m[4] * py + m[5]) / w;
        // The piece ends sharply at its edges, as the preview shows it; within them, blend neighbours.
        if (ux < 0 || uy < 0 || ux >= width || uy >= height) return 0;
        float qx = ux - .5f, qy = uy - .5f;
        int x0 = (int)Math.floor(qx), y0 = (int)Math.floor(qy);
        int sx0 = Math.max(0, Math.min(width - 1, x0)), sx1 = Math.max(0, Math.min(width - 1, x0 + 1));
        int sy0 = Math.max(0, Math.min(height - 1, y0)), sy1 = Math.max(0, Math.min(height - 1, y0 + 1));
        return ToneDocument.blendBilinear(rawPixel(sx0, sy0), rawPixel(sx1, sy0), rawPixel(sx0, sy1), rawPixel(sx1, sy1),
                qx - x0, qy - y0);
    }
    private int rawPixel(int x, int y) { int i = y * width + x; return (tones[i] & 255) << 8 | (alpha[i] & 255); }

    /**
     * Lays the piece on the page as one undo step: onto the active layer, which first loses
     * what was lifted, or as a new layer above it.
     * @return whether the page changed
     */
    boolean place(ToneDocument document) {
        if (unmoved()) return false;
        float[] m = inverse();
        int[] area = bounds();
        if (newLayer) {
            byte[] layerTones = new byte[document.width * document.height], layerAlpha = new byte[layerTones.length];
            java.util.Arrays.fill(layerTones, (byte)255);
            for (int y = area[1]; y < area[3]; y++) for (int x = area[0]; x < area[2]; x++) {
                int pixel = sample(m, x, y);
                if (pixel == 0) continue;
                layerTones[y * document.width + x] = (byte)(pixel >>> 8); layerAlpha[y * document.width + x] = (byte)pixel;
            }
            document.addLayer(layerTones, layerAlpha);
            return true;
        }
        document.beginLayer();
        if (lifted) for (int row = 0; row < height; row++) for (int col = 0; col < width; col++)
            if (alpha[row * width + col] != 0) document.paintTone(cutX + col, cutY + row, 255, 0);
        for (int y = area[1]; y < area[3]; y++) for (int x = area[0]; x < area[2]; x++) {
            int pixel = sample(m, x, y);
            if (pixel != 0) document.overTone(x, y, pixel >>> 8, pixel & 255);
        }
        return document.finish();
    }
}
