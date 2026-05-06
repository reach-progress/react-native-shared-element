package com.ijzerenhein.sharedelement;

import java.util.ArrayList;

import android.annotation.SuppressLint;
import android.os.Build;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Matrix;
import android.view.View;
import android.view.ViewGroup;
import android.view.Choreographer;
import android.os.SystemClock;
import android.util.Log;

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
  static private final String LOG_TAG = "RNSharedElementTransition";
  static private final boolean DEBUG = true;
  static private int sNextInstanceId = 1;
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
  private final int mInstanceId;
  private final long mCreatedAtMs = SystemClock.uptimeMillis();
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
  private final RNSharedElementView mStartView;
  private final RNSharedElementView mEndView;
  private int mInitialVisibleAncestorIndex = -1;
  private boolean mHasLoggedFirstRenderableLayout = false;
  private boolean mLoggedBootstrapWaitForParent = false;
  private boolean mLoggedBootstrapWaitForSize = false;
  private boolean mHasDrawnRenderableFrame = false;
  private boolean mHasLoggedVisibilityDefer = false;
  private int mLastLoggedLayoutBucket = -1;
  private boolean mShouldLogRenderSnapshot = false;

  // Native-timer animation state for Fabric interop.
  private boolean mNativeDriver = false;
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
    mInstanceId = sNextInstanceId++;
    mNodeManager = nodeManager;
    mItems.add(new RNSharedElementTransitionItem(nodeManager, "start"));
    mItems.add(new RNSharedElementTransitionItem(nodeManager, "end"));

    mStartView = new RNSharedElementView(context);
    addView(mStartView);

    mEndView = new RNSharedElementView(context);
    addView(mEndView);
    log("created");
  }

  private void log(String message) {
    if (!DEBUG) return;
    long elapsed = SystemClock.uptimeMillis() - mCreatedAtMs;
    Log.d(LOG_TAG, "#" + mInstanceId + " t+" + elapsed + "ms " + message);
  }

  void releaseData() {
    // Defensive cleanup to stop any pending frame callbacks.
    log("releaseData");
    stopNativeAnimation("releaseData");
    for (RNSharedElementTransitionItem item : mItems) {
      item.setNode(null);
    }
  }

  RNSharedElementNodeManager getNodeManager() {
    return mNodeManager;
  }

  void setItemNode(Item item, RNSharedElementNode node) {
    log(
      "setItemNode item="
        + item
        + " node="
        + (node != null ? node.getReactTag() : "null")
        + " initialLayout="
        + mInitialLayoutPassCompleted
    );
    mItems.get(item.getValue()).setNode(node);
    mHasDrawnRenderableFrame = false;
    mHasLoggedVisibilityDefer = false;
    // Nodes/ancestors may resolve after layout in Fabric; queue start.
    mNativeAnimationPending = mNativeDriver;
    // Kick off style/content fetch immediately so data can be ready by the
    // time the first layout pass completes. This reduces first-frame lag where
    // the destination is visible before shared elements are rendered.
    requestStylesAndContent(true);
    startNativeAnimationIfReady("setItemNode");
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

  private boolean isItemRenderable(RNSharedElementTransitionItem item) {
    return (item.getStyle() != null) && (item.getContent() != null);
  }

  private boolean hasRenderableSnapshot() {
    RNSharedElementTransitionItem startItem = mItems.get(Item.START.getValue());
    RNSharedElementTransitionItem endItem = mItems.get(Item.END.getValue());
    return isItemRenderable(startItem) || isItemRenderable(endItem);
  }

  private void tryCompleteInitialLayoutPass(String reason) {
    if (mInitialLayoutPassCompleted) return;
    View parent = (View) getParent();
    if (parent == null) {
      if (!mLoggedBootstrapWaitForParent) {
        mLoggedBootstrapWaitForParent = true;
        log("initial layout bootstrap waiting reason=" + reason + " parent=null");
      }
      return;
    }
    int width = getWidth();
    int height = getHeight();
    if ((width <= 0 || height <= 0) && !mLoggedBootstrapWaitForSize) {
      mLoggedBootstrapWaitForSize = true;
      log(
        "initial layout bootstrap proceeding with size="
          + width
          + "x"
          + height
          + " reason="
          + reason
      );
    }
    mInitialLayoutPassCompleted = true;
    mLoggedBootstrapWaitForParent = false;
    mLoggedBootstrapWaitForSize = false;
    log(
      "initial layout bootstrap completed reason="
        + reason
        + " size="
        + width
        + "x"
        + height
    );
    requestStylesAndContent(true);
    updateLayout();
    updateNodeVisibility();
  }

  private void startNativeAnimationIfReady(String reason) {
    // Drive nodePosition natively when layout/content are ready.
    if (!mNativeDriver) {
      log("native anim skip reason=" + reason + " (nativeDriver=false)");
      return;
    }
    if (!mNativeAnimationPending) {
      log("native anim skip reason=" + reason + " (pending=false)");
      return;
    }
    tryCompleteInitialLayoutPass("startNativeAnimationIfReady:" + reason);
    if (!mInitialLayoutPassCompleted) {
      log("native anim wait reason=" + reason + " (initialLayout=false)");
      return;
    }
    // Zero duration means no-op to avoid a tight loop.
    if (mNativeDuration <= 0.0f) {
      log("native anim skip reason=" + reason + " (duration<=0)");
      mNativeAnimationPending = false;
      return;
    }
    if (!hasRenderableSnapshot()) {
      RNSharedElementTransitionItem startItem = mItems.get(Item.START.getValue());
      RNSharedElementTransitionItem endItem = mItems.get(Item.END.getValue());
      log(
        "native anim wait reason="
          + reason
          + " (renderable=false startStyle="
          + (startItem.getStyle() != null)
          + " startContent="
          + (startItem.getContent() != null)
          + " endStyle="
          + (endItem.getStyle() != null)
          + " endContent="
          + (endItem.getContent() != null)
          + ")"
      );
      return;
    }
    if (mNativeAnimating) {
      log("native anim skip reason=" + reason + " (alreadyAnimating=true)");
      return;
    }

    mNativeAnimating = true;
    mNativeAnimationPending = false;

    long nowMs = SystemClock.uptimeMillis();
    mNativeStartTimeMs = nowMs + (long) mNativeDelay;

    if (Float.isNaN(mNativeFrom)) mNativeFrom = mNodePosition;
    if (Float.isNaN(mNativeTo)) mNativeTo = 1.0f;
    log(
      "native anim start reason="
        + reason
        + " from="
        + mNativeFrom
        + " to="
        + mNativeTo
        + " duration="
        + mNativeDuration
        + " delay="
        + mNativeDelay
    );

    if (mChoreographer == null) {
      // Use Choreographer to sync with UI rendering.
      mChoreographer = Choreographer.getInstance();
    }
    mChoreographer.postFrameCallback(mFrameCallback);
  }

  private void stopNativeAnimation(String reason) {
    // Remove callbacks to avoid leaks or duplicate frames.
    if (!mNativeAnimating) return;
    mNativeAnimating = false;
    log("native anim stop reason=" + reason);
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
      log("native anim frame complete value=" + value);
      stopNativeAnimation("complete");
    } else if (mChoreographer != null) {
      mChoreographer.postFrameCallback(mFrameCallback);
    }
  }

  void setNativeDriver(final boolean nativeDriver) {
    // When enabled, we defer start until layout + nodes are ready.
    if (mNativeDriver != nativeDriver) {
      mNativeDriver = nativeDriver;
      log("setNativeDriver value=" + nativeDriver);
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady("nativeDriver");
    }
  }

  void setNativeDuration(final float nativeDuration) {
    // Any timing change should restart the pending native animation.
    if (mNativeDuration != nativeDuration) {
      mNativeDuration = nativeDuration;
      log("setNativeDuration value=" + nativeDuration);
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady("nativeDuration");
    }
  }

  void setNativeDelay(final float nativeDelay) {
    // Any timing change should restart the pending native animation.
    if (mNativeDelay != nativeDelay) {
      mNativeDelay = nativeDelay;
      log("setNativeDelay value=" + nativeDelay);
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady("nativeDelay");
    }
  }

  void setNativeFrom(final float nativeFrom) {
    // Any timing change should restart the pending native animation.
    if (mNativeFrom != nativeFrom) {
      mNativeFrom = nativeFrom;
      log("setNativeFrom value=" + nativeFrom);
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady("nativeFrom");
    }
  }

  void setNativeTo(final float nativeTo) {
    // Any timing change should restart the pending native animation.
    if (mNativeTo != nativeTo) {
      mNativeTo = nativeTo;
      log("setNativeTo value=" + nativeTo);
      mNativeAnimationPending = mNativeDriver;
      startNativeAnimationIfReady("nativeTo");
    }
  }

  void setNodePosition(final float nodePosition) {
    if (mNodePosition != nodePosition) {
      // Ignore JS updates while native driver is active to avoid contention.
      if (mNativeAnimating && mNativeDriver) {
        return;
      }
      if (mNativeAnimating) {
        stopNativeAnimation("nodePosition set");
      }
      //Log.d(LOG_TAG, "setNodePosition " + nodePosition + ", mInitialLayoutPassCompleted: " + mInitialLayoutPassCompleted);
      mNodePosition = nodePosition;
      mInitialNodePositionSet = true;
      log("setNodePosition value=" + nodePosition);
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
    log(
      "onLayout changed="
        + changed
        + " frame=["
        + left
        + ","
        + top
        + ","
        + right
        + ","
        + bottom
        + "] reactLayoutSet="
        + mReactLayoutSet
    );
    if (!mReactLayoutSet) {
      mReactLayoutSet = true;

      // Wait for the whole layout pass to have completed before
      // requesting the layout and content
      requestStylesAndContent(true);
      mInitialLayoutPassCompleted = true;
      mLoggedBootstrapWaitForParent = false;
      mLoggedBootstrapWaitForSize = false;
      log("initial layout pass completed");
      updateLayout();
      updateNodeVisibility();
      // Fabric interop bootstrap point for native animation.
      startNativeAnimationIfReady("onLayout");
    }
  }

  @Override
  public boolean hasOverlappingRendering() {
    return false;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    log("onAttachedToWindow");
    tryCompleteInitialLayoutPass("onAttachedToWindow");
    startNativeAnimationIfReady("onAttachedToWindow");
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    log("onDetachedFromWindow");
    stopNativeAnimation("onDetachedFromWindow");
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    //Log.d(LOG_TAG, "dispatchDraw, mRequiresClipping: " + mRequiresClipping + ", width: " + getWidth() + ", height: " + getHeight());
    if (mRequiresClipping) {
      canvas.clipRect(0, 0, getWidth(), getHeight());
    }
    super.dispatchDraw(canvas);
    if (!mHasDrawnRenderableFrame && hasRenderableSnapshot()) {
      mHasDrawnRenderableFrame = true;
      log("first renderable dispatchDraw");
      updateNodeVisibility();
    }

    // Draw content
    //Paint backgroundPaint = new Paint();
    //backgroundPaint.setColor(Color.argb(128, 255, 0, 0));
    //canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);
  }

  private void requestStylesAndContent(boolean force) {
    if (!mInitialLayoutPassCompleted && !force) {
      log("requestStylesAndContent skipped force=false initialLayout=false");
      return;
    }
    log(
      "requestStylesAndContent force="
        + force
        + " initialLayout="
        + mInitialLayoutPassCompleted
    );
    for (final RNSharedElementTransitionItem item : mItems) {
      if (item.getNeedsStyle()) {
        log("requestStyle item=" + item.getName());
        item.setNeedsStyle(false);
        item.getNode().requestStyle(args -> {
          RNSharedElementStyle style = (RNSharedElementStyle) args[0];
          item.setStyle(style);
          log(
            "didLoadStyle item="
              + item.getName()
              + " layout="
              + (style != null ? style.layout : "null")
          );
          tryCompleteInitialLayoutPass("didLoadStyle");
          updateLayout();
          updateNodeVisibility();
          startNativeAnimationIfReady("didLoadStyle");
        });
      }
      if (item.getNeedsContent()) {
        log("requestContent item=" + item.getName());
        item.setNeedsContent(false);
        item.getNode().requestContent(args -> {
          RNSharedElementContent content = (RNSharedElementContent) args[0];
          item.setContent(content);
          log(
            "didLoadContent item="
              + item.getName()
              + " view="
              + ((content != null && content.view != null)
                ? content.view.getClass().getSimpleName()
                : "null")
          );
          tryCompleteInitialLayoutPass("didLoadContent");
          updateLayout();
          updateNodeVisibility();
          startNativeAnimationIfReady("didLoadContent");
        });
      }
    }
  }

  private void updateLayout() {
    if (!mInitialLayoutPassCompleted) {
      log("updateLayout waiting initialLayout=false");
      return;
    }

    // Local data
    RNSharedElementTransitionItem startItem = mItems.get(Item.START.getValue());
    RNSharedElementTransitionItem endItem = mItems.get(Item.END.getValue());

    // Get parent offset
    View parent = (View) getParent();
    if (parent == null) {
      log("updateLayout waiting parent=null");
      return;
    }
    parent.getLocationInWindow(mParentOffset);

    // Get styles
    RNSharedElementStyle startStyle = startItem.getStyle();
    RNSharedElementStyle endStyle = endItem.getStyle();
    if ((startStyle == null) && (endStyle == null)) {
      log("updateLayout waiting startStyle=null endStyle=null");
      return;
    }

    // Get content
    RNSharedElementContent startContent = startItem.getContent();
    RNSharedElementContent endContent = endItem.getContent();
    if ((mAnimation == RNSharedElementAnimation.MOVE) && (startContent == null) && (endContent != null)) {
      startContent = endContent;
    }
    if (!mHasLoggedFirstRenderableLayout) {
      log(
        "updateLayout first-renderable-state startStyle="
          + (startStyle != null)
          + " endStyle="
          + (endStyle != null)
          + " startContent="
          + (startContent != null)
          + " endContent="
          + (endContent != null)
          + " nodePosition="
          + mNodePosition
      );
      mHasLoggedFirstRenderableLayout = true;
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
                    RNSharedElementContent.getLayout(startLayout, startContent.size, startStyle.scaleType, false),
                    startStyle,
                    mParentOffset
            )
            : RNSharedElementStyle.EMPTY_RECTF;
    RNSharedElementContent endContentForLayout = (endContent != null) ? endContent : startContent;
    RectF endContentLayout = ((endStyle != null) && (endContentForLayout != null))
            ? RNSharedElementStyle.normalizeLayout(
                    endCompensate,
                    RNSharedElementContent.getLayout(endLayout, endContentForLayout.size, endStyle.scaleType, false),
                    endStyle,
                    mParentOffset
            )
            : RNSharedElementStyle.EMPTY_RECTF;

    logLayoutSnapshot(
      "source",
      startStyle,
      endStyle,
      startContent,
      endContent,
      startLayout,
      endLayout,
      startContentLayout,
      endContentLayout
    );

    // Get interpolated layout
    RectF interpolatedLayout;
    RectF interpolatedContentLayout;
    RectF interpolatedClipInsets;
    RNSharedElementStyle interpolatedStyle;
    if ((startStyle != null) && (endStyle != null)) {
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
    } else if (mResize == RNSharedElementResize.CLIP) {
      parentLayout = new RectF(interpolatedLayout);
      mRequiresClipping = true;
    } else {
      parentLayout = new RectF(startLayout);
      parentLayout.union(endLayout);
      mRequiresClipping = false;
    }

    //Log.d(LOG_TAG, "updateLayout: " + mNodePosition);

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

    // Determine opacity
    float startAlpha = 1.0f;
    float endAlpha = 1.0f;
    switch (mAnimation) {
      case MOVE:
        startAlpha = interpolatedStyle.opacity;
        endAlpha = (startStyle == null) ? interpolatedStyle.opacity : 0.0f;
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
    if (mAnimation != RNSharedElementAnimation.FADE_IN) {
      RectF startRenderLayout = mResize == RNSharedElementResize.CLIP
              ? interpolatedContentLayout
              : interpolatedLayout;
      logRenderSnapshot(
        "start",
        interpolatedLayout,
        interpolatedContentLayout,
        parentLayout,
        startRenderLayout,
        startFrame
      );
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
            || ((mAnimation == RNSharedElementAnimation.MOVE) && (startStyle == null))
    ) {
      RectF endRenderLayout = mResize == RNSharedElementResize.CLIP
              ? interpolatedContentLayout
              : interpolatedLayout;
      logRenderSnapshot(
        "end",
        interpolatedLayout,
        interpolatedContentLayout,
        parentLayout,
        endRenderLayout,
        endFrame
      );
      mEndView.updateViewAndDrawable(
              endRenderLayout,
              parentLayout,
              mResize == RNSharedElementResize.CLIP ? endContentLayout : endLayout,
              endFrame,
              endContent,
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

  private boolean shouldLogLayoutSnapshot() {
    mShouldLogRenderSnapshot = false;
    if (!DEBUG) return false;
    int bucket = Math.round(mNodePosition * 10.0f);
    if (bucket == mLastLoggedLayoutBucket) return false;
    mLastLoggedLayoutBucket = bucket;
    mShouldLogRenderSnapshot = true;
    return true;
  }

  private void logLayoutSnapshot(
    String phase,
    RNSharedElementStyle startStyle,
    RNSharedElementStyle endStyle,
    RNSharedElementContent startContent,
    RNSharedElementContent endContent,
    RectF startLayout,
    RectF endLayout,
    RectF startContentLayout,
    RectF endContentLayout
  ) {
    if (!shouldLogLayoutSnapshot()) return;
    log(
      phase
        + " position="
        + mNodePosition
        + " animation="
        + mAnimation
        + " resize="
        + mResize
        + " align="
        + mAlign
        + " initialVisibleAncestor="
        + mInitialVisibleAncestorIndex
        + " startScaleType="
        + (startStyle != null ? startStyle.scaleType : null)
        + " endScaleType="
        + (endStyle != null ? endStyle.scaleType : null)
        + " startLayout="
        + startLayout
        + " endLayout="
        + endLayout
        + " startContentSize="
        + describeContentSize(startContent)
        + " endContentSize="
        + describeContentSize(endContent)
        + " startContentLayout="
        + startContentLayout
        + " endContentLayout="
        + endContentLayout
    );
  }

  private void logRenderSnapshot(
    String item,
    RectF interpolatedLayout,
    RectF interpolatedContentLayout,
    RectF parentLayout,
    RectF renderLayout,
    Rect frame
  ) {
    if (!DEBUG || !mShouldLogRenderSnapshot) return;
    log(
      "render item="
        + item
        + " position="
        + mNodePosition
        + " outer="
        + interpolatedLayout
        + " content="
        + interpolatedContentLayout
        + " parent="
        + parentLayout
        + " render="
        + renderLayout
        + " frame="
        + frame
        + " requiresClipping="
        + mRequiresClipping
    );
  }

  private String describeContentSize(RNSharedElementContent content) {
    if (content == null) return "null";
    return content.size + " view=" + (content.view != null ? content.view.getClass().getSimpleName() : "null");
  }

  private void updateNodeVisibility() {
    boolean shouldDeferHide = !mHasDrawnRenderableFrame;
    if (shouldDeferHide && !mHasLoggedVisibilityDefer && hasRenderableSnapshot()) {
      mHasLoggedVisibilityDefer = true;
      log("visibility defer waiting first renderable draw");
    }
    for (RNSharedElementTransitionItem item : mItems) {
      boolean previousHidden = item.getHidden();
      boolean hidden = mInitialLayoutPassCompleted
              && (item.getStyle() != null)
              && (item.getContent() != null);
      if (hidden && shouldDeferHide) hidden = false;
      if (hidden && (mAnimation == RNSharedElementAnimation.FADE_IN) && item.getName().equals("start"))
        hidden = false;
      if (hidden && (mAnimation == RNSharedElementAnimation.FADE_OUT) && item.getName().equals("end"))
        hidden = false;
      item.setHidden(hidden);
      if (previousHidden != hidden) {
        log(
          "visibility item="
            + item.getName()
            + " hidden="
            + hidden
            + " hasStyle="
            + (item.getStyle() != null)
            + " hasContent="
            + (item.getContent() != null)
        );
      }
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
      log("fireMeasureEvent skipped eventDispatcher=null");
      return;
    }
    int surfaceId = UIManagerHelper.getSurfaceId(this);
    eventDispatcher.dispatchEvent(new RNSharedElementMeasureEvent(surfaceId, getId(), eventData));
  }
}
