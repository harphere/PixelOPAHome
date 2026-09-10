# Pixel OPA Home v1.1.1

LSPosed module for restoring the classic Google Pixel OPA four-colour Home-button animation on the tested Infinity-X Android 16 SystemUI.

## What changed in v1.1.1

This version abandons hooks into ROM-private `KeyButtonView` methods such as `onTouchEvent`, `dispatchTouchEvent`, and `sendEvent`.

It resolves the exact Infinity-X class:

`com.android.systemui.navigationbar.views.buttons.KeyButtonView`

Then it hooks the stable Android framework API `android.view.View.setPressed(boolean)` and filters callbacks to the actual Home `KeyButtonView` (`mCode == KEYCODE_HOME`, with a `home` resource-ID fallback).

The OPA dots are drawn in a separate transient View added to the navigation-bar host. The module does **not** replace `SystemUI.apk`, replace `ic_sysbar_home`, use an RRO, or intercept the actual Home action.

## Build

Push this source tree to GitHub and run **Build Pixel OPA Home** in Actions. The artifact is:

`PixelOpaHome-v1.1.1-debug.apk`

## Install / LSPosed scope

1. Make sure the previous Magisk/RRO Pixel OPA module remains removed.
2. Install the APK.
3. Enable it in LSPosed.
4. Scope it only to **System UI (`com.android.systemui`)**.
5. Reboot (preferred for the first test).

## Expected first-test log

After SystemUI starts:

`PixelOpaHome: resolved Infinity-X KeyButtonView=com.android.systemui.navigationbar.views.buttons.KeyButtonView`

`PixelOpaHome: framework View.setPressed(boolean) hook installed`

When Home is pressed for the first time:

`PixelOpaHome: HOME identified through View.setPressed; ... mCode=3 ...`

If those lines appear but no dots are visible, the remaining issue is drawing host/z-order/geometry, not Home-button event detection.


## Startup diagnostics

This build logs before any SystemUI-specific hook logic. After enabling the module and scoping System UI, reboot and filter LSPosed/logcat for `PixelOpaHome`. Expected earliest markers:

```
PixelOpaHome: ENTRYPOINT initZygote reached
PixelOpaHome: ENTRYPOINT handleLoadPackage package=com.android.systemui
```

If neither appears, the problem is module activation/installation/scope rather than the Home-button hook.
