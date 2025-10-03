package co.tinode.tindroid.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import co.tinode.tindroid.R;

public class GradientTitleTextView extends AppCompatTextView {
    private int[] colors = new int[]{
            0xFF42A5F5, // kék
            0xFF7E57C2, // lila
            0xFFFF4081, // pink
            0xFF42A5F5  // vissza a kékhez, hogy szépen zárjon
    };
    private float speed = 40f; // px/s
    private LinearGradient gradient;
    private final Matrix matrix = new Matrix();
    private float translate;
    private ValueAnimator animator;

    public GradientTitleTextView(Context c) { super(c); init(null); }
    public GradientTitleTextView(Context c, @Nullable AttributeSet a) { super(c, a); init(a); }
    public GradientTitleTextView(Context c, @Nullable AttributeSet a, int s) { super(c, a, s); init(a); }

    private void init(@Nullable AttributeSet attrs) {
        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.GradientTitleTextView);
            speed = ta.getFloat(R.styleable.GradientTitleTextView_gtvSpeed, speed);
            ta.recycle();
        }
        setWillNotDraw(false);
        setIncludeFontPadding(false);
        setSingleLine(true);
        startAnim();
    }

    public void setColors(int[] c) {
        if (c != null && c.length >= 2) {
            colors = c;
            gradient = null;
            invalidate();
        }
    }

    public void setSpeed(float pxPerSecond) { this.speed = Math.max(5f, pxPerSecond); }

    private void startAnim() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setDuration(1000L / 60L); // frame trigger
        animator.addUpdateListener(a -> {
            translate += (speed / 60f);
            invalidate();
        });
        animator.start();
    }

    private void stopAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); startAnim(); }
    @Override protected void onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow(); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            gradient = new LinearGradient(
                    0, 0, w, 0,
                    colors, null, Shader.TileMode.MIRROR);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (gradient != null) {
            Paint p = getPaint();
            matrix.setTranslate(translate, 0);
            gradient.setLocalMatrix(matrix);
            p.setShader(gradient);
        }
        super.onDraw(canvas);
        // shader OFF a nem szöveges elemekhez (ha lenne compound drawable)
        Paint p = getPaint();
        p.setShader(null);
    }
}
