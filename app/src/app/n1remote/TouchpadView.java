package app.n1remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Swipe pad, same place on the remote as the D-pad.
 *
 * A swipe moves exactly one item (one press + release), however long or fast it is.
 * Resting the finger still for a moment after a swipe holds that direction key down until
 * the finger lifts, so the box repeats it itself at the normal held-key pace. Repeats are
 * not generated on the phone on purpose: the Bluetooth link sits in sniff mode and delivers
 * reports in ~225 ms batches, so phone-timed repeats arrive bunched and the focus jumps.
 * A short tap is OK.
 */
public final class TouchpadView extends View {

    interface Listener {
        void onTap(int key);                // one press + release
        void onHold(int key, boolean down); // keep a key pressed until released
    }

    /** Finger still this long after a swipe = hold the key. */
    private static final long HOLD_AFTER_MS = 400;

    private final float threshold, slop, radius;
    private final Paint bg = paint(0xFF2C2C2E);
    private final Paint flash = paint(0xFF3A3A3C);
    private final Paint hint = paint(0xFF8E8E93);
    private final RectF box = new RectF();

    private Listener listener;
    private float startX, startY;
    private long downAt;
    private int dir = -1; // direction of this swipe, -1 until the finger has travelled far enough
    private boolean holding;
    private float restX, restY; // where the finger last was when it started resting
    private long flashUntil;

    private final Runnable startHold = () -> {
        if (dir < 0 || holding) return;
        holding = true;
        flash();
        if (listener != null) listener.onHold(dir, true);
    };

    public TouchpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        threshold = 32 * d;
        slop = 10 * d;
        radius = 28 * d;
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(14 * d);
    }

    void setListener(Listener l) {
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

    @Override
    protected void onDraw(Canvas c) {
        box.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(box, radius, radius, System.currentTimeMillis() < flashUntil ? flash : bg);
        float cy = getHeight() / 2f;
        c.drawText("划一下移一格", getWidth() / 2f, cy - hint.getTextSize() * 0.9f, hint);
        c.drawText("划完停住不松 = 连续移动", getWidth() / 2f, cy + hint.getTextSize() * 0.5f, hint);
        c.drawText("轻点 = OK", getWidth() / 2f, cy + hint.getTextSize() * 1.9f, hint);
    }

    private void flash() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        flashUntil = System.currentTimeMillis() + 120;
        invalidate();
        postInvalidateDelayed(130);
    }

    private void tap(int key) {
        flash();
        if (listener != null) listener.onTap(key);
    }

    private void endGesture() {
        removeCallbacks(startHold);
        if (holding && listener != null) listener.onHold(dir, false);
        holding = false;
        dir = -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The remote sits in a ScrollView; don't let it steal vertical swipes.
                getParent().requestDisallowInterceptTouchEvent(true);
                startX = x;
                startY = y;
                downAt = e.getEventTime();
                dir = -1;
                holding = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (dir < 0) {
                    float dx = x - startX, dy = y - startY;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) < threshold) return true;
                    if (Math.abs(dx) > Math.abs(dy)) dir = dx > 0 ? HidRemote.KEY_RIGHT : HidRemote.KEY_LEFT;
                    else dir = dy > 0 ? HidRemote.KEY_DOWN : HidRemote.KEY_UP;
                    tap(dir);
                    restX = x;
                    restY = y;
                    postDelayed(startHold, HOLD_AFTER_MS);
                } else if (!holding && Math.hypot(x - restX, y - restY) > slop) {
                    // Still moving: a long swipe is still one move. Restart the rest timer.
                    restX = x;
                    restY = y;
                    removeCallbacks(startHold);
                    postDelayed(startHold, HOLD_AFTER_MS);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dir < 0 && Math.hypot(x - startX, y - startY) < slop
                        && e.getEventTime() - downAt < 400) {
                    tap(HidRemote.KEY_OK);
                }
                endGesture();
                return true;
            case MotionEvent.ACTION_CANCEL:
                endGesture();
                return true;
            default:
                return true;
        }
    }
}
