package com.remote.bluetooth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class JoystickView extends View {

    public interface OnJoystickMoveListener {
        void onValueChanged(float xPercent, float yPercent);
    }

    private Paint basePaint;
    private Paint baseBorderPaint;
    private Paint hatPaint;
    private Paint hatCenterPaint;

    private float centerX = 0;
    private float centerY = 0;
    private float baseRadius = 0;
    private float hatRadius = 0;

    private float hatX = 0;
    private float hatY = 0;

    private OnJoystickMoveListener listener;
    private boolean isDragging = false;

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.parseColor("#151515"));
        basePaint.setStyle(Paint.Style.FILL);

        baseBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseBorderPaint.setColor(Color.parseColor("#2A2A2A"));
        baseBorderPaint.setStyle(Paint.Style.STROKE);
        baseBorderPaint.setStrokeWidth(4f);

        hatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hatPaint.setColor(Color.parseColor("#262626"));
        hatPaint.setStyle(Paint.Style.FILL);

        hatCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hatCenterPaint.setColor(Color.parseColor("#3B82F6")); // Blue highlight
        hatCenterPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 2f * 0.85f;
        hatRadius = baseRadius * 0.42f;
        resetHat();
    }

    private void resetHat() {
        hatX = centerX;
        hatY = centerY;
        invalidate();
    }

    public void setOnJoystickMoveListener(OnJoystickMoveListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseRadius <= 0) return;

        // Base circle
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, baseBorderPaint);

        // Center crosshair / guide lines
        canvas.drawLine(centerX - 16, centerY, centerX + 16, centerY, baseBorderPaint);
        canvas.drawLine(centerX, centerY - 16, centerX, centerY + 16, baseBorderPaint);

        // Knob / Hat circle
        canvas.drawCircle(hatX, hatY, hatRadius, hatPaint);
        // Knob outer ring
        canvas.drawCircle(hatX, hatY, hatRadius, baseBorderPaint);
        // Knob center indicator
        canvas.drawCircle(hatX, hatY, hatRadius * 0.3f, hatCenterPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                isDragging = true;
                float dx = event.getX() - centerX;
                float dy = event.getY() - centerY;
                float dist = (float) Math.hypot(dx, dy);

                if (dist > baseRadius) {
                    hatX = centerX + (dx / dist) * baseRadius;
                    hatY = centerY + (dy / dist) * baseRadius;
                } else {
                    hatX = event.getX();
                    hatY = event.getY();
                }

                float xPercent = (hatX - centerX) / baseRadius;
                float yPercent = (hatY - centerY) / baseRadius;

                // Deadzone clamp
                if (Math.abs(xPercent) < 0.08f) xPercent = 0f;
                if (Math.abs(yPercent) < 0.08f) yPercent = 0f;

                if (listener != null) {
                    listener.onValueChanged(xPercent, yPercent);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                isDragging = false;
                resetHat();
                if (listener != null) {
                    listener.onValueChanged(0f, 0f);
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }
}
