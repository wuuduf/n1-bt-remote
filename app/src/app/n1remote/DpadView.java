package app.n1remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Round D-pad with OK in the middle, like the original remote. Reports press and release. */
public final class DpadView extends View {

    interface KeyListener {
        void onKey(int key, boolean down);
    }

    private static final int NONE = -1;

    private final Paint ring = paint(0xFF2C2C2E);
    private final Paint ringDown = paint(0xFF4A4A4E);
    private final Paint gap = paint(0xFF1C1C1E);
    private final Paint center = paint(0xFF3A3A3C);
    private final Paint centerDown = paint(0xFF5C5C60);
    private final Paint arrow = paint(0xFFB0B0B5);
    private final Paint text = paint(0xFFFFFFFF);
    private final RectF oval = new RectF();
    private final Path tri = new Path();

    private int down = NONE;
    private KeyListener listener;

    public DpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    void setKeyListener(KeyListener l) {
        listener = l;
    }

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = getDefaultSize(getSuggestedMinimumWidth(), widthSpec);
        int h = getDefaultSize(getSuggestedMinimumHeight(), heightSpec);
        int s = Math.min(w, h);
        setMeasuredDimension(s, s);
    }

    private float cx() { return getWidth() / 2f; }
    private float cy() { return getHeight() / 2f; }
    private float outer() { return Math.min(cx(), cy()) - 2; }
    private float inner() { return outer() * 0.40f; }

    @Override
    protected void onDraw(Canvas c) {
        float cx = cx(), cy = cy(), R = outer(), r = inner();
        c.drawCircle(cx, cy, R, ring);
        if (down >= HidRemote.KEY_UP && down <= HidRemote.KEY_RIGHT) {
            oval.set(cx - R, cy - R, cx + R, cy + R);
            c.drawArc(oval, startAngle(down), 90, true, ringDown);
        }
        c.drawCircle(cx, cy, r * 1.14f, gap);
        c.drawCircle(cx, cy, r, down == HidRemote.KEY_OK ? centerDown : center);

        float m = (R + r * 1.14f) / 2f, t = R * 0.07f;
        triangle(c, cx, cy - m, 0, -1, t);
        triangle(c, cx, cy + m, 0, 1, t);
        triangle(c, cx - m, cy, -1, 0, t);
        triangle(c, cx + m, cy, 1, 0, t);

        text.setTextSize(r * 0.5f);
        c.drawText("OK", cx, cy - (text.descent() + text.ascent()) / 2f, text);
    }

    private static float startAngle(int key) {
        switch (key) {
            case HidRemote.KEY_UP: return 225;
            case HidRemote.KEY_RIGHT: return 315;
            case HidRemote.KEY_DOWN: return 45;
            default: return 135; // LEFT
        }
    }

    /** Triangle at (x, y) pointing in direction (dx, dy). */
    private void triangle(Canvas c, float x, float y, int dx, int dy, float t) {
        tri.reset();
        tri.moveTo(x + dx * t, y + dy * t);
        tri.lineTo(x - dx * t * 0.6f + dy * t, y - dy * t * 0.6f + dx * t);
        tri.lineTo(x - dx * t * 0.6f - dy * t, y - dy * t * 0.6f - dx * t);
        tri.close();
        c.drawPath(tri, arrow);
    }

    private int zone(float x, float y) {
        float dx = x - cx(), dy = y - cy();
        double d = Math.hypot(dx, dy);
        if (d <= inner() * 1.07f) return HidRemote.KEY_OK;
        if (d > outer()) return NONE;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? HidRemote.KEY_RIGHT : HidRemote.KEY_LEFT;
        return dy > 0 ? HidRemote.KEY_DOWN : HidRemote.KEY_UP;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int k = zone(e.getX(), e.getY());
                if (k == NONE) return false;
                getParent().requestDisallowInterceptTouchEvent(true); // keep a held key held
                down = k;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) listener.onKey(k, true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (down != NONE) {
                    int k = down;
                    down = NONE;
                    if (listener != null) listener.onKey(k, false);
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }
}
