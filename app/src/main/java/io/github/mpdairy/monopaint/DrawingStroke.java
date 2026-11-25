package io.github.mpdairy.monopaint;

interface DrawingStroke {
    void sample(float x, float y, float pressure, float tiltX, float tiltY);
    /**
     * Brings the page up to date after a batch of samples, before the canvas draws it. A stroke
     * whose samples are cheap but whose drawing is costly defers that work to here.
     */
    default void settle() { }
    boolean finish();
    /** Wet paint holds still while this stroke is drawn, and moves once it is finished. */
    default boolean holdsWetPaint() { return false; }
}
