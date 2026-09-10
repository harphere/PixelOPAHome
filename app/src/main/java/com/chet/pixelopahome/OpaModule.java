package com.chet.pixelopahome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Pixel OPA Home v1.1.1
 *
 * Infinity-X/Pixel 8 Pro implementation strategy:
 *  - Resolve the exact ROM KeyButtonView class once.
 *  - Do NOT hook any ROM-private KeyButtonView methods.
 *  - Hook the stable framework View.setPressed(boolean) method.
 *  - Filter callbacks to only the ROM KeyButtonView instance representing HOME.
 *  - Draw the OPA dots as a separate transient View over NavigationBarView.
 */
public final class OpaModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "PixelOpaHome";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String KEY_BUTTON_CLASS =
            "com.android.systemui.navigationbar.views.buttons.KeyButtonView";

    private static final Map<View, OpaBurstView> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Integer> LOGGED_HOME_INSTANCES =
            Collections.synchronizedSet(new HashSet<>());

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log(TAG + ": ENTRYPOINT initZygote reached; modulePath=" + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // This line is intentionally before the package filter. If it never appears,
        // LSPosed did not invoke this module entrypoint at all.
        XposedBridge.log(TAG + ": ENTRYPOINT handleLoadPackage package=" + lpparam.packageName
                + "; process=" + lpparam.processName);
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        final String process = lpparam.processName == null ? "<unknown>" : lpparam.processName;

        // Important: only the real SystemUI classloader can resolve this class. Auxiliary
        // injections (AdGuard/AutoLocation/etc.) that report packageName=com.android.systemui
        // but have an empty/foreign DexPathList will fail here and are intentionally ignored.
        final Class<?> keyButtonClass;
        try {
            keyButtonClass = XposedHelpers.findClass(KEY_BUTTON_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ignoring non-SystemUI/incompatible classloader in process="
                    + process + " (cannot resolve " + KEY_BUTTON_CLASS + ")");
            return;
        }

        XposedBridge.log(TAG + ": resolved Infinity-X KeyButtonView=" + keyButtonClass.getName()
                + "; process=" + process);

        try {
            XposedHelpers.findAndHookMethod(View.class, "setPressed", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object object = param.thisObject;
                            if (!keyButtonClass.isInstance(object)) return;

                            View key = (View) object;
                            if (!isHomeButton(key)) return;

                            boolean pressed = (Boolean) param.args[0];
                            logHomeOnce(key, process);

                            if (pressed) {
                                startOpaIfNeeded(key);
                            } else {
                                releaseOpa(key, false);
                            }
                        }
                    });

            XposedBridge.log(TAG + ": framework View.setPressed(boolean) hook installed; process="
                    + process);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FAILED to hook framework View.setPressed(boolean): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean isHomeButton(View key) {
        // Primary test: KeyButtonView's keycode field. This field is present in the actual
        // Infinity-X class and is the semantic test we want.
        try {
            if (XposedHelpers.getIntField(key, "mCode") == KeyEvent.KEYCODE_HOME) return true;
        } catch (Throwable ignored) {
            // Keep a resource-id fallback in case the ROM later renames the field.
        }

        try {
            int id = key.getId();
            if (id != View.NO_ID) {
                String entry = key.getResources().getResourceEntryName(id);
                return "home".equals(entry) || entry.contains("home");
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private static void logHomeOnce(View home, String process) {
        int identity = System.identityHashCode(home);
        if (!LOGGED_HOME_INSTANCES.add(identity)) return;

        String id = "unknown";
        Integer code = null;
        try { id = home.getResources().getResourceName(home.getId()); } catch (Throwable ignored) {}
        try { code = XposedHelpers.getIntField(home, "mCode"); } catch (Throwable ignored) {}

        XposedBridge.log(TAG + ": HOME identified through View.setPressed; process=" + process
                + "; class=" + home.getClass().getName()
                + "; id=" + id
                + "; mCode=" + code
                + "; size=" + home.getWidth() + "x" + home.getHeight());
    }

    private static void startOpaIfNeeded(View home) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(home)) return;
        }

        home.post(() -> {
            synchronized (ACTIVE) {
                if (ACTIVE.containsKey(home)) return;
            }

            try {
                if (!home.isAttachedToWindow() || !home.isPressed()) return;

                ViewGroup host = findNavigationHost(home);
                if (host == null || host.getWidth() <= 0 || host.getHeight() <= 0) {
                    XposedBridge.log(TAG + ": HOME found but no usable navigation host yet");
                    return;
                }

                int[] homePos = new int[2];
                int[] hostPos = new int[2];
                home.getLocationOnScreen(homePos);
                host.getLocationOnScreen(hostPos);

                float cx = homePos[0] - hostPos[0] + home.getWidth() / 2f;
                float cy = homePos[1] - hostPos[1] + home.getHeight() / 2f;

                OpaBurstView burst = new OpaBurstView(home.getContext(), host);
                burst.setClickable(false);
                burst.setFocusable(false);
                burst.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

                host.addView(burst, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                burst.bringToFront();

                synchronized (ACTIVE) {
                    ACTIVE.put(home, burst);
                }
                burst.begin(cx, cy);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": OPA start failed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        });
    }

    private static void releaseOpa(View home, boolean cancel) {
        OpaBurstView burst;
        synchronized (ACTIVE) {
            burst = ACTIVE.remove(home);
        }
        if (burst == null) return;
        if (cancel) burst.cancelAndRemove();
        else burst.release();
    }

    private static ViewGroup findNavigationHost(View home) {
        View current = home;
        ViewGroup best = null;

        while (true) {
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                best = group;

                String name = current.getClass().getName();
                if (name.contains("NavigationBarView")) return group;
            }

            Object parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }

        // AOSP NavigationBarView is a ViewGroup and should normally have been found above.
        // The fallback is intentionally the highest ViewGroup ancestor in the same navbar
        // window; we never create a WindowManager overlay or modify SystemUI resources.
        return best;
    }

    /** Programmatic recreation of the classic Pixel OPA/OpaLayout dot animation. */
    public static final class OpaBurstView extends View {
        private static final int BLUE   = 0xFF4285F4;
        private static final int RED    = 0xFFEA4335;
        private static final int YELLOW = 0xFFFBBC05;
        private static final int GREEN  = 0xFF34A853;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final PointF[] dots = {
                new PointF(), new PointF(), new PointF(), new PointF()
        };
        private final int[] colors = {BLUE, RED, YELLOW, GREEN};
        private final ViewGroup host;
        private final float density;
        private final float radius;
        private final float diamond;
        private final float outerLine;
        private final float innerLine;

        private float cx;
        private float cy;
        private float alpha = 1f;
        private float scale = 1f;
        private boolean released;
        private boolean inLine;
        private ValueAnimator animator;

        private final Runnable longPress = () -> {
            if (!released && isAttachedToWindow()) animateToLine();
        };

        OpaBurstView(Context context, ViewGroup host) {
            super(context);
            this.host = host;
            density = context.getResources().getDisplayMetrics().density;
            radius = 5f * density;      // classic OPA dot diameter: ~10dp
            diamond = 16f * density;    // diamond translation
            outerLine = 30f * density;  // blue/green line positions
            innerLine = 15f * density;  // red/yellow line positions
            setWillNotDraw(false);
        }

        void begin(float x, float y) {
            cx = x;
            cy = y;
            released = false;
            inLine = false;
            setDotsAtCenter();
            animateDiamond();
            handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        }

        void release() {
            released = true;
            handler.removeCallbacks(longPress);
            animateCollapse();
        }

        void cancelAndRemove() {
            released = true;
            handler.removeCallbacks(longPress);
            if (animator != null) animator.cancel();
            detach();
        }

        private void animateDiamond() {
            run(0f, 1f, 190L, new OvershootInterpolator(1.15f), f -> {
                // Classic Pixel arrangement: red top, green right, yellow bottom, blue left.
                set(dots[1], cx, cy - diamond * f); // red
                set(dots[3], cx + diamond * f, cy); // green
                set(dots[2], cx, cy + diamond * f); // yellow
                set(dots[0], cx - diamond * f, cy); // blue
                alpha = 1f;
                scale = 0.72f + 0.28f * f;
            }, null);
        }

        private void animateToLine() {
            inLine = true;
            PointF[] start = snapshot();
            run(0f, 1f, 180L, new DecelerateInterpolator(), f -> {
                lerp(dots[0], start[0], cx - outerLine, cy, f); // blue
                lerp(dots[1], start[1], cx - innerLine, cy, f); // red
                lerp(dots[2], start[2], cx + innerLine, cy, f); // yellow
                lerp(dots[3], start[3], cx + outerLine, cy, f); // green
                scale = 1f;
                alpha = 1f;
            }, null);
        }

        private void animateCollapse() {
            if (!isAttachedToWindow()) {
                detach();
                return;
            }

            PointF[] start = snapshot();
            final float overshoot = 8f * density;
            run(0f, 1f, inLine ? 190L : 165L, new DecelerateInterpolator(), f -> {
                for (int i = 0; i < dots.length; i++) {
                    float sx = start[i].x;
                    float sy = start[i].y;
                    float kick = (float) Math.sin(Math.PI * Math.min(1f, f * 1.35f))
                            * overshoot * (1f - f);
                    float vx = sx - cx;
                    float vy = sy - cy;
                    float len = (float) Math.hypot(vx, vy);
                    float kx = len == 0f ? 0f : (vx / len) * kick;
                    float ky = len == 0f ? 0f : (vy / len) * kick;
                    dots[i].x = sx + (cx - sx) * f + kx;
                    dots[i].y = sy + (cy - sy) * f + ky;
                }
                alpha = 1f - f;
                scale = 1f - 0.35f * f;
            }, this::detach);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int i = 0; i < dots.length; i++) {
                paint.setColor(colors[i]);
                paint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255f))));
                canvas.drawCircle(dots[i].x, dots[i].y, radius * scale, paint);
            }
        }

        private void setDotsAtCenter() {
            for (PointF dot : dots) set(dot, cx, cy);
            alpha = 1f;
            scale = 0.72f;
            invalidate();
        }

        private PointF[] snapshot() {
            PointF[] copy = new PointF[dots.length];
            for (int i = 0; i < dots.length; i++) {
                copy[i] = new PointF(dots[i].x, dots[i].y);
            }
            return copy;
        }

        private interface Frame {
            void apply(float fraction);
        }

        private void run(float from, float to, long duration,
                         android.animation.TimeInterpolator interpolator,
                         Frame frame, Runnable end) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(from, to);
            animator.setDuration(duration);
            animator.setInterpolator(interpolator);
            animator.addUpdateListener(a -> {
                frame.apply((Float) a.getAnimatedValue());
                invalidate();
            });
            if (end != null) {
                animator.addListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!cancelled) end.run();
                    }
                });
            }
            animator.start();
        }

        private void detach() {
            handler.removeCallbacks(longPress);
            if (animator != null) {
                animator.removeAllUpdateListeners();
                animator.removeAllListeners();
            }
            try {
                if (getParent() == host) host.removeView(this);
                else if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            } catch (Throwable ignored) {}
        }

        private static void set(PointF p, float x, float y) {
            p.x = x;
            p.y = y;
        }

        private static void lerp(PointF out, PointF start, float tx, float ty, float f) {
            out.x = start.x + (tx - start.x) * f;
            out.y = start.y + (ty - start.y) * f;
        }
    }
}
