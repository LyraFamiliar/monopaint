package io.github.mpdairy.monopaint;

/** Drawable for each tool and tool variant. */
final class ToolIcons {
    private ToolIcons() { }

    /** The tool's generic icon, used before a variant is chosen and in Settings. */
    static int of(ToolSettings.Tool tool) {
        switch (tool) {
            case WATERCOLOR: return R.drawable.ic_watercolor;
            case FLAT_WASH: return R.drawable.ic_flat_wash;
            case WET_WATERCOLOR: return R.drawable.ic_wet_watercolor;
            case AIRBRUSH: return R.drawable.ic_airbrush;
            case SHAPES: return R.drawable.ic_shapes;
            case PENCIL: return R.drawable.ic_pencil;
            case FILL: return R.drawable.ic_fill;
            case ERASER: return R.drawable.ic_eraser;
            case SOFTEN: return R.drawable.ic_soften;
            case BRUSH: return R.drawable.ic_brush;
            case BRUSH_PEN: return R.drawable.ic_brush_pen;
            case WET_BRUSH_PEN: return R.drawable.ic_wet_brush_pen;
            case LASSO: return R.drawable.ic_lasso;
            case LIQUIFY: return R.drawable.ic_liquify;
            default: throw new IllegalArgumentException("No icon for " + tool);
        }
    }

    /** The icon for these exact settings: brush head, shape, fill type or liquify mode. */
    static int of(ToolSettings settings) {
        settings = settings.asBrush();
        if (settings.isBrush() && settings.head != ToolSettings.Head.ROUND)
            return settings.head == ToolSettings.Head.FLAT ? R.drawable.ic_brush_flat : R.drawable.ic_brush_filbert;
        if (settings.tool == ToolSettings.Tool.SHAPES) return shape(settings.shape);
        if (settings.tool == ToolSettings.Tool.FILL) return fill(settings.gradient);
        if (settings.tool == ToolSettings.Tool.LIQUIFY && settings.liquify != ToolSettings.Liquify.PUSH)
            return liquify(settings.liquify);
        return of(settings.tool);
    }

    static int liquify(ToolSettings.Liquify mode) {
        switch (mode) {
            case PUSH: return R.drawable.ic_liquify_push;
            case BLOAT: return R.drawable.ic_liquify_bloat;
            case PINCH: return R.drawable.ic_liquify_pinch;
            case TWIRL_RIGHT: return R.drawable.ic_liquify_twirl_right;
            case TWIRL_LEFT: return R.drawable.ic_liquify_twirl_left;
            case RECONSTRUCT: return R.drawable.ic_liquify_reconstruct;
            default: throw new IllegalArgumentException("Unknown liquify mode: " + mode);
        }
    }

    static int shape(ToolSettings.Shape shape) {
        switch (shape) {
            case LINE: return R.drawable.ic_shape_line;
            case RECTANGLE: return R.drawable.ic_shape_rectangle;
            case SQUARE: return R.drawable.ic_shape_square;
            case OVAL: return R.drawable.ic_shape_oval;
            case CIRCLE: return R.drawable.ic_shape_circle;
            default: throw new IllegalArgumentException("Unknown shape: " + shape);
        }
    }

    static int tip(ToolSettings.Head head) {
        return head == ToolSettings.Head.FLAT ? R.drawable.ic_tip_flat
                : head == ToolSettings.Head.FILBERT ? R.drawable.ic_tip_filbert : R.drawable.ic_tip_round;
    }

    static int fill(ToolSettings.Gradient type) {
        return type == ToolSettings.Gradient.FLAT ? R.drawable.ic_fill
                : type == ToolSettings.Gradient.LINEAR ? R.drawable.ic_fill_linear : R.drawable.ic_fill_circular;
    }
}
