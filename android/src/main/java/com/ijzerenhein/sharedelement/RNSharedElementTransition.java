package com.ijzerenhein.sharedelement;

import java.util.ArrayList;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.Choreographer;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;

public class RNSharedElementTransition extends ViewGroup {
  // Fabric can finish its visible surface transaction well after the source
  // ImageView detaches on slower devices. Keep the popped endpoint hidden long
  // enough that restoring its alpha cannot leak into a later rendered frame.
  private static final long FADE_RELEASE_TIMEOUT_MS = 5000L;
  private static final float ENDPOINT_FADE_FINISH_POSITION = 0.85f;

  // Native-timer animation is used for Fabric interop when JS-driven progress
  // does not update JS state. Uses Choreographer on the UI thread.

  enum Item {
    START(0),
    END(1);

    private final int value;

    Item(final int newValue) {
      value = newValue;
    }

    public int getValue() {
      return value;
    }
  }

  private final RNSharedElementNodeManager mNodeManager;
  private RNSharedElementAnimation mAnimation = RNSharedElementAnimation.MOVE;
  private RNSharedElementResize mResize = RNSharedElementResize.STRETCH;
  private RNSharedElementAlign mAlign = RNSharedElementAlign.CENTER_CENTER;
  private float mNodePosition = 0.0f;
  private boolean mReactLayoutSet = false;
  private boolean mInitialLayoutPassCompleted = false;
  private boolean mInitialNodePositionSet = false;
  private final ArrayList<RNSharedElementTransitionItem> mItems = new ArrayList<>();
  private final int[] mParentOffset = new int[2];
  private boolean mRequiresClipping = false;
  private final Path mClipPath = new Path();
  private boolean mHasRoundedClip = false;
  private final RNSharedElementView mStartView;
  private final RNSharedElementView mEndView;
  private int mInitialVisibleAncestorIndex = -1;
  private boolean mHasDrawnRenderableFrame = false;
  private boolean mHoldStartHiddenOnRelease = false;
  private boolean mHoldEndHiddenOnRelease = false;

  // Native-timer animation state for Fabric interop.
  private boolean mNativeDriver = false;
  private boolean mNativePreparing = false;
  private float mNativeDuration = 0.0f;
  private float mNativeDelay = 0.0f;
  private float mNativeFrom = Float.NaN;
  private float mNativeTo = Float.NaN;
  private boolean mNativeAnimating = false;
  private boolean mNativeAnimationPending = false;
  private long mNativeStartTimeMs = 0L;
  private Choreographer mChoreographer;
  private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
    @Override
    public void doFrame(long frameTimeNanos) {
      onFrame();
    }
  };

  private static final class RNSharedElementMeasureEvent extends Event<RNSharedElementMeasureEvent> {
    private final WritableMap mEventData;

    RNSharedElementMeasureEvent(int surfaceId, int viewTag, WritableMap eventData) {
      super(surfaceId, viewTag);
      mEventData = eventData;
    }

    @Override
    public String getEventName() {
      return "onMeasureNode";
    }

    @Override
    public boolean canCoalesce() {
      return false;
    }

    @Nullable
    @Override
    protected WritableMap getEventData() {
      return mEventData;
    }
  }

  public RNSharedElementTransition(ThemedReactContext context, RNSharedElementNodeManager nodeManager) {
    super(context);
    mNodeManager = nodeManager;
    mItems.add(new RNSharedElementTransitionItem(nodeManager, "start"));
    mItems.add(new RNSharedElementTransitionItem(nodeManager, "end"));

    mStartView = new RNSharedElementView(context);
    addView(mStartView);

    mEndView = new RNSharedElementView(context);
    addView(mEndView);
  }

  void releaseData() {
    // Defensive cleanup to stop any pending frame callbacks.
    stopNativeAnimation();
    Handler handler = new Handler(Looper.getMainLooper());
    for (int index = 0; index < mItems.size(); index++) {
      RNSharedElementTransitionItem item = mItems.get(index);
      boolean holdHidden = item.getHidden()
              && ((index == Item.START.getValue() && mHoldStartHiddenOnRelease)
              || (index == Item.END.getValue() && mHoldEndHiddenOnRelease));
      if (holdHidden) {
        releaseHiddenItemAfterTeardown(handler, item);
      } else {
        item.setNode(null);
      }
    }
  }

  private void releaseHiddenItemAfterTeardown(
          Handler handler,
          RNSharedElementTransitionItem item
  ) {
    handler.postDelayed(() -> item.setNode(null), FADE_RELEASE_TIMEOUT_MS);
  }

  RNSharedElementNodeManager getNodeManager() {
    return mNodeManager;
  }

  void setItemNode(Item item, RNSharedElementNode node) {
    mItems.get(item.getValue()).setNode(node);
    mHasDrawnRenderableFrame = false;
    // Nodes/ancestors may resolve after layout in Fabric; queue start.
    mNativeAnimationPending = mNativeDriver;
    // Kick off style/content fetch immediately so data can be ready by the
    // time the first layout pass completes. This reduces first-frame lag where
    // the destination is visible before shared elements are rendered.
    requestStylesAndContent(true);
    startNativeAnimationIfReady();
  }

  void setAnimation(final RNSharedElementAnimation animation) {
    if (mAnimation != animation) {
      mAnimation = animation;
      updateLayout();
    }
  }

  void setResize(final RNSharedElementResize resize) {
    if (mResize != resize) {
      mResize = resize;
      updateLayout();
    }
  }

  void setAlign(final RNSharedElementAlign align) {
    if (mAlign != align) {
      mAlign = align;
      updateLayout();
    }
  }

  // Easing similar to iOS: slow down near the end.
  private static float easeOutCubic(float t) {
    float clamped = Math.max(0.0f, Math.min(1.0f, t));
    float inv = 1.0f - clamped;
    return 1.0f - (inv * inv * inv);
  }

  private static boolean hasVisibleArea(RectF layout) {
    return (layout != null) && (layout.width() > 0.0f) && (layout.height() > 0.0f);
  }

  private static float getStartEndpointFadeProgress(float position) {
    float progress = position / ENDPOINT_FADE_FINISH_POSITION;
    return Math.max(0.0f, Math.min(1.0f, progress));
  }

  private static float getEndEndpointFadeProgress(float position) {
    float startPosition = 1.0f - ENDPOINT_FADE_FINISH_POSITION;
    float progress = (position - startPosition) / ENDPOINT_FADE_FINISH_POSITION;
    return Math.max(0.0f, Math.min(1.0f, progress));
  }

  private void useNativeStartEndpoint(float nativeFrom) {
    if (Float.isNaN(nativeFrom)) return;

    mNodePosition = nativeFrom;
    mInitialNodePositionSet = true;
    // The animation tells us which scene is on screen. Using that endpoint
    // avoids compensating the wrong card when both scene ancestors overlap.
    mInitialVisibleAncestorIndex = nativeFrom >= 0.5f ? 1 : 0;
    updateLayout();
  }

  private boolean isItemRenderable(RNSharedElementTransitionItem item) {
    return (item.getStyle() != null) && (item.getContent() != null);
  }

  private boolean hasRenderableSnapshot() {
    RNSharedElementTransitionItem startItem = mItems.get(Item.START.getValue());
    RNSharedElementTransitionItem endItem = mItems.get(Item.END.getValue());
    return isItemRenderable(startItem) || isItemRenderable(endItem);
  }

  private void tryCompleteInitialLayoutPass() {
    if (mInitialLayoutPassCompleted) return;
    View parent = (View) getParent();
    if (parent == null) {
      return;
    }
    mInitialLayoutPassCompleted = true;
    requestStylesAndContent(true);
    updateLayout();
    updateNodeVisibility();
  }

  private void startNativeAnimationIfReady() {
    // Drive nodePosition natively when layout/content are ready.
    if (!mNativeDriver) {
      return;
    }
    if (mNativePreparing) {
      return;
    }
    if (!mNativeAnimationPending) {
      return;
    }
    tryCompleteInitialLayoutPass();
    if (!mInitialLayoutPassCompleted) {
      return;
    }
    // Zero duration means no-op to avoid a tight loop.
    if (mNativeDuration <= 0.0f) {
      mNativeAnimationPending = false;
      return;
    }
    if (!hasRenderableSnapshot()) {
      return;
    }
    if (mNativeAnimating) {
      return;
    }

    mNativeAnimating = true;
    mNativeAnimationPending = false;

    long nowMs = SystemClock.uptimeMillis();
    mNativeStartTimeMs = nowMs + (long) mNativeDelay;

    if (Float.isNaN(mNativeFrom)) mNativeFrom = mNodePosition;
    if (Float.isNaN(mNativeTo)) mNativeTo = 1.0f;

    if (mChoreographer == null) {
      // Use Choreographer to sync with UI rendering.
      mChoreographer = Choreographer.getInstance();
    }
    mChoreographer.postFrameCallback(mFrameCallback);
  }

  private void stopNativeAnimation() {
    // Remove callbacks to avoid leaks or duplicate frames.
    if (!mNativeAnimating) return;
    mNativeAnimating = false;
    if (mChoreographer != null) {
      mChoreographer.removeFrameCallback(mFrameCallback);
    }
  }

  private void onFrame() {
    // Fixed-duration easing on the UI thread.
    if (!mNativeAnimating) return;
    long nowMs = SystemClock.uptimeMillis();
    if (nowMs < mNativeStartTimeMs) {
      if (mChoreographer != null) mChoreographer.postFrameCallback(mFrameCallback);
      return;
    }

    float elapsed = (float) (nowMs - mNativeStartTimeMs);
    float duration = mNativeDuration;
    float t = duration > 0.0f ? Math.min(1.0f, (elapsed / duration)) : 1.0f;
    float eased = easeOutCubic(t);
    float value = mNativeFrom + ((mNativeTo - mNativeFrom) * eased);

    if (mNodePosition != value) {
      mNodePosition = value;
      updateLayout();
      updateNodeVisibility();
    }

    if (t >= 1.0f) {
      stopNativeAnimation();
    } else if (mChoreographer != null) {
      mChoreographer.postFrameCallback(mFrameCallback);
    }
  }

  void setNativeDriver(final boolean nativeDriver) {
    // When enabled, we defer start until layout + nodes are ready.
    if (mNativeDriver != nativeDriver) {
      mNativeDriver = nativeDriver;
      if (mNativeDriver) {
        useNativeStartEndpoint(mNativeFrom);
      }
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady();
    }
  }

  void setNativePreparing(final boolean nativePreparing) {
    if (mNativePreparing == nativePreparing) return;

    mNativePreparing = nativePreparing;
    updateNodeVisibility();
    if (!mNativePreparing) {
      // The preview and live transition reuse one native view. Reset teardown
      // state here so only the real transition can keep an endpoint hidden.
      mHoldStartHiddenOnRelease = false;
      mHoldEndHiddenOnRelease = false;
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady();
    }
  }

  void setNativeDuration(final float nativeDuration) {
    // Any timing change should restart the pending native animation.
    if (mNativeDuration != nativeDuration) {
      mNativeDuration = nativeDuration;
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady();
    }
  }

  void setNativeDelay(final float nativeDelay) {
    // Any timing change should restart the pending native animation.
    if (mNativeDelay != nativeDelay) {
      mNativeDelay = nativeDelay;
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady();
    }
  }

  void setNativeFrom(final float nativeFrom) {
    // Any timing change should restart the pending native animation.
    if (mNativeFrom != nativeFrom) {
      mNativeFrom = nativeFrom;
      useNativeStartEndpoint(nativeFrom);
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady();
    }
  }

  void setNativeTo(final float nativeTo) {
    // Any timing change should restart the pending native animation.
    if (mNativeTo != nativeTo) {
      mNativeTo = nativeTo;
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady();
    }
  }

  void setNodePosition(final float nodePosition) {
    if (mNodePosition != nodePosition) {
      // Ignore JS updates while native driver is active to avoid contention.
      if (mNativeAnimating && mNativeDriver) {
        return;
      }
      if (mNativeAnimating) {
        stopNativeAnimation();
      }
      mNodePosition = nodePosition;
      mInitialNodePositionSet = true;
      updateLayout();
    }
  }

  @Override
  @SuppressLint("MissingSuperCall")
  public void requestLayout() {
    // No-op, terminate `requestLayout` here, all layout is updated in the
    // `updateLayout` function
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    if (!mReactLayoutSet) {
      mReactLayoutSet = true;

      // Wait for the whole layout pass to have completed before
      // requesting the layout and content
      requestStylesAndContent(true);
      mInitialLayoutPassCompleted = true;
      updateLayout();
      updateNodeVisibility();
      // Fabric interop bootstrap point for native animation.
      startNativeAnimationIfReady();
    }
  }

  @Override
  public boolean hasOverlappingRendering() {
    return false;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    tryCompleteInitialLayoutPass();
    startNativeAnimationIfReady();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    stopNativeAnimation();
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    if (mRequiresClipping) {
      int saveCount = canvas.save();
      canvas.clipRect(0, 0, getWidth(), getHeight());
      if (mHasRoundedClip) {
        canvas.clipPath(mClipPath);
      }
      super.dispatchDraw(canvas);
      canvas.restoreToCount(saveCount);
    } else {
      super.dispatchDraw(canvas);
    }
    if (!mHasDrawnRenderableFrame && hasRenderableSnapshot()) {
      mHasDrawnRenderableFrame = true;
      updateNodeVisibility();
    }
  }

  private void requestStylesAndContent(boolean force) {
    if (!mInitialLayoutPassCompleted && !force) {
      return;
    }
    for (final RNSharedElementTransitionItem item : mItems) {
      if (item.getNeedsStyle()) {
        item.setNeedsStyle(false);
        item.getNode().requestStyle(args -> {
          RNSharedElementStyle style = (RNSharedElementStyle) args[0];
          item.setStyle(style);
          tryCompleteInitialLayoutPass();
          updateLayout();
          updateNodeVisibility();
          startNativeAnimationIfReady();
        });
      }
      if (item.getNeedsContent()) {
        item.setNeedsContent(false);
        item.getNode().requestContent(args -> {
          RNSharedElementContent content = (RNSharedElementContent) args[0];
          item.setContent(content);
          tryCompleteInitialLayoutPass();
          updateLayout();
          updateNodeVisibility();
          startNativeAnimationIfReady();
        });
      }
    }
  }

  private void updateLayout() {
    if (!mInitialLayoutPassCompleted) {
      return;
    }

    // Local data
    RNSharedElementTransitionItem startItem = mItems.get(Item.START.getValue());
    RNSharedElementTransitionItem endItem = mItems.get(Item.END.getValue());

    // Get parent offset
    View parent = (View) getParent();
    if (parent == null) {
      return;
    }
    parent.getLocationInWindow(mParentOffset);

    // Get styles
    RNSharedElementStyle startStyle = startItem.getStyle();
    RNSharedElementStyle endStyle = endItem.getStyle();
    if ((startStyle == null) && (endStyle == null)) {
      return;
    }

    // Get content
    RNSharedElementContent startContent = startItem.getContent();
    RNSharedElementContent endContent = endItem.getContent();
    if ((mAnimation == RNSharedElementAnimation.MOVE) && (startContent == null) && (endContent != null)) {
      startContent = endContent;
    }
    // Determine starting scene that is currently visible to the user
    if (mInitialVisibleAncestorIndex < 0) {
      if ((startStyle != null) && (endStyle == null)) {
        mInitialVisibleAncestorIndex = (endItem.getNode() == null) ? 1 : 0;
      } else if ((endStyle != null) && (startStyle == null)) {
        mInitialVisibleAncestorIndex = (startItem.getNode() == null) ? 0 : 1;
      } else if ((startStyle != null) && (endStyle != null)) {
        float startAncestorVisibility = RNSharedElementStyle.getAncestorVisibility(parent, startStyle);
        float endAncestorVisibility = RNSharedElementStyle.getAncestorVisibility(parent, endStyle);
        mInitialVisibleAncestorIndex = endAncestorVisibility > startAncestorVisibility ? 1 : 0;
      } else {
        // Wait for both styles before deciding which ancestor is currently visible to the user
      }
    }

    // Get layout
    boolean startCompensate = mInitialVisibleAncestorIndex == 1;
    RectF startLayout = RNSharedElementStyle.normalizeLayout(startCompensate, startStyle, mParentOffset);
    Rect startFrame = (startStyle != null) ? startStyle.frame : RNSharedElementStyle.EMPTY_RECT;
    boolean endCompensate = mInitialVisibleAncestorIndex == 0;
    RectF endLayout = RNSharedElementStyle.normalizeLayout(endCompensate, endStyle, mParentOffset);
    Rect endFrame = (endStyle != null) ? endStyle.frame : RNSharedElementStyle.EMPTY_RECT;

    // Get clipped areas
    RectF startClippedLayout = RNSharedElementStyle.normalizeLayout(startCompensate, (startStyle != null) ? startItem.getClippedLayout() : RNSharedElementStyle.EMPTY_RECTF, startStyle, mParentOffset);
    RectF startClipInsets = getClipInsets(startLayout, startClippedLayout);
    RectF endClippedLayout = RNSharedElementStyle.normalizeLayout(endCompensate, (endStyle != null) ? endItem.getClippedLayout() : RNSharedElementStyle.EMPTY_RECTF, endStyle, mParentOffset);
    RectF endClipInsets = getClipInsets(endLayout, endClippedLayout);

    RectF startContentLayout = ((startStyle != null) && (startContent != null))
            ? RNSharedElementStyle.normalizeLayout(
                    startCompensate,
                    RNSharedElementContent.getLayout(startLayout, startContent, startStyle.scaleType, false),
                    startStyle,
                    mParentOffset
            )
            : RNSharedElementStyle.EMPTY_RECTF;
    RNSharedElementContent endContentForLayout = (endContent != null) ? endContent : startContent;
    RectF endContentLayout = ((endStyle != null) && (endContentForLayout != null))
            ? RNSharedElementStyle.normalizeLayout(
                    endCompensate,
                    RNSharedElementContent.getLayout(endLayout, endContentForLayout, endStyle.scaleType, false),
                    endStyle,
                    mParentOffset
            )
            : RNSharedElementStyle.EMPTY_RECTF;

    boolean startEndpointVisible = (startStyle != null)
            && (startContent != null)
            && hasVisibleArea(startClippedLayout);
    boolean endEndpointVisible = (endStyle != null)
            && (endContentForLayout != null)
            && hasVisibleArea(endClippedLayout);
    boolean fadeStartEndpoint = (mAnimation == RNSharedElementAnimation.MOVE)
            && startEndpointVisible
            && !endEndpointVisible;
    boolean fadeEndEndpoint = (mAnimation == RNSharedElementAnimation.MOVE)
            && !startEndpointVisible
            && endEndpointVisible;
    if (!mNativePreparing && mNativeDriver && !Float.isNaN(mNativeTo)) {
      mHoldStartHiddenOnRelease = fadeStartEndpoint && mNativeTo >= 0.5f;
      mHoldEndHiddenOnRelease = fadeEndEndpoint && mNativeTo < 0.5f;
    }

    // Get interpolated layout
    RectF interpolatedLayout;
    RectF interpolatedContentLayout;
    RectF interpolatedClipInsets;
    RNSharedElementStyle interpolatedStyle;
    if (fadeStartEndpoint) {
      // A virtualized or fully clipped destination has no useful geometry.
      // Keep the endpoint the user can see still and let it fade away.
      interpolatedLayout = startLayout;
      interpolatedContentLayout = startContentLayout;
      interpolatedStyle = startStyle;
      interpolatedClipInsets = startClipInsets;
    } else if (fadeEndEndpoint) {
      interpolatedLayout = endLayout;
      interpolatedContentLayout = endContentLayout;
      interpolatedStyle = endStyle;
      interpolatedClipInsets = endClipInsets;
    } else if ((startStyle != null) && (endStyle != null)) {
      interpolatedLayout = RNSharedElementStyle.getInterpolatedLayout(startLayout, endLayout, mNodePosition);
      interpolatedContentLayout = RNSharedElementStyle.getInterpolatedLayout(startContentLayout, endContentLayout, mNodePosition);
      interpolatedClipInsets = getInterpolatedClipInsets(interpolatedLayout, startClipInsets, startClippedLayout, endClipInsets, endClippedLayout, mNodePosition);
      interpolatedStyle = RNSharedElementStyle.getInterpolatedStyle(startStyle, startLayout, endStyle, endLayout, mNodePosition);
    } else if (startStyle != null) {
      interpolatedLayout = startLayout;
      interpolatedContentLayout = startContentLayout;
      interpolatedStyle = startStyle;
      interpolatedClipInsets = startClipInsets;
    } else {
      if (!mInitialNodePositionSet) {
        mNodePosition = 1.0f;
        mInitialNodePositionSet = true;
      }
      interpolatedLayout = endLayout;
      interpolatedContentLayout = endContentLayout;
      interpolatedStyle = endStyle;
      interpolatedClipInsets = endClipInsets;
    }

    // Calculate outer frame rect. Apply clipping insets if needed
    RectF parentLayout;
    if (interpolatedClipInsets.left > 0.0f || interpolatedClipInsets.top > 0.0f || interpolatedClipInsets.right > 0.0f || interpolatedClipInsets.bottom > 0.0f) {
      parentLayout = new RectF(interpolatedLayout);
      parentLayout.left += interpolatedClipInsets.left;
      parentLayout.top += interpolatedClipInsets.top;
      parentLayout.right -= interpolatedClipInsets.right;
      parentLayout.bottom -= interpolatedClipInsets.bottom;
      mRequiresClipping = true;
    } else if ((startContent != null && startContent.view instanceof android.widget.ImageView)
            || (endContentForLayout != null && endContentForLayout.view instanceof android.widget.ImageView)) {
      // ImageViews render at their mapped drawable rect, which can extend past
      // a cover container. Clip that rect to the interpolated element bounds.
      parentLayout = new RectF(interpolatedLayout);
      mRequiresClipping = true;
    } else if (mResize == RNSharedElementResize.CLIP) {
      parentLayout = new RectF(interpolatedLayout);
      mRequiresClipping = true;
    } else {
      parentLayout = new RectF(startLayout);
      parentLayout.union(endLayout);
      mRequiresClipping = false;
    }

    // Update outer viewgroup layout. The outer viewgroup hosts 2 inner views
    // which draw the content & elevation. The outer viewgroup performs additional
    // clipping on these views.
    super.layout(
            -mParentOffset[0],
            -mParentOffset[1],
            (int) Math.ceil(parentLayout.width() - mParentOffset[0]),
            (int) Math.ceil(parentLayout.height() - mParentOffset[1])
    );
    setTranslationX(parentLayout.left);
    setTranslationY(parentLayout.top);
    updateRoundedClip(interpolatedStyle, interpolatedLayout, parentLayout);

    // Determine opacity
    float startAlpha = 1.0f;
    float endAlpha = 1.0f;
    switch (mAnimation) {
      case MOVE:
        if (fadeStartEndpoint) {
          float fadeProgress = getStartEndpointFadeProgress(mNodePosition);
          startAlpha = interpolatedStyle.opacity * (1.0f - fadeProgress);
          endAlpha = 0.0f;
        } else if (fadeEndEndpoint) {
          float fadeProgress = getEndEndpointFadeProgress(mNodePosition);
          startAlpha = 0.0f;
          endAlpha = interpolatedStyle.opacity * fadeProgress;
        } else {
          startAlpha = interpolatedStyle.opacity;
          endAlpha = (startStyle == null) ? interpolatedStyle.opacity : 0.0f;
        }
        break;
      case FADE:
        startAlpha = ((startStyle != null) ? startStyle.opacity : 1) * (1 - mNodePosition);
        endAlpha = ((endStyle != null) ? endStyle.opacity : 1) * mNodePosition;
        break;
      case FADE_IN:
        startAlpha = 0.0f;
        endAlpha = ((endStyle != null) ? endStyle.opacity : 1) * mNodePosition;
        break;
      case FADE_OUT:
        startAlpha = ((startStyle != null) ? startStyle.opacity : 1) * (1 - mNodePosition);
        endAlpha = 0.0f;
        break;
    }

    // Render the start view
    if ((mAnimation != RNSharedElementAnimation.FADE_IN) && !fadeEndEndpoint) {
      boolean renderMappedImageContent = startContent != null
              && startContent.view instanceof android.widget.ImageView;
      RectF startRenderLayout = (mResize == RNSharedElementResize.CLIP || renderMappedImageContent)
              ? interpolatedContentLayout
              : interpolatedLayout;
      mStartView.updateViewAndDrawable(
              startRenderLayout,
              parentLayout,
              mResize == RNSharedElementResize.CLIP ? startContentLayout : startLayout,
              startFrame,
              startContent,
              interpolatedStyle,
              startAlpha,
              mResize,
              mAlign,
              mNodePosition
      );
    }

    // Render the end view as well for the "cross-fade" animations
    if ((mAnimation == RNSharedElementAnimation.FADE)
            || (mAnimation == RNSharedElementAnimation.FADE_IN)
            || fadeEndEndpoint
            || ((mAnimation == RNSharedElementAnimation.MOVE) && (startStyle == null))
    ) {
      boolean renderMappedImageContent = endContentForLayout != null
              && endContentForLayout.view instanceof android.widget.ImageView;
      RectF endRenderLayout = (mResize == RNSharedElementResize.CLIP || renderMappedImageContent)
              ? interpolatedContentLayout
              : interpolatedLayout;
      mEndView.updateViewAndDrawable(
              endRenderLayout,
              parentLayout,
              mResize == RNSharedElementResize.CLIP ? endContentLayout : endLayout,
              endFrame,
              endContentForLayout,
              interpolatedStyle,
              endAlpha,
              mResize,
              mAlign,
              mNodePosition
      );

      // Also apply a fade effect on the elevation. This reduces the shadow visibility
      // underneath the view which becomes visible when the transparency of the view
      // is set. This in turn makes the shadow very visible and gives the whole view
      // a "grayish" appearance. The following code tries to reduce that visual artefact.
      if (interpolatedStyle.elevation > 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          mStartView.setOutlineAmbientShadowColor(Color.argb(startAlpha, 0, 0, 0));
          mStartView.setOutlineSpotShadowColor(Color.argb(startAlpha, 0, 0, 0));
          mEndView.setOutlineAmbientShadowColor(Color.argb(endAlpha, 0, 0, 0));
          mEndView.setOutlineSpotShadowColor(Color.argb(endAlpha, 0, 0, 0));
        }
      }
    } else {
      mEndView.reset();
    }

    // Fire events
    if ((startStyle != null) && !startItem.getHasCalledOnMeasure()) {
      startItem.setHasCalledOnMeasure(true);
      fireMeasureEvent("startNode", startItem, startLayout, startClippedLayout, startContentLayout);
    }
    if ((endStyle != null) && !endItem.getHasCalledOnMeasure()) {
      endItem.setHasCalledOnMeasure(true);
      fireMeasureEvent("endNode", endItem, endLayout, endClippedLayout, endContentLayout);
    }
  }

  private void updateRoundedClip(
          RNSharedElementStyle style,
          RectF elementLayout,
          RectF parentLayout
  ) {
    mHasRoundedClip = style.borderTopLeftRadius > 0
            || style.borderTopRightRadius > 0
            || style.borderBottomRightRadius > 0
            || style.borderBottomLeftRadius > 0;
    mClipPath.reset();
    if (!mHasRoundedClip) return;

    // Expo Image's mapped drawable can be larger than its cover container.
    // Clip in the element's coordinate space so its interpolated corner radii
    // follow the thumbnail instead of rounding the oversized drawable bounds.
    RectF localElementLayout = new RectF(
            elementLayout.left - parentLayout.left,
            elementLayout.top - parentLayout.top,
            elementLayout.right - parentLayout.left,
            elementLayout.bottom - parentLayout.top
    );
    mClipPath.addRoundRect(
            localElementLayout,
            new float[]{
                    style.borderTopLeftRadius,
                    style.borderTopLeftRadius,
                    style.borderTopRightRadius,
                    style.borderTopRightRadius,
                    style.borderBottomRightRadius,
                    style.borderBottomRightRadius,
                    style.borderBottomLeftRadius,
                    style.borderBottomLeftRadius
            },
            Path.Direction.CW
    );
  }

  private void updateNodeVisibility() {
    if (mNativePreparing) {
      setVisibility(INVISIBLE);
      for (RNSharedElementTransitionItem item : mItems) {
        item.setHidden(false);
      }
      return;
    }

    setVisibility(VISIBLE);
    boolean shouldDeferHide = !mHasDrawnRenderableFrame;
    for (RNSharedElementTransitionItem item : mItems) {
      boolean hidden = mInitialLayoutPassCompleted
              && (item.getStyle() != null)
              && (item.getContent() != null);
      if (hidden && shouldDeferHide) hidden = false;
      if (hidden && (mAnimation == RNSharedElementAnimation.FADE_IN) && item.getName().equals("start"))
        hidden = false;
      if (hidden && (mAnimation == RNSharedElementAnimation.FADE_OUT) && item.getName().equals("end"))
        hidden = false;
      item.setHidden(hidden);
    }
  }

  static private RectF getClipInsets(RectF layout, RectF clippedLayout) {
    return new RectF(
            clippedLayout.left - layout.left,
            clippedLayout.top - layout.top,
            layout.right - clippedLayout.right,
            layout.bottom - clippedLayout.bottom
    );
  }

  static private RectF getInterpolatedClipInsets(
          RectF interpolatedLayout,
          RectF startClipInsets,
          RectF startClippedLayout,
          RectF endClipInsets,
          RectF endClippedLayout,
          float position) {
    RectF clipInsets = new RectF();

    // Top
    if ((endClipInsets.top == 0) && (startClipInsets.top != 0) && (startClippedLayout.top <= endClippedLayout.top)) {
      clipInsets.top = Math.max(0, startClippedLayout.top - interpolatedLayout.top);
    } else if ((startClipInsets.top == 0) && (endClipInsets.top != 0) && (endClippedLayout.top <= startClippedLayout.top)) {
      clipInsets.top = Math.max(0, endClippedLayout.top - interpolatedLayout.top);
    } else {
      clipInsets.top = (startClipInsets.top + ((endClipInsets.top - startClipInsets.top) * position));
    }

    // Bottom
    if ((endClipInsets.bottom == 0) && (startClipInsets.bottom != 0) && (startClippedLayout.bottom >= endClippedLayout.bottom)) {
      clipInsets.bottom = Math.max(0, interpolatedLayout.bottom - startClippedLayout.bottom);
    } else if ((startClipInsets.bottom == 0) && (endClipInsets.bottom != 0) && (endClippedLayout.bottom >= startClippedLayout.bottom)) {
      clipInsets.bottom = Math.max(0, interpolatedLayout.bottom - endClippedLayout.bottom);
    } else {
      clipInsets.bottom = (startClipInsets.bottom + ((endClipInsets.bottom - startClipInsets.bottom) * position));
    }

    // Left
    if ((endClipInsets.left == 0) && (startClipInsets.left != 0) && (startClippedLayout.left <= endClippedLayout.left)) {
      clipInsets.left = Math.max(0, startClippedLayout.left - interpolatedLayout.left);
    } else if ((startClipInsets.left == 0) && (endClipInsets.left != 0) && (endClippedLayout.left <= startClippedLayout.left)) {
      clipInsets.left = Math.max(0, endClippedLayout.left - interpolatedLayout.left);
    } else {
      clipInsets.left = (startClipInsets.left + ((endClipInsets.left - startClipInsets.left) * position));
    }

    // Right
    if ((endClipInsets.right == 0) && (startClipInsets.right != 0) && (startClippedLayout.right >= endClippedLayout.right)) {
      clipInsets.right = Math.max(0, interpolatedLayout.right - startClippedLayout.right);
    } else if ((startClipInsets.right == 0) && (endClipInsets.right != 0) && (endClippedLayout.right >= startClippedLayout.right)) {
      clipInsets.right = Math.max(0, interpolatedLayout.right - endClippedLayout.right);
    } else {
      clipInsets.right = (startClipInsets.right + ((endClipInsets.right - startClipInsets.right) * position));
    }

    return clipInsets;
  }

  private void fireMeasureEvent(String name, RNSharedElementTransitionItem item, RectF layout, RectF clippedLayout, RectF contentLayout) {
    ReactContext reactContext = (ReactContext) getContext();
    RNSharedElementStyle style = item.getStyle();
    RNSharedElementContent content = item.getContent();

    WritableMap layoutData = Arguments.createMap();
    layoutData.putDouble("x", PixelUtil.toDIPFromPixel(layout.left - mParentOffset[0]));
    layoutData.putDouble("y", PixelUtil.toDIPFromPixel(layout.top - mParentOffset[1]));
    layoutData.putDouble("width", PixelUtil.toDIPFromPixel(layout.width()));
    layoutData.putDouble("height", PixelUtil.toDIPFromPixel(layout.height()));
    layoutData.putDouble("visibleX", PixelUtil.toDIPFromPixel(clippedLayout.left - mParentOffset[0]));
    layoutData.putDouble("visibleY", PixelUtil.toDIPFromPixel(clippedLayout.top - mParentOffset[1]));
    layoutData.putDouble("visibleWidth", PixelUtil.toDIPFromPixel(clippedLayout.width()));
    layoutData.putDouble("visibleHeight", PixelUtil.toDIPFromPixel(clippedLayout.height()));
    layoutData.putDouble("contentX", PixelUtil.toDIPFromPixel(contentLayout.left - mParentOffset[0]));
    layoutData.putDouble("contentY", PixelUtil.toDIPFromPixel(contentLayout.top - mParentOffset[1]));
    layoutData.putDouble("contentWidth", PixelUtil.toDIPFromPixel(contentLayout.width()));
    layoutData.putDouble("contentHeight", PixelUtil.toDIPFromPixel(contentLayout.height()));

    WritableMap styleData = Arguments.createMap();
    styleData.putDouble("borderTopLeftRadius", PixelUtil.toDIPFromPixel(style.borderTopLeftRadius));
    styleData.putDouble("borderTopRightRadius", PixelUtil.toDIPFromPixel(style.borderTopRightRadius));
    styleData.putDouble("borderBottomLeftRadius", PixelUtil.toDIPFromPixel(style.borderBottomLeftRadius));
    styleData.putDouble("borderBottomRightRadius", PixelUtil.toDIPFromPixel(style.borderBottomRightRadius));

    WritableMap eventData = Arguments.createMap();
    eventData.putString("node", name);
    eventData.putMap("layout", layoutData);
    RNSharedElementDrawable.ViewType viewType = (content != null)
            ? RNSharedElementDrawable.getViewType(content.view, style)
            : RNSharedElementDrawable.ViewType.NONE;
    eventData.putString("contentType", viewType.getValue());
    eventData.putMap("style", styleData);
    EventDispatcher eventDispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, getId());
    if (eventDispatcher == null) {
      return;
    }
    int surfaceId = UIManagerHelper.getSurfaceId(this);
    eventDispatcher.dispatchEvent(new RNSharedElementMeasureEvent(surfaceId, getId(), eventData));
  }
}
