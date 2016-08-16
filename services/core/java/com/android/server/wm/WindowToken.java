/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import android.annotation.CallSuper;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IWindow;

import java.io.PrintWriter;
import java.util.ArrayList;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_SCRIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

/**
 * Container of a set of related windows in the window manager. Often this is an AppWindowToken,
 * which is the handle for an Activity that it uses to display windows. For nested windows, there is
 * a WindowToken created for the parent window to manage its children.
 */
class WindowToken extends WindowContainer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowToken" : TAG_WM;

    // The window manager!
    protected final WindowManagerService mService;

    // The actual token.
    final IBinder token;

    // The type of window this token is for, as per WindowManager.LayoutParams.
    final int windowType;

    // Set if this token was explicitly added by a client, so should
    // not be removed when all windows are removed.
    final boolean explicit;

    // For printing.
    String stringName;

    // Is key dispatching paused for this token?
    boolean paused = false;

    // Should this token's windows be hidden?
    boolean hidden;

    // Temporary for finding which tokens no longer have visible windows.
    boolean hasVisible;

    // Set to true when this token is in a pending transaction where it
    // will be shown.
    boolean waitingToShow;

    // Set to true when this token is in a pending transaction where its
    // windows will be put to the bottom of the list.
    boolean sendingToBottom;

    WindowToken(WindowManagerService service, IBinder _token, int type, boolean _explicit) {
        mService = service;
        token = _token;
        windowType = type;
        explicit = _explicit;
        mService.mTokenMap.put(token, this);
    }

    void removeAllWindows() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState win = (WindowState) mChildren.get(i);
            if (DEBUG_WINDOW_MOVEMENT) Slog.w(TAG_WM, "removeAllWindows: removing win=" + win);
            win.removeIfPossible();
        }
        mChildren.clear();
    }

    void setExiting() {
        if (hidden) {
            return;
        }

        final int count = mChildren.size();
        boolean changed = false;
        boolean delayed = false;
        DisplayContent displayContent = null;

        for (int i = 0; i < count; i++) {
            final WindowState win = (WindowState) mChildren.get(i);
            if (win.mWinAnimator.isAnimationSet()) {
                delayed = true;
                // TODO: This is technically wrong as a token can have windows on multi-displays
                // currently. That will change moving forward though.
                displayContent = win.getDisplayContent();
            }
            changed |= win.onSetAppExiting();
        }

        hidden = true;

        if (changed) {
            mService.mWindowPlacerLocked.performSurfacePlacement();
            mService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /*updateInputWindows*/);
        }

        if (delayed && displayContent != null) {
            displayContent.mExitingTokens.add(this);
        }
    }

    int adjustAnimLayer(int adj) {
        int highestAnimLayer = -1;
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowState w = (WindowState) mChildren.get(j);
            final int winHighestAnimLayer = w.adjustAnimLayer(adj);
            if (winHighestAnimLayer > highestAnimLayer) {
                highestAnimLayer = winHighestAnimLayer;
            }
            if (w == mService.mInputMethodTarget && !mService.mInputMethodTargetWaitingAnim) {
                mService.mLayersController.setInputMethodAnimLayerAdjustment(adj);
            }
        }
        return highestAnimLayer;
    }

    /* package level access for test. */
    WindowState getTopWindow() {
        if (mChildren.isEmpty()) {
            return null;
        }
        return (WindowState) mChildren.get(mChildren.size() - 1).getTop();
    }

    /**
     * Recursive search through a WindowList and all of its windows' children.
     * @param target The window to search for.
     * @return The index of win in windows or of the window that is an ancestor of win.
     */
    /* package level access for test. */
    int getWindowIndex(WindowState target) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState w = (WindowState) mChildren.get(i);
            if (w == target || w.hasChild(target)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Return the list of Windows in this token on the given Display.
     * @param displayContent The display we are interested in.
     * @return List of windows from token that are on displayContent.
     */
    private WindowList getTokenWindowsOnDisplay(DisplayContent displayContent) {
        final WindowList windowList = new WindowList();
        final WindowList displayWindows = displayContent.getWindowList();
        final int count = displayWindows.size();
        for (int i = 0; i < count; i++) {
            final WindowState win = displayWindows.get(i);
            if (win.mToken == this) {
                windowList.add(win);
            }
        }
        return windowList;
    }

    // TODO: Now that we are no longer adding child windows to token directly, the rest of the code
    // in this method doesn't really belong here, but is it difficult to move at the moment. Need to
    // re-evaluate when figuring-out what to do about display window list.
    private void addChildWindow(final WindowState win) {
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            return;
        }
        final WindowState parentWindow = win.getParentWindow();

        WindowList windowsOnSameDisplay = getTokenWindowsOnDisplay(displayContent);

        // Figure out this window's ordering relative to the parent window.
        final int wCount = windowsOnSameDisplay.size();
        final int sublayer = win.mSubLayer;
        int largestSublayer = Integer.MIN_VALUE;
        WindowState windowWithLargestSublayer = null;
        int i;
        for (i = 0; i < wCount; i++) {
            WindowState w = windowsOnSameDisplay.get(i);
            final int wSublayer = w.mSubLayer;
            if (wSublayer >= largestSublayer) {
                largestSublayer = wSublayer;
                windowWithLargestSublayer = w;
            }
            if (sublayer < 0) {
                // For negative sublayers, we go below all windows in the same sublayer.
                if (wSublayer >= sublayer) {
                    win.addWindowToListBefore(wSublayer >= 0 ? parentWindow : w);
                    break;
                }
            } else {
                // For positive sublayers, we go above all windows in the same sublayer.
                if (wSublayer > sublayer) {
                    win.addWindowToListBefore(w);
                    break;
                }
            }
        }
        if (i >= wCount) {
            if (sublayer < 0) {
                win.addWindowToListBefore(parentWindow);
            } else {
                win.addWindowToListAfter(
                        largestSublayer >= 0 ? windowWithLargestSublayer : parentWindow);
            }
        }
    }

    void addWindow(final WindowState win) {
        if (DEBUG_FOCUS) Slog.d(TAG_WM, "addWindow: win=" + win + " Callers=" + Debug.getCallers(5));

        if (!win.isChildWindow()) {
            int tokenWindowsPos = 0;
            if (asAppWindowToken() != null) {
                tokenWindowsPos = addAppWindow(win);
            } else {
                win.addNonAppWindowToList();
            }
            if (!mChildren.contains(win)) {
                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Adding " + win + " to " + this);
                mChildren.add(tokenWindowsPos, win);
            }
        } else {
            addChildWindow(win);
        }
    }

    private int addAppWindow(final WindowState win) {
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            // It doesn't matter this display is going away.
            return 0;
        }
        final IWindow client = win.mClient;

        final WindowList windows = displayContent.getWindowList();
        WindowList tokenWindowList = getTokenWindowsOnDisplay(displayContent);
        int tokenWindowsPos = 0;
        if (!tokenWindowList.isEmpty()) {
            return addAppWindowExisting(win, windows, tokenWindowList);
        }

        // No windows from this token on this display
        if (mService.localLOGV) Slog.v(TAG_WM, "Figuring out where to add app window "
                + client.asBinder() + " (token=" + this + ")");

        // Figure out where the window should go, based on the order of applications.
        WindowState pos = null;

        final ArrayList<Task> tasks = displayContent.getTasks();
        int taskNdx;
        int tokenNdx = -1;
        for (taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
            for (tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                final AppWindowToken t = tokens.get(tokenNdx);
                if (t == this) {
                    --tokenNdx;
                    if (tokenNdx < 0) {
                        --taskNdx;
                        if (taskNdx >= 0) {
                            tokenNdx = tasks.get(taskNdx).mAppTokens.size() - 1;
                        }
                    }
                    break;
                }

                // We haven't reached the token yet; if this token is not going to the bottom and
                // has windows on this display, we can use it as an anchor for when we do reach the
                // token.
                tokenWindowList = getTokenWindowsOnDisplay(displayContent);
                if (!t.sendingToBottom && tokenWindowList.size() > 0) {
                    pos = tokenWindowList.get(0);
                }
            }
            if (tokenNdx >= 0) {
                // early exit
                break;
            }
        }

        // We now know the index into the apps. If we found an app window above, that gives us the
        // position; else we need to look some more.
        if (pos != null) {
            // Move behind any windows attached to this one.
            final WindowToken atoken = mService.mTokenMap.get(pos.mClient.asBinder());
            if (atoken != null) {
                tokenWindowList = atoken.getTokenWindowsOnDisplay(displayContent);
                final int NC = tokenWindowList.size();
                if (NC > 0) {
                    WindowState bottom = tokenWindowList.get(0);
                    if (bottom.mSubLayer < 0) {
                        pos = bottom;
                    }
                }
            }
            win.addWindowToListBefore(pos);
            return tokenWindowsPos;
        }

        // Continue looking down until we find the first token that has windows on this display.
        for ( ; taskNdx >= 0; --taskNdx) {
            AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
            for ( ; tokenNdx >= 0; --tokenNdx) {
                final WindowToken t = tokens.get(tokenNdx);
                tokenWindowList = t.getTokenWindowsOnDisplay(displayContent);
                final int NW = tokenWindowList.size();
                if (NW > 0) {
                    pos = tokenWindowList.get(NW-1);
                    break;
                }
            }
            if (tokenNdx >= 0) {
                // found
                break;
            }
        }

        if (pos != null) {
            // Move in front of any windows attached to this one.
            final WindowToken atoken = mService.mTokenMap.get(pos.mClient.asBinder());
            if (atoken != null) {
                final WindowState top = atoken.getTopWindow();
                if (top != null && top.mSubLayer >= 0) {
                    pos = top;
                }
            }
            win.addWindowToListAfter(pos);
            return tokenWindowsPos;
        }

        // Just search for the start of this layer.
        final int myLayer = win.mBaseLayer;
        int i;
        for (i = windows.size() - 1; i >= 0; --i) {
            WindowState w = windows.get(i);
            // Dock divider shares the base layer with application windows, but we want to always
            // keep it above the application windows. The sharing of the base layer is intended
            // for window animations, which need to be above the dock divider for the duration
            // of the animation.
            if (w.mBaseLayer <= myLayer && w.mAttrs.type != TYPE_DOCK_DIVIDER) {
                break;
            }
        }
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Based on layer: Adding window " + win + " at " + (i + 1) + " of "
                        + windows.size());
        windows.add(i + 1, win);
        mService.mWindowsChanged = true;
        return tokenWindowsPos;
    }

    private int addAppWindowExisting(
            WindowState win, WindowList windowList, WindowList tokenWindowList) {

        int tokenWindowsPos;
        // If this application has existing windows, we simply place the new window on top of
        // them... but keep the starting window on top.
        if (win.mAttrs.type == TYPE_BASE_APPLICATION) {
            // Base windows go behind everything else.
            final WindowState lowestWindow = tokenWindowList.get(0);
            win.addWindowToListBefore(lowestWindow);
            tokenWindowsPos = getWindowIndex(lowestWindow);
        } else {
            final AppWindowToken atoken = win.mAppToken;
            final int windowListPos = tokenWindowList.size();
            final WindowState lastWindow = tokenWindowList.get(windowListPos - 1);
            if (atoken != null && lastWindow == atoken.startingWindow) {
                win.addWindowToListBefore(lastWindow);
                tokenWindowsPos = getWindowIndex(lastWindow);
            } else {
                int newIdx = findIdxBasedOnAppTokens(win);
                // There is a window above this one associated with the same apptoken note that the
                // window could be a floating window that was created later or a window at the top
                // of the list of windows associated with this token.
                if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                        "not Base app: Adding window " + win + " at " + (newIdx + 1) + " of "
                                + windowList.size());
                windowList.add(newIdx + 1, win);
                if (newIdx < 0) {
                    // No window from token found on win's display.
                    tokenWindowsPos = 0;
                } else {
                    tokenWindowsPos = getWindowIndex(windowList.get(newIdx)) + 1;
                }
                mService.mWindowsChanged = true;
            }
        }
        return tokenWindowsPos;
    }

    int reAddAppWindows(DisplayContent displayContent, int index) {
        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            final WindowState win = (WindowState) mChildren.get(i);
            final DisplayContent winDisplayContent = win.getDisplayContent();
            if (winDisplayContent == displayContent || winDisplayContent == null) {
                win.mDisplayContent = displayContent;
                index = win.reAddWindow(index);
            }
        }
        return index;
    }

    /**
     * This method finds out the index of a window that has the same app token as win. used for z
     * ordering the windows in mWindows
     */
    private int findIdxBasedOnAppTokens(WindowState win) {
        final WindowList windows = win.getWindowList();
        for(int j = windows.size() - 1; j >= 0; j--) {
            final WindowState wentry = windows.get(j);
            if(wentry.mAppToken == win.mAppToken) {
                return j;
            }
        }
        return -1;
    }

    /** Return the first window in the token window list that isn't a starting window or null. */
    WindowState getFirstNonStartingWindow() {
        final int count = mChildren.size();
        // We only care about parent windows so no need to loop through child windows.
        for (int i = 0; i < count; i++) {
            final WindowState w = (WindowState) mChildren.get(i);
            if (w.mAttrs.type != TYPE_APPLICATION_STARTING) {
                return w;
            }
        }
        return null;
    }

    @CallSuper
    void removeWindow(WindowState win) {
        mChildren.remove(win);
    }

    /** Returns true if the token windows list is empty. */
    boolean isEmpty() {
        return mChildren.isEmpty();
    }

    WindowState getReplacingWindow() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = (WindowState) mChildren.get(i);
            final WindowState replacing = win.getReplacingWindow();
            if (replacing != null) {
                return replacing;
            }
        }
        return null;
    }

    void hideWallpaperToken(boolean wasDeferred, String reason) {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final WindowState wallpaper = (WindowState) mChildren.get(j);
            wallpaper.hideWallpaperWindow(wasDeferred, reason);
        }
        hidden = true;
    }

    void sendWindowWallpaperCommand(
            String action, int x, int y, int z, Bundle extras, boolean sync) {
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = (WindowState) mChildren.get(wallpaperNdx);
            try {
                wallpaper.mClient.dispatchWallpaperCommand(action, x, y, z, extras, sync);
                // We only want to be synchronous with one wallpaper.
                sync = false;
            } catch (RemoteException e) {
            }
        }
    }

    void updateWallpaperOffset(int dw, int dh, boolean sync) {
        final WallpaperController wallpaperController = mService.mWallpaperControllerLocked;
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = (WindowState) mChildren.get(wallpaperNdx);
            if (wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, sync)) {
                final WindowStateAnimator winAnimator = wallpaper.mWinAnimator;
                winAnimator.computeShownFrameLocked();
                // No need to lay out the windows - we can just set the wallpaper position directly.
                winAnimator.setWallpaperOffset(wallpaper.mShownPosition);
                // We only want to be synchronous with one wallpaper.
                sync = false;
            }
        }
    }

    void updateWallpaperVisibility(int dw, int dh, boolean visible, DisplayContent displayContent) {
        if (hidden == visible) {
            hidden = !visible;
            // Need to do a layout to ensure the wallpaper now has the correct size.
            displayContent.layoutNeeded = true;
        }

        final WallpaperController wallpaperController = mService.mWallpaperControllerLocked;
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = (WindowState) mChildren.get(wallpaperNdx);
            if (visible) {
                wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, false);
            }

            wallpaper.dispatchWallpaperVisibility(visible);
        }
    }

    boolean updateWallpaperWindowsPlacement(WindowList windowList, WindowState wallpaperTarget,
            int wallpaperTargetIndex, boolean visible, int dw, int dh, int wallpaperAnimLayerAdj) {

        boolean changed = false;
        if (hidden == visible) {
            if (DEBUG_WALLPAPER_LIGHT) Slog.d(TAG,
                    "Wallpaper token " + token + " hidden=" + !visible);
            hidden = !visible;
            // Need to do a layout to ensure the wallpaper now has the correct size.
            mService.getDefaultDisplayContentLocked().layoutNeeded = true;
        }

        final WallpaperController wallpaperController = mService.mWallpaperControllerLocked;
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = (WindowState) mChildren.get(wallpaperNdx);

            if (visible) {
                wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, false);
            }

            // First, make sure the client has the current visibility state.
            wallpaper.dispatchWallpaperVisibility(visible);
            wallpaper.adjustAnimLayer(wallpaperAnimLayerAdj);

            if (DEBUG_LAYERS || DEBUG_WALLPAPER_LIGHT) Slog.v(TAG, "adjustWallpaper win "
                    + wallpaper + " anim layer: " + wallpaper.mWinAnimator.mAnimLayer);

            // First, if this window is at the current index, then all is well.
            if (wallpaper == wallpaperTarget) {
                wallpaperTargetIndex--;
                wallpaperTarget = wallpaperTargetIndex > 0
                        ? windowList.get(wallpaperTargetIndex - 1) : null;
                continue;
            }

            // The window didn't match...  the current wallpaper window,
            // wherever it is, is in the wrong place, so make sure it is not in the list.
            int oldIndex = windowList.indexOf(wallpaper);
            if (oldIndex >= 0) {
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG,
                        "Wallpaper removing at " + oldIndex + ": " + wallpaper);
                windowList.remove(oldIndex);
                mService.mWindowsChanged = true;
                if (oldIndex < wallpaperTargetIndex) {
                    wallpaperTargetIndex--;
                }
            }

            // Now stick it in. For apps over wallpaper keep the wallpaper at the bottommost
            // layer. For keyguard over wallpaper put the wallpaper under the lowest window that
            // is currently on screen, i.e. not hidden by policy.
            int insertionIndex = 0;
            if (visible && wallpaperTarget != null) {
                final int type = wallpaperTarget.mAttrs.type;
                final int privateFlags = wallpaperTarget.mAttrs.privateFlags;
                if (((privateFlags & PRIVATE_FLAG_KEYGUARD) != 0 || type == TYPE_KEYGUARD_SCRIM)
                        && !mService.isKeyguardAnimatingIn()) {
                    insertionIndex = Math.min(windowList.indexOf(wallpaperTarget),
                            findLowestWindowOnScreen(windowList));
                }
            }
            if (DEBUG_WALLPAPER_LIGHT || DEBUG_WINDOW_MOVEMENT
                    || (DEBUG_ADD_REMOVE && oldIndex != insertionIndex)) Slog.v(TAG,
                    "Moving wallpaper " + wallpaper + " from " + oldIndex + " to " + insertionIndex);

            windowList.add(insertionIndex, wallpaper);
            mService.mWindowsChanged = true;
            changed = true;
        }

        return changed;
    }

    /**
     * @return The index in {@param windows} of the lowest window that is currently on screen and
     *         not hidden by the policy.
     */
    private int findLowestWindowOnScreen(WindowList windowList) {
        final int size = windowList.size();
        for (int index = 0; index < size; index++) {
            final WindowState win = windowList.get(index);
            if (win.isOnScreen()) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    boolean hasVisibleNotDrawnWallpaper() {
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState wallpaper = (WindowState) mChildren.get(j);
            if (wallpaper.hasVisibleNotDrawnWallpaper()) {
                return true;
            }
        }
        return false;
    }

    int getHighestAnimLayer() {
        int highest = -1;
        for (int j = 0; j < mChildren.size(); j++) {
            final WindowState w = (WindowState) mChildren.get(j);
            final int wLayer = w.getHighestAnimLayer();
            if (wLayer > highest) {
                highest = wLayer;
            }
        }
        return highest;
    }

    AppWindowToken asAppWindowToken() {
        // TODO: Not sure if this is the best way to handle this vs. using instanceof and casting.
        // I am not an app window token!
        return null;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("windows="); pw.println(mChildren);
        pw.print(prefix); pw.print("windowType="); pw.print(windowType);
                pw.print(" hidden="); pw.print(hidden);
                pw.print(" hasVisible="); pw.println(hasVisible);
        if (waitingToShow || sendingToBottom) {
            pw.print(prefix); pw.print("waitingToShow="); pw.print(waitingToShow);
                    pw.print(" sendingToBottom="); pw.print(sendingToBottom);
        }
    }

    @Override
    public String toString() {
        if (stringName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("WindowToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" "); sb.append(token); sb.append('}');
            stringName = sb.toString();
        }
        return stringName;
    }
}
