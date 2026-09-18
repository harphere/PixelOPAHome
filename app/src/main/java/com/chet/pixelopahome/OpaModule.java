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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.Collections;
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
 * Pixel OPA Home v1.2.1
 *
 * Android 16 strategy:
 *  - The active 3-button navbar is created by Launcher3/Quickstep taskbar code.
 *  - Hook NavbarButtonsViewController.addButton(...) without assuming signatures.
 *  - Capture the returned View whose resource entry is "home".
 *  - Capture that exact live Home ImageView by object identity.
 *  - Hook framework View.dispatchTouchEvent(MotionEvent) and react only when thisObject
 *    is the captured Home view, avoiding Launcher listener replacement/interception.
 *  - Draw the classic four-dot OPA animation as a separate transient overlay.
 */
public final class OpaModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "PixelOpaHome";
    private static final String PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String AOSP_LAUNCHER = "com.android.launcher3";
    private static final String NAVBAR_CONTROLLER =
            "com.android.launcher3.taskbar.NavbarButtonsViewController";

    private static final Map<View, OpaBurstView> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<View> BOUND_HOME_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log(TAG + ": ENTRYPOINT initZygote reached; modulePath=" + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PIXEL_LAUNCHER.equals(lpparam.packageName)
                && !AOSP_LAUNCHER.equals(lpparam.packageName)) {
            return;
        }

        final String process = lpparam.processName == null ? "<unknown>" : lpparam.processName;
        XposedBridge.log(TAG + ": LAUNCHER ENTRYPOINT package=" + lpparam.packageName
                + "; process=" + process);

        final Class<?> controller;
        try {
            controller = XposedHelpers.findClass(NAVBAR_CONTROLLER, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Launcher navbar controller not found in this classloader; package="
                    + lpparam.packageName + "; process=" + process + "; "
                    + t.getClass().getSimpleName());
            return;
        }

        XposedBridge.log(TAG + ": resolved Launcher3 NavbarButtonsViewController="
                + controller.getName() + "; process=" + process);

        installFrameworkDispatchHook(process);

        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(controller, "addButton",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof View)) return;
                            View v = (View) result;
                            String entry = resourceEntry(v);
                            XposedBridge.log(TAG + ": Launcher addButton returned; entry=" + entry
                                    + "; " + describeView(v));
                            if ("home".equals(entry) || "home_button".equals(entry)) {
                                bindHome(v, "NavbarButtonsViewController.addButton");
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hookAllMethods Launcher addButton installed count=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Launcher addButton hook failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Fallback: after init/update methods, reflect the known mHomeButton field if present.
        String[] lifecycle = {"init", "onConfigurationChanged", "updateButtonLayoutSpacing"};
        for (String name : lifecycle) {
            try {
                Set<XC_MethodHook.Unhook> hs = XposedBridge.hookAllMethods(controller, name,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                captureHomeField(param.thisObject, name);
                            }
                        });
                if (!hs.isEmpty()) {
                    XposedBridge.log(TAG + ": hookAllMethods Launcher " + name
                            + " installed count=" + hs.size());
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void captureHomeField(Object controller, String source) {
        try {
            Object value = XposedHelpers.getObjectField(controller, "mHomeButton");
            if (value instanceof View) {
                View home = (View) value;
                XposedBridge.log(TAG + ": mHomeButton captured via " + source + "; "
                        + describeView(home));
                bindHome(home, "mHomeButton/" + source);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": mHomeButton unavailable via " + source + "; "
                    + t.getClass().getSimpleName());
        }
    }

    private static String resourceEntry(View v) {
        if (v == null || v.getId() == View.NO_ID) return "<no-id>";
        try { return v.getResources().getResourceEntryName(v.getId()); }
        catch (Throwable t) { return "<id:" + v.getId() + ">"; }
    }

    private static void bindHome(View home, String source) {
        synchronized (BOUND_HOME_VIEWS) {
            if (BOUND_HOME_VIEWS.contains(home)) return;
            BOUND_HOME_VIEWS.add(home);
        }

        XposedBridge.log(TAG + ": BOUND LAUNCHER HOME VIEW; source=" + source
                + "; identity=" + System.identityHashCode(home)
                + "; " + describeView(home));
    }

    private static void installFrameworkDispatchHook(String process) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)
                                    || param.args == null || param.args.length == 0
                                    || !(param.args[0] instanceof MotionEvent)) {
                                return;
                            }

                            View v = (View) param.thisObject;
                            synchronized (BOUND_HOME_VIEWS) {
                                if (!BOUND_HOME_VIEWS.contains(v)) return;
                            }

                            MotionEvent e = (MotionEvent) param.args[0];
                            int action = e.getActionMasked();
                            if (action == MotionEvent.ACTION_DOWN) {
                                XposedBridge.log(TAG + ": LAUNCHER HOME DOWN via View.dispatchTouchEvent; identity="
                                        + System.identityHashCode(v) + "; " + describeView(v));
                                startOpaIfNeeded(v);
                            } else if (action == MotionEvent.ACTION_UP) {
                                XposedBridge.log(TAG + ": LAUNCHER HOME UP via View.dispatchTouchEvent; identity="
                                        + System.identityHashCode(v) + "; " + describeView(v));
                                releaseOpa(v, false);
                            } else if (action == MotionEvent.ACTION_CANCEL) {
                                XposedBridge.log(TAG + ": LAUNCHER HOME CANCEL via View.dispatchTouchEvent; identity="
                                        + System.identityHashCode(v) + "; " + describeView(v));
                                releaseOpa(v, true);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": framework View.dispatchTouchEvent(MotionEvent) hook installed in Launcher; process="
                    + process);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": framework View.dispatchTouchEvent hook failed in Launcher: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String describeView(View v) {
        String id;
        try { id = v.getResources().getResourceName(v.getId()); }
        catch (Throwable t) { id = String.valueOf(v.getId()); }
        return "class=" + v.getClass().getName() + "; id=" + id
                + "; size=" + v.getWidth() + "x" + v.getHeight()
                + "; shown=" + v.isShown() + "; attached=" + v.isAttachedToWindow();
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
                if (!home.isAttachedToWindow()) return;
                ViewGroup host = findNavigationHost(home);
                if (host == null || host.getWidth() <= 0 || host.getHeight() <= 0) {
                    XposedBridge.log(TAG + ": Launcher Home has no usable overlay host");
                    return;
                }
                int[] hp = new int[2];
                int[] gp = new int[2];
                home.getLocationOnScreen(hp);
                host.getLocationOnScreen(gp);
                float cx = hp[0] - gp[0] + home.getWidth() / 2f;
                float cy = hp[1] - gp[1] + home.getHeight() / 2f;

                OpaBurstView burst = new OpaBurstView(home.getContext(), host);
                burst.setClickable(false);
                burst.setFocusable(false);
                burst.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                host.addView(burst, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                burst.bringToFront();
                synchronized (ACTIVE) { ACTIVE.put(home, burst); }
                XposedBridge.log(TAG + ": OPA overlay attached in Launcher; host="
                        + host.getClass().getName() + "; center=" + cx + "," + cy);
                burst.begin(cx, cy);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Launcher OPA start failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private static void releaseOpa(View home, boolean cancel) {
        OpaBurstView burst;
        synchronized (ACTIVE) { burst = ACTIVE.remove(home); }
        if (burst == null) return;
        if (cancel) burst.cancelAndRemove();
        else burst.release();
    }

    private static ViewGroup findNavigationHost(View home) {
        View current = home;
        ViewGroup best = null;
        while (true) {
            if (current instanceof ViewGroup) best = (ViewGroup) current;
            Object parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        if (current instanceof ViewGroup) best = (ViewGroup) current;
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
            diamond = 22f * density;    // diamond translation
            outerLine = 38f * density;  // blue/green line positions
            innerLine = 19f * density;  // red/yellow line positions
            setWillNotDraw(false);
        }

        void begin(float x, float y) {
    cx = x;
    cy = y;
    released = false;
    inLine = false;
    setDotsAtCenter();

    handler.postDelayed(() -> {
        if (!released && isAttachedToWindow()) {
            animateDiamond();
        }
    }, 60L);

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
            run(0f, 1f, 220L, new OvershootInterpolator(1.15f), f -> {
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
