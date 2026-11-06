package com.capri.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fundo Y2K "vivo": cada musica recebe uma semente (do proprio id) e o fundo
 * redesenha stickers numa composicao nova. A paleta pode vir da CAPA da
 * musica (cores dominantes) ou ser o vermelho/verde padrao do app.
 * Transicao: stickers antigos sao "arrastados" pra fora enquanto os novos
 * entram por baixo.
 */
public class Y2kBgView extends View {

    private static final int RED = 0xFFFF2445;
    private static final int GREEN = 0xFF3DFF5E;
    private static final int WHITE = 0xFFF1F1F5;
    private static final int SMOKE = 0x667A7A8A;
    private static final int DARK = 0xFF0A0A0C;

    private static final int T_STAR = 0;
    private static final int T_SMILE = 1;
    private static final int T_BLOB = 2;
    private static final int T_CROSS = 3;
    private static final int T_LABEL = 4;
    private static final int T_WIRE = 9;

    private static class Item {
        int type;
        float x, y, s, rot;
        float ph, wa;      // fase e amplitude do wobble
        int color;
        SweepGradient grad;
        String txt;
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private List<Item> items = new ArrayList<>();
    private List<Item> oldItems;   // grupo saindo ("arrastado") na transicao
    private ValueAnimator slide;
    private float prog = 1f;
    private float panDx, panDy;

    // wobble suave — stickers balançam enquanto a musica toca
    private ValueAnimator wobble;
    private float wobT;
    private float amp, ampTarget;

    private long seed = 0x5EEDL;

    // paleta "identidade" — troca quando a capa da musica manda cor
    private int cMain = RED;
    private int cSec = GREEN;

    public Y2kBgView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** paleta vinda da capa (ou padrao do app quando nao tem capa) */
    public void setPalette(int main, int sec) {
        if (main == cMain && sec == cSec) return;
        cMain = main;
        cSec = sec;
        regenerate();
        slideIn();
    }

    /** muda a "identidade" do fundo — chamada a cada faixa tocada */
    public void setSeed(long s) {
        seed = s;
        regenerate();
        slideIn();
    }

    /** wobble ligado = tocando; desligado ease-out pra zero */
    public void setWobble(boolean on) {
        ampTarget = on ? 1f : 0f;
        if (wobble != null) return;
        wobble = ValueAnimator.ofFloat(0f, 1f);
        wobble.setDuration(4200);
        wobble.setRepeatCount(ValueAnimator.INFINITE);
        wobble.setInterpolator(null); // linear: a onda e continua
        wobble.addUpdateListener(a -> {
            wobT = (Float) a.getAnimatedValue() * (float) Math.PI * 2f;
            amp += (ampTarget - amp) * 0.055f;
            if (amp > 0.015f || ampTarget > 0f) invalidate();
        });
        wobble.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        oldItems = null;
        prog = 1f;
        regenerate();
    }

    private void slideIn() {
        int w = getWidth(), h = getHeight();
        if (oldItems != null) oldItems = null;
        if (w == 0 || h == 0) return;

        oldItems = new ArrayList<>(items);
        Random r = new Random(seed ^ 0x2A11FL);
        int dir = (seed & 1L) == 0 ? 1 : -1;
        panDx = dir * w * (0.85f + r.nextFloat() * 0.5f);
        panDy = -dir * h * (0.18f + r.nextFloat() * 0.3f);

        prog = 0f;
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(0f, 1f);
        slide.setDuration(940);
        slide.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        slide.addUpdateListener(a -> {
            prog = (Float) a.getAnimatedValue();
            if (prog >= 1f && oldItems != null) oldItems = null;
            invalidate();
        });
        slide.start();
    }

    private void regenerate() {
        items = new ArrayList<>();
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        Random r = new Random(seed * 2654435761L);
        int n = 8 + r.nextInt(5);
        for (int i = 0; i < n; i++) {
            Item it = new Item();
            it.type = r.nextInt(5);
            it.x = (0.08f + r.nextFloat() * 0.84f) * w;
            it.y = (0.06f + r.nextFloat() * 0.88f) * h;
            it.s = (16 + r.nextFloat() * 36) * (w / 420f);
            if (it.s < 10) it.s = 10;
            it.rot = r.nextFloat() * 360f;
            it.ph = r.nextFloat() * 6.2832f;
            it.wa = (2.5f + r.nextFloat() * 3.5f) * getContext().getResources()
                    .getDisplayMetrics().density;
            float cor = r.nextFloat();
            if (cor < 0.55f) it.color = cMain;
            else if (cor < 0.85f) it.color = cSec;
            else it.color = WHITE;

            if (it.type == T_BLOB) {
                boolean g = r.nextBoolean();
                int c1 = g ? cMain : cSec;
                int c2 = shade(c1, 0.25f);
                it.grad = new SweepGradient(it.x, it.y, new int[]{
                        c1, WHITE, c2, 0xFF2A2A33, c1},
                        new float[]{0f, 0.22f, 0.5f, 0.8f, 1f});
            }
            if (it.type == T_LABEL) {
                it.txt = "0x" + Integer.toHexString(
                        (int) (r.nextInt() & 0xFFFF)).toUpperCase();
            }
            items.add(it);
        }

        // fios conectando (estilo cordao da evidencia)
        int lines = 2 + r.nextInt(2);
        for (int i = 0; i < lines; i++) {
            Item a = items.get(r.nextInt(Math.max(1, items.size())));
            Item b = items.get(r.nextInt(Math.max(1, items.size())));
            Item l = new Item();
            l.type = T_WIRE;
            l.x = a.x;
            l.y = a.y;
            l.txt = b.x + ";" + b.y;
            l.color = r.nextBoolean() ? cMain : cSec;
            items.add(l);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float d = getContext().getResources().getDisplayMetrics().density;

        // moldura HUD (estilo cyberpunk) — imovel durante a transicao
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor(RED);
        p.setAlpha(70);
        float c = 26 * d;
        canvas.drawLine(0, 0, c, 0, p);
        canvas.drawLine(0, 0, 0, c, p);
        canvas.drawLine(w - c, 0, w, 0, p);
        canvas.drawLine(w, 0, w, c, p);
        canvas.drawLine(0, h, c, h, p);
        canvas.drawLine(0, h - c, 0, h, p);
        canvas.drawLine(w - c, h, w, h, p);
        canvas.drawLine(w, h - c, w, h, p);
        p.setAlpha(255);

        // grupo antigo saindo ("arrastado")
        if (oldItems != null) {
            canvas.save();
            canvas.translate(panDx * prog, panDy * prog);
            drawItems(canvas, oldItems);
            canvas.restore();
        }
        // grupo novo entrando
        canvas.save();
        if (oldItems != null) {
            canvas.translate(-panDx * (1f - prog), -panDy * (1f - prog));
        }
        drawItems(canvas, items);
        canvas.restore();

        // pontinhos de brilho (imoveis)
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(WHITE);
        p.setAlpha(28);
        for (int i = 0; i < 14; i++) {
            float px = (i * 97f) % w;
            float py = (i * 53f) % h;
            canvas.drawCircle(px, py, 1.1f * d, p);
        }
        p.setAlpha(255);
    }

    private void drawItems(Canvas canvas, List<Item> list) {
        for (Item it : list) {
            if (it.type == T_WIRE) {
                String[] xy = (it.txt == null ? "0;0" : it.txt).split(";");
                float bx = parseF(xy[0]), by = parseF(xy.length > 1 ? xy[1] : "0");
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f * getContext().getResources()
                        .getDisplayMetrics().density);
                p.setColor(it.color);
                p.setAlpha(80);
                p.setPathEffect(new DashPathEffect(new float[]{
                        9 * getContext().getResources().getDisplayMetrics().density,
                        7 * getContext().getResources().getDisplayMetrics().density}, 0));
                canvas.drawLine(it.x, it.y, bx, by, p);
                p.setPathEffect(null);
                p.setAlpha(255);
                continue;
            }

            canvas.save();
            if (amp > 0.01f && it.wa > 0) {
                canvas.translate(
                        (float) Math.sin(wobT + it.ph) * it.wa * amp,
                        (float) Math.cos(wobT * 1.31f + it.ph * 1.17f)
                                * it.wa * 0.7f * amp);
            }
            canvas.rotate(it.rot, it.x, it.y);
            switch (it.type) {
                case T_STAR: {
                    float s = it.s;
                    path.reset();
                    path.moveTo(it.x, it.y - s * 1.5f);
                    path.quadTo(it.x, it.y, it.x + s * 1.5f, it.y);
                    path.quadTo(it.x, it.y, it.x, it.y + s * 1.5f);
                    path.quadTo(it.x, it.y, it.x - s * 1.5f, it.y);
                    path.quadTo(it.x, it.y, it.x, it.y - s * 1.5f);
                    p.setShader(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(it.color);
                    p.setAlpha(40);
                    canvas.drawPath(path, p);
                    p.setAlpha(255);
                    canvas.drawPath(path, p);
                    break;
                }
                case T_SMILE: {
                    float s = it.s;
                    p.setShader(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(it.color);
                    canvas.drawCircle(it.x, it.y, s, p);
                    p.setColor(DARK);
                    canvas.drawCircle(it.x - s * 0.32f, it.y - s * 0.3f, s * 0.14f, p);
                    canvas.drawCircle(it.x + s * 0.32f, it.y - s * 0.3f, s * 0.14f, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1.6f, s * 0.13f));
                    canvas.drawArc(it.x - s * 0.55f, it.y - s * 0.55f,
                            it.x + s * 0.55f, it.y + s * 0.55f, 25, 130, false, p);
                    p.setStrokeWidth(0);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case T_BLOB: {
                    float s = it.s * 1.15f;
                    p.setStyle(Paint.Style.FILL);
                    p.setShader(it.grad);
                    canvas.drawCircle(it.x, it.y, s, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.4f * getContext().getResources()
                            .getDisplayMetrics().density);
                    p.setColor(WHITE);
                    p.setAlpha(110);
                    canvas.drawCircle(it.x, it.y, s, p);
                    p.setAlpha(255);
                    p.setStrokeWidth(0);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case T_CROSS: {
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.4f * getContext().getResources()
                            .getDisplayMetrics().density);
                    p.setColor(it.color);
                    p.setAlpha(it.color == WHITE ? 110 : 200);
                    float s = it.s * 0.8f;
                    canvas.drawLine(it.x - s, it.y, it.x + s, it.y, p);
                    canvas.drawLine(it.x, it.y - s, it.x, it.y + s, p);
                    p.setAlpha(255);
                    p.setStrokeWidth(0);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case T_LABEL: {
                    p.setShader(null);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(SMOKE);
                    p.setTypeface(Typeface.MONOSPACE);
                    p.setTextSize(Math.max(8, it.s * 0.34f));
                    canvas.drawText("//" + (it.txt == null ? "y2k" : it.txt), it.x, it.y, p);
                    break;
                }
            }
            canvas.restore();
        }
    }

    private static int shade(int c, float f) {
        int r = (int) (((c >> 16) & 255) * f);
        int g = (int) (((c >> 8) & 255) * f);
        int b = (int) ((c & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float parseF(String s) {
        try {
            return Float.parseFloat(s);
        } catch (Throwable e) {
            return 0f;
        }
    }
}
