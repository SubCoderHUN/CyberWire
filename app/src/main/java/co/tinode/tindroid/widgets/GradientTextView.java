package co.tinode.tindroid.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

public class GradientTextView extends AppCompatTextView {

    private LinearGradient mLinearGradient;
    private Matrix mGradientMatrix;
    private float mTranslate;
    private int[] mColors;

    public GradientTextView(Context context) {
        super(context);
        init();
    }

    public GradientTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GradientTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mColors = new int[]{
                Color.parseColor("#FFFF00"),
                Color.parseColor("#FF00FF"),
                Color.parseColor("#00FFFF"),
                Color.parseColor("#FFFF00")
        };
        mGradientMatrix = new Matrix();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            mLinearGradient = new LinearGradient(0, 0, w, 0, mColors, null, Shader.TileMode.CLAMP);
            getPaint().setShader(mLinearGradient);
            mGradientMatrix.setTranslate(-w, 0);
            mLinearGradient.setLocalMatrix(mGradientMatrix);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mGradientMatrix != null) {
            mTranslate += getWidth() / 10f;
            if (mTranslate > 2 * getWidth()) {
                mTranslate = -getWidth();
            }
            mGradientMatrix.setTranslate(mTranslate, 0);
            mLinearGradient.setLocalMatrix(mGradientMatrix);
            postInvalidateDelayed(100);
        }
    }
}
