package io.github.lotusdecoder.daybookrecorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import io.github.lotusdecoder.daybookrecorder.core.ConnectionHud;

/** Pure-Canvas vector components matching the v0.2 design sheets (zero external UI dependencies). */
final class UiGraphics {
    static final int GREEN = 0xff176b5b;
    static final int GREEN_BRIGHT = 0xff10b981;
    static final int GREEN_SOFT = 0xffe3f2ee;
    static final int GREEN_MINT = 0xffd1fae5;
    static final int BLUE = 0xff0284c7;
    static final int BLUE_DARK = 0xff0369a1;
    static final int BLUE_SOFT = 0xffe0f2fe;
    static final int AMBER = 0xffd97706;
    static final int AMBER_DARK = 0xffb45309;
    static final int AMBER_SOFT = 0xfffef3c7;
    static final int AMBER_BORDER = 0xfffde68a;
    static final int SLATE = 0xff475569;
    static final int SLATE_MUTED = 0xff64748b;
    static final int SLATE_LIGHT = 0xff94a3b8;
    static final int SLATE_SOFT = 0xfff1f5f9;
    static final int INK = 0xff152b25;
    static final int MUTED = 0xff5f716a;
    static final int BG = 0xfff3f5ef;
    static final int DANGER = 0xffdc2626;
    static final int DANGER_SOFT = 0xfffef2f2;
    static final int DANGER_BORDER = 0xfffecaca;

    private UiGraphics() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Center Recording Bean pebble hardware illustration + 4-state lifecycle halo. */
    static final class BeanHeroView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private DashPathEffect dashEffect;
        private ConnectionHud.Mode mode = ConnectionHud.Mode.DISCONNECTED;
        private boolean liveRecording;
        private float phaseDeg;

        BeanHeroView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            dashEffect = new DashPathEffect(new float[]{5.5f * d, 5.5f * d}, 0f);
        }

        void setState(ConnectionHud.Mode newMode, boolean isRecording) {
            boolean dynamic = (newMode == ConnectionHud.Mode.CONNECTING
                    || newMode == ConnectionHud.Mode.RECONNECTING || isRecording);
            boolean changed = this.mode != newMode || this.liveRecording != isRecording;
            this.mode = newMode;
            this.liveRecording = isRecording;
            if (dynamic) {
                phaseDeg = (phaseDeg + 18f) % 360f;
                invalidate();
            } else if (changed) {
                phaseDeg = 0f;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float scale = Math.min(w, h) / 144f;
            float cx = w * 0.5f, cy = h * 0.5f;

            paint.setPathEffect(null);
            if (mode == ConnectionHud.Mode.CONNECTING) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xa8e0f2fe);
                canvas.drawCircle(cx, cy, 54f * scale, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * scale);
                paint.setColor(0x550e7490);
                paint.setPathEffect(dashEffect);
                canvas.drawCircle(cx, cy, 64f * scale, paint);
                paint.setPathEffect(null);

                float r = 60f * scale;
                rect.set(cx - r, cy - r, cx + r, cy + r);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(3.4f * scale);
                paint.setColor(BLUE);
                canvas.drawArc(rect, -90f + phaseDeg, 96f, false, paint);
                paint.setStrokeWidth(2.5f * scale);
                paint.setColor(0x9938bdf8);
                canvas.drawArc(rect, 90f + phaseDeg, 88f, false, paint);
            } else if (mode == ConnectionHud.Mode.STABLE) {
                float pulse = liveRecording ? (float) (2.5f * Math.sin(Math.toRadians(phaseDeg * 2f))) : 0f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x8ce3f2ee);
                canvas.drawCircle(cx, cy, (64f + pulse) * scale, paint);
                paint.setColor(0x8cd1fae5);
                canvas.drawCircle(cx, cy, (54f + pulse * 0.5f) * scale, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.4f * scale);
                paint.setColor(liveRecording ? 0x6610b981 : 0x38176b5b);
                canvas.drawCircle(cx, cy, 60f * scale, paint);
            } else if (mode == ConnectionHud.Mode.DISCONNECTED) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xe6f1f5f9);
                canvas.drawCircle(cx, cy, 60f * scale, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * scale);
                paint.setColor(0xffcbd5e1);
                paint.setPathEffect(dashEffect);
                canvas.drawCircle(cx, cy, 60f * scale, paint);
                paint.setPathEffect(null);
            } else if (mode == ConnectionHud.Mode.RECONNECTING) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x80fef3c7);
                canvas.drawCircle(cx, cy, 64f * scale, paint);
                paint.setColor(0x5cfde68a);
                canvas.drawCircle(cx, cy, 53f * scale, paint);

                float r = 60f * scale;
                rect.set(cx - r, cy - r, cx + r, cy + r);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(3f * scale);
                paint.setColor(AMBER);
                canvas.drawArc(rect, -145f + phaseDeg * 0.8f, 265f, false, paint);
            }

            // Pebble Hardware Body
            boolean off = (mode == ConnectionHud.Mode.DISCONNECTED);
            int rimColor = off ? SLATE_LIGHT : 0xff334842;
            int bodyColor = off ? SLATE_MUTED : 0xff243430;
            int ringAccent = mode == ConnectionHud.Mode.CONNECTING ? BLUE
                    : mode == ConnectionHud.Mode.RECONNECTING ? AMBER
                    : off ? SLATE_LIGHT : GREEN;
            int ledColor = mode == ConnectionHud.Mode.CONNECTING ? 0xff38bdf8
                    : mode == ConnectionHud.Mode.RECONNECTING ? 0xfff59e0b
                    : off ? 0xffcbd5e1 : GREEN_BRIGHT;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(rimColor);
            rect.set(cx - 28f * scale, cy - 42f * scale, cx + 28f * scale, cy + 42f * scale);
            canvas.drawRoundRect(rect, 28f * scale, 28f * scale, paint);

            paint.setColor(bodyColor);
            rect.set(cx - 25f * scale, cy - 39f * scale, cx + 25f * scale, cy + 39f * scale);
            canvas.drawRoundRect(rect, 25f * scale, 25f * scale, paint);

            // 3 Mic Grille Dots
            paint.setColor(0xb394a3b8);
            float dotY = cy - 26f * scale;
            canvas.drawCircle(cx - 6f * scale, dotY, 1.4f * scale, paint);
            canvas.drawCircle(cx, dotY, 1.4f * scale, paint);
            canvas.drawCircle(cx + 6f * scale, dotY, 1.4f * scale, paint);

            // Tactile Center Record Button
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff1a2623);
            canvas.drawCircle(cx, cy + 1f * scale, 15f * scale, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * scale);
            paint.setColor(ringAccent);
            canvas.drawCircle(cx, cy + 1f * scale, 15f * scale, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(liveRecording ? GREEN : 0xff2b3d38);
            canvas.drawCircle(cx, cy + 1f * scale, 10f * scale, paint);

            // Status LED
            paint.setColor(ledColor);
            canvas.drawCircle(cx, cy + 25f * scale, 3.2f * scale, paint);

            // Bottom-Right Corner Status Badge
            float bx = cx + 42f * scale, by = cy + 40f * scale, br = 13f * scale;
            if (mode == ConnectionHud.Mode.STABLE) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(GREEN);
                canvas.drawCircle(bx, by, br, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(2.3f * scale);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(bx - 5.2f * scale, by + 0.2f * scale);
                path.lineTo(bx - 1.5f * scale, by + 3.8f * scale);
                path.lineTo(bx + 5.6f * scale, by - 3.5f * scale);
                canvas.drawPath(path, paint);
            } else if (mode == ConnectionHud.Mode.DISCONNECTED) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(SLATE_MUTED);
                canvas.drawCircle(bx, by, br, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(2.1f * scale);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float d = 4.2f * scale;
                canvas.drawLine(bx - d, by - d, bx + d, by + d, paint);
                canvas.drawLine(bx + d, by - d, bx - d, by + d, paint);
            } else if (mode == ConnectionHud.Mode.RECONNECTING) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(AMBER);
                canvas.drawCircle(bx, by, br, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(2f * scale);
                paint.setStrokeCap(Paint.Cap.ROUND);
                rect.set(bx - 5f * scale, by - 5f * scale, bx + 5f * scale, by + 5f * scale);
                canvas.drawArc(rect, -40f, 260f, false, paint);
            }
        }
    }

    /** Audio duration waveform bar (progress-colored vertical rounded bars). */
    static final class WaveformBarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long seed = 1L;
        private float progress = 1f;
        private int activeColor = GREEN;
        private int idleColor = 0xffcbd5ce;

        WaveformBarView(Context c, int barCount) {
            super(c);
        }

        void bind(long seed, float ratio, int active, int idle) {
            this.seed = seed;
            this.progress = Math.max(0f, Math.min(1f, ratio));
            this.activeColor = active;
            this.idleColor = idle;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float density = getResources().getDisplayMetrics().density;
            float barW = 2.7f * density;
            float gap = 2.4f * density;
            int n = Math.max(6, Math.min(40, (int) ((w + gap) / (barW + gap))));
            float totalUsed = n * barW + (n - 1) * gap;
            float startX = Math.max(0f, (w - totalUsed) * 0.5f);
            float[] profile = ConnectionHud.waveformProfile(seed, n);
            int cutoff = Math.round(n * progress);
            paint.setStyle(Paint.Style.FILL);
            float minH = 3.4f * density;
            float r = barW * 0.5f;
            for (int i = 0; i < n; i++) {
                float bh = Math.max(minH, h * profile[i]);
                float top = (h - bh) * 0.5f;
                float left = startX + i * (barW + gap);
                paint.setColor(i < cutoff ? activeColor : idleColor);
                rect.set(left, top, left + barW, top + bh);
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }

    /** Smooth rounded progress bar supporting custom track and gradient/solid fill colors. */
    static final class SmoothProgressView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float ratio;
        private int trackColor = 0xffe6ece9;
        private int startColor = GREEN;
        private int endColor = GREEN_BRIGHT;
        private int lastW;
        private LinearGradient gradient;

        SmoothProgressView(Context c) {
            super(c);
        }

        void setProgress(float r, int track, int c1, int c2) {
            this.ratio = Math.max(0f, Math.min(1f, r));
            if (this.trackColor != track || this.startColor != c1 || this.endColor != c2) {
                this.trackColor = track;
                this.startColor = c1;
                this.endColor = c2;
                this.gradient = null;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h * 0.5f;
            paint.setShader(null);
            paint.setColor(trackColor);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);

            if (ratio > 0.005f) {
                float fw = Math.max(h, w * ratio);
                if (gradient == null || lastW != w) {
                    lastW = w;
                    gradient = new LinearGradient(0, 0, w, 0, startColor, endColor, Shader.TileMode.CLAMP);
                }
                paint.setShader(gradient);
                rect.set(0, 0, Math.min(w, fw), h);
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }

    /** Crisp 24x24 viewport vector icons rendered directly on Canvas. */
    static final class VectorIconView extends View {
        enum Kind {
            NAV_LINK, NAV_AUDIO, NAV_SETTINGS,
            PLAY, PAUSE, TRASH, UPLOAD, DOWNLOAD, CLOUD_OK,
            CLOCK, BATTERY, SIGNAL, REFRESH, CHEVRON
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private Kind kind;
        private int tint;
        private int level = 4; // Used by SIGNAL (1..4) or BATTERY (0..100)

        VectorIconView(Context c, Kind kind, int tint) {
            super(c);
            this.kind = kind;
            this.tint = tint;
        }

        void setIcon(Kind k, int color) {
            this.kind = k;
            this.tint = color;
            invalidate();
        }

        void setLevel(int lvl) {
            if (this.level != lvl) {
                this.level = lvl;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h) / 24f;
            float ox = (w - 24f * s) * 0.5f;
            float oy = (h - 24f * s) * 0.5f;
            canvas.save();
            canvas.translate(ox, oy);

            paint.setColor(tint);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2.05f * s);

            switch (kind) {
                case PLAY: {
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(7.5f * s, 4.8f * s);
                    path.lineTo(19.5f * s, 12f * s);
                    path.lineTo(7.5f * s, 19.2f * s);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                }
                case PAUSE: {
                    paint.setStyle(Paint.Style.FILL);
                    rect.set(6.2f * s, 4.8f * s, 10.4f * s, 19.2f * s);
                    canvas.drawRoundRect(rect, 1.2f * s, 1.2f * s, paint);
                    rect.set(13.6f * s, 4.8f * s, 17.8f * s, 19.2f * s);
                    canvas.drawRoundRect(rect, 1.2f * s, 1.2f * s, paint);
                    break;
                }
                case TRASH: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.9f * s);
                    canvas.drawLine(4f * s, 6.5f * s, 20f * s, 6.5f * s, paint);
                    rect.set(9f * s, 3.2f * s, 15f * s, 6.5f * s);
                    canvas.drawRoundRect(rect, 1.5f * s, 1.5f * s, paint);
                    path.reset();
                    path.moveTo(6.2f * s, 6.5f * s);
                    path.lineTo(7.2f * s, 19.5f * s);
                    path.lineTo(16.8f * s, 19.5f * s);
                    path.lineTo(17.8f * s, 6.5f * s);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(10.2f * s, 10.8f * s, 10.2f * s, 16.2f * s, paint);
                    canvas.drawLine(13.8f * s, 10.8f * s, 13.8f * s, 16.2f * s, paint);
                    break;
                }
                case UPLOAD: {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(12f * s, 15.5f * s, 12f * s, 4f * s, paint);
                    path.reset();
                    path.moveTo(7.5f * s, 8.5f * s);
                    path.lineTo(12f * s, 4f * s);
                    path.lineTo(16.5f * s, 8.5f * s);
                    canvas.drawPath(path, paint);
                    path.reset();
                    path.moveTo(4.5f * s, 15.5f * s);
                    path.lineTo(4.5f * s, 19.5f * s);
                    path.lineTo(19.5f * s, 19.5f * s);
                    path.lineTo(19.5f * s, 15.5f * s);
                    canvas.drawPath(path, paint);
                    break;
                }
                case DOWNLOAD: {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(12f * s, 4f * s, 12f * s, 15.5f * s, paint);
                    path.reset();
                    path.moveTo(7.5f * s, 11f * s);
                    path.lineTo(12f * s, 15.5f * s);
                    path.lineTo(16.5f * s, 11f * s);
                    canvas.drawPath(path, paint);
                    path.reset();
                    path.moveTo(4.5f * s, 15.5f * s);
                    path.lineTo(4.5f * s, 19.5f * s);
                    path.lineTo(19.5f * s, 19.5f * s);
                    path.lineTo(19.5f * s, 15.5f * s);
                    canvas.drawPath(path, paint);
                    break;
                }
                case CLOUD_OK: {
                    paint.setStyle(Paint.Style.STROKE);
                    rect.set(3.5f * s, 6f * s, 20.5f * s, 18.5f * s);
                    canvas.drawRoundRect(rect, 6.2f * s, 6.2f * s, paint);
                    path.reset();
                    path.moveTo(8.8f * s, 12.5f * s);
                    path.lineTo(11.2f * s, 14.9f * s);
                    path.lineTo(15.6f * s, 10.2f * s);
                    canvas.drawPath(path, paint);
                    break;
                }
                case CLOCK: {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(12f * s, 12f * s, 8.8f * s, paint);
                    path.reset();
                    path.moveTo(12f * s, 7.2f * s);
                    path.lineTo(12f * s, 12f * s);
                    path.lineTo(15.4f * s, 14f * s);
                    canvas.drawPath(path, paint);
                    break;
                }
                case BATTERY: {
                    paint.setStyle(Paint.Style.STROKE);
                    rect.set(2.2f * s, 6.8f * s, 19.2f * s, 17.2f * s);
                    canvas.drawRoundRect(rect, 2.5f * s, 2.5f * s, paint);
                    canvas.drawLine(21.6f * s, 10f * s, 21.6f * s, 14f * s, paint);
                    paint.setStyle(Paint.Style.FILL);
                    float pct = level < 0 ? 0.6f : Math.max(0.12f, Math.min(1f, level / 100f));
                    rect.set(4.5f * s, 9.1f * s, (4.5f + 12.4f * pct) * s, 14.9f * s);
                    canvas.drawRoundRect(rect, 1.1f * s, 1.1f * s, paint);
                    break;
                }
                case SIGNAL: {
                    paint.setStyle(Paint.Style.FILL);
                    float[] tops = {15f, 11.2f, 7.4f, 3.6f};
                    float[] xs = {2.2f, 7.6f, 13.0f, 18.4f};
                    for (int i = 0; i < 4; i++) {
                        paint.setColor(i < level ? tint : (tint & 0x00ffffff) | 0x44000000);
                        rect.set(xs[i] * s, tops[i] * s, (xs[i] + 3.4f) * s, 20.5f * s);
                        canvas.drawRoundRect(rect, 1.1f * s, 1.1f * s, paint);
                    }
                    break;
                }
                case REFRESH: {
                    paint.setStyle(Paint.Style.STROKE);
                    rect.set(4f * s, 4f * s, 20f * s, 20f * s);
                    canvas.drawArc(rect, 25f, 290f, false, paint);
                    path.reset();
                    path.moveTo(15.8f * s, 4.2f * s);
                    path.lineTo(20.2f * s, 8.2f * s);
                    path.lineTo(15.2f * s, 8.8f * s);
                    canvas.drawPath(path, paint);
                    break;
                }
                case CHEVRON: {
                    paint.setStyle(Paint.Style.STROKE);
                    path.reset();
                    path.moveTo(9f * s, 6f * s);
                    path.lineTo(15f * s, 12f * s);
                    path.lineTo(9f * s, 18f * s);
                    canvas.drawPath(path, paint);
                    break;
                }
                case NAV_LINK: {
                    paint.setStyle(Paint.Style.STROKE);
                    rect.set(3f * s, 5.2f * s, 9.8f * s, 18.8f * s);
                    canvas.drawRoundRect(rect, 3.4f * s, 3.4f * s, paint);
                    rect.set(14.2f * s, 5.2f * s, 21f * s, 18.8f * s);
                    canvas.drawRoundRect(rect, 3.4f * s, 3.4f * s, paint);
                    canvas.drawLine(8.2f * s, 9.5f * s, 15.8f * s, 9.5f * s, paint);
                    canvas.drawLine(8.2f * s, 14.5f * s, 15.8f * s, 14.5f * s, paint);
                    break;
                }
                case NAV_AUDIO: {
                    paint.setStyle(Paint.Style.STROKE);
                    path.reset();
                    path.moveTo(9f * s, 17.5f * s);
                    path.lineTo(9f * s, 5.5f * s);
                    path.lineTo(20f * s, 3.8f * s);
                    path.lineTo(20f * s, 15.8f * s);
                    canvas.drawPath(path, paint);
                    canvas.drawCircle(6.2f * s, 17.8f * s, 2.8f * s, paint);
                    canvas.drawCircle(17.2f * s, 16.0f * s, 2.8f * s, paint);
                    break;
                }
                case NAV_SETTINGS: {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(12f * s, 12f * s, 3.2f * s, paint);
                    canvas.drawCircle(12f * s, 12f * s, 7.6f * s, paint);
                    for (int a = 0; a < 360; a += 60) {
                        double rad = Math.toRadians(a);
                        float x1 = 12f + (float) Math.cos(rad) * 7.6f;
                        float y1 = 12f + (float) Math.sin(rad) * 7.6f;
                        float x2 = 12f + (float) Math.cos(rad) * 9.8f;
                        float y2 = 12f + (float) Math.sin(rad) * 9.8f;
                        canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint);
                    }
                    break;
                }
            }
            canvas.restore();
        }
    }

    /**
     * Settings toggle row extending CheckBox so accessibility/instrumented tests locate it by exact title,
     * while visually rendering a modern grouped row with subtitle + pill switch on the right.
     */
    static final class SwitchRowCheckBox extends CheckBox {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final String subtitle;

        SwitchRowCheckBox(Context c, String title, String subtitle) {
            super(c);
            this.subtitle = subtitle == null ? "" : subtitle;
            setButtonDrawable(null);
            setBackground(null);
            setText(title);
            setTextSize(13.5f);
            setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setTextColor(INK);
            setGravity(Gravity.START | Gravity.TOP);
            int padTop = dp(c, this.subtitle.isEmpty() ? 13 : 10);
            int padBottom = dp(c, this.subtitle.isEmpty() ? 13 : 26);
            setPadding(0, padTop, dp(c, 54), padBottom);
            subPaint.setTextSize(11.2f * c.getResources().getDisplayMetrics().scaledDensity);
            subPaint.setColor(MUTED);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            if (!subtitle.isEmpty()) {
                canvas.drawText(subtitle, 0f, getHeight() - 9.5f * d, subPaint);
            }
            // Draw pill switch on the far right
            float swW = 41f * d, swH = 23.5f * d;
            float left = getWidth() - swW;
            float top = (getHeight() - swH) * 0.5f;
            rect.set(left, top, left + swW, top + swH);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isChecked() ? GREEN : 0xffcbd5e1);
            canvas.drawRoundRect(rect, swH * 0.5f, swH * 0.5f, paint);

            float pad = 2.6f * d;
            float knobD = swH - pad * 2f;
            float kx = isChecked() ? (left + swW - pad - knobD * 0.5f) : (left + pad + knobD * 0.5f);
            float ky = top + swH * 0.5f;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(kx, ky, knobD * 0.5f, paint);
        }
    }
}
