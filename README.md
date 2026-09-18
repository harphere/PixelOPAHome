# PixelOpaHome v1.2.1 — Android 16 Launcher3 branch

This branch moves the hook from SystemUI to the Android 16 Launcher3/Quickstep navbar implementation.

## Why
Current Android 16 Launcher3 creates three-button navigation in `com.android.launcher3.taskbar.NavbarButtonsViewController`. The controller creates the Home `ImageView` through `addButton(...)`, assigns `R.id.home`, and routes Home clicks through `TaskbarNavButtonController`.

## LSPosed scope
Enable the module and scope it to your launcher, normally **Pixel Launcher (`com.google.android.apps.nexuslauncher`)**. If your ROM uses AOSP Launcher3 instead, also/alternatively scope **`com.android.launcher3`**.

You no longer need System UI scoped for this branch.

## Expected logs
- `PixelOpaHome: LAUNCHER ENTRYPOINT ...`
- `PixelOpaHome: resolved Launcher3 NavbarButtonsViewController=...`
- `PixelOpaHome: hookAllMethods Launcher addButton installed count=...`
- `PixelOpaHome: Launcher addButton returned; entry=home; ...`
- `PixelOpaHome: BOUND LAUNCHER HOME VIEW; ...`
- on press: `PixelOpaHome: LAUNCHER HOME DOWN`

The listener returns false, so it does not consume the Home action.
