package com.ijzerenhein.sharedelement;

import java.util.ArrayList;

import javax.annotation.Nullable;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.drawable.Animatable;
import android.widget.ImageView;
import android.content.Context;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReadableMap;

import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.drawee.view.GenericDraweeView;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.controller.BaseControllerListener;
import com.facebook.drawee.backends.pipeline.PipelineDraweeController;

abstract class RetryRunnable implements Runnable {
  int numRetries = 0;
}

class RNSharedElementNode {
  static private final String LOG_TAG = "RNSharedElementNode";
  static private final boolean DEBUG = false;

  private final Context mContext;
  private final int mReactTag;
  private View mView;
  private View mAncestorView;
  private final boolean mIsParent;
  private ReadableMap mStyleConfig;
  private final RNSharedElementStyle mResolveStyle;
  private View mResolvedView;
  private int mRefCount;
  private int mHideRefCount;
  private float mHideAlpha;
  private RNSharedElementStyle mStyleCache;
  private ArrayList<Callback> mStyleCallbacks;
  private RNSharedElementContent mContentCache;
  private ArrayList<Callback> mContentCallbacks;
  private BaseControllerListener<ImageInfo> mDraweeControllerListener;
  private Handler mRetryHandler;
  private final long mCreatedAtMs;
  private boolean mLoggedStyleWaitForView;
  private boolean mLoggedStyleWaitForSize;
  private boolean mLoggedStyleWaitForTransform;
  private boolean mLoggedContentWaitForView;
  private boolean mLoggedContentWaitForSize;
  private boolean mLoggedContentWaitForImage;

  RNSharedElementNode(Context context, int reactTag, View view, boolean isParent, View ancestorView, ReadableMap styleConfig) {
    mReactTag = reactTag;
    mView = view;
    mAncestorView = ancestorView;
    mIsParent = isParent;
    mStyleConfig = styleConfig;
    mResolveStyle = new RNSharedElementStyle(styleConfig, context);
    mContext = context;
    mRefCount = 1;
    mHideRefCount = 0;
    mHideAlpha = 1;
    mStyleCache = null;
    mStyleCallbacks = null;
    mContentCache = null;
    mContentCallbacks = null;
    mResolvedView = null;
    mDraweeControllerListener = null;
    mRetryHandler = null;
    mCreatedAtMs = SystemClock.uptimeMillis();
    mLoggedStyleWaitForView = false;
    mLoggedStyleWaitForSize = false;
    mLoggedStyleWaitForTransform = false;
    mLoggedContentWaitForView = false;
    mLoggedContentWaitForSize = false;
    mLoggedContentWaitForImage = false;
    log(
      "created isParent="
        + isParent
        + " view="
        + (view != null ? view.getClass().getSimpleName() : "null")
        + " ancestor="
        + (ancestorView != null ? ancestorView.getClass().getSimpleName() : "null")
    );
  }

  private void log(String message) {
    if (!DEBUG) return;
    long elapsed = SystemClock.uptimeMillis() - mCreatedAtMs;
    Log.d(LOG_TAG, "tag=" + mReactTag + " t+" + elapsed + "ms " + message);
  }

  int getReactTag() {
    return mReactTag;
  }

  int addRef() {
    return ++mRefCount;
  }

  int releaseRef() {
    if (--mRefCount == 0) {
      log("releaseRef->0 cleanup");
      removeDraweeControllerListener(mResolvedView);
      stopRetryLoop();
      mView = null;
      mAncestorView = null;
      mStyleConfig = null;
      mResolvedView = null;
      mContentCache = null;
      mContentCallbacks = null;
      mStyleCache = null;
      mStyleCallbacks = null;
    }
    return mRefCount;
  }

  void addHideRef() {
    mHideRefCount++;
    if (mHideRefCount == 1) {
      mHideAlpha = mView.getAlpha();
      mView.setAlpha(0);
      log("hideRef++ -> hidden alpha=0 (saved=" + mHideAlpha + ")");
    }
  }

  void releaseHideRef() {
    mHideRefCount--;
    if (mHideRefCount == 0) {
      mView.setAlpha(mHideAlpha);
      log("hideRef-- -> restored alpha=" + mHideAlpha);
    }
  }

  private static View resolveView(View view, RNSharedElementStyle style) {
    if (view == null) return null;

    // If the view is a ViewGroup and it contains exactly one
    // imageview with the same size, then use that imageview
    if (view instanceof ViewGroup) {
      ViewGroup viewGroup = (ViewGroup) view;
      if (viewGroup.getChildCount() == 1) {
        View childView = viewGroup.getChildAt(0);
        if (childView instanceof ImageView) {
          int left = childView.getLeft();
          int top = childView.getTop();
          int width = childView.getWidth();
          int height = childView.getHeight();
          int expectedLeft = Math.round(style.borderWidth);
          int expectedTop = Math.round(style.borderWidth);
          int expectedWidth = Math.round((float) viewGroup.getWidth() - (style.borderWidth * 2));
          int expectedHeight = Math.round((float) viewGroup.getHeight() - (style.borderWidth * 2));
          if (((left >= expectedLeft - 1) && (left <= expectedLeft + 1)) &&
                  ((top >= expectedTop - 1) && (top <= expectedTop + 1)) &&
                  ((width >= expectedWidth - 1) && (width <= expectedWidth + 1)) &&
                  ((height >= expectedHeight - 1) && (height <= expectedHeight + 1))) {
            return childView;
          }
        }
      }
    }
    return view;
  }

  View getAncestorView() {
    return mAncestorView;
  }

  View getResolvedView() {
    if (mResolvedView != null) return mResolvedView;
    View view = mView;
    if (mIsParent) {
      int childCount = ((ViewGroup) mView).getChildCount();
      if (childCount == 1) {
        view = ((ViewGroup) mView).getChildAt(0);
      } else if (childCount <= 0) {
        if (DEBUG) Log.d(LOG_TAG, "Child for parent doesn't exist");
        return null;
      }
    }
    mResolvedView = RNSharedElementNode.resolveView(view, mResolveStyle);
    return mResolvedView;
  }

  void requestStyle(Callback callback) {
    if (mStyleCache != null) {
      log("requestStyle cache-hit");
      callback.invoke(mStyleCache, this);
      return;
    }
    log("requestStyle cache-miss");
    if (mStyleCallbacks == null) mStyleCallbacks = new ArrayList<>();
    mStyleCallbacks.add(callback);
    if (!fetchInitialStyle()) {
      startRetryLoop();
    }
  }

  private boolean fetchInitialStyle() {
    View view = getResolvedView();
    if (view == null) {
      if (!mLoggedStyleWaitForView) {
        mLoggedStyleWaitForView = true;
        log("fetchInitialStyle waiting: resolvedView=null");
      }
      return false;
    }
    if (mStyleCallbacks == null) return true;

    // Get relative size and position within parent
    int left = view.getLeft();
    int top = view.getTop();
    int width = view.getWidth();
    int height = view.getHeight();
    if (width == 0 && height == 0) {
      if (!mLoggedStyleWaitForSize) {
        mLoggedStyleWaitForSize = true;
        log("fetchInitialStyle waiting: size=0x0");
      }
      return false;
    }
    Matrix transform = RNSharedElementStyle.getAbsoluteViewTransform(view);
    Matrix ancestorTransform = RNSharedElementStyle.getAbsoluteViewTransform(mAncestorView);
    if ((transform == null) || (ancestorTransform == null)) {
      if (!mLoggedStyleWaitForTransform) {
        mLoggedStyleWaitForTransform = true;
        log("fetchInitialStyle waiting: transform unavailable");
      }
      return false;
    }
    Rect frame = new Rect(left, top, left + width, top + height);

    // Create style
    RNSharedElementStyle style = new RNSharedElementStyle(mStyleConfig, mContext);
    RNSharedElementStyle.getLayoutOnScreen(view, style.layout);
    style.frame = frame;
    RNSharedElementStyle.getLayoutOnScreen(mAncestorView, style.ancestorLayout);
    style.ancestorTransform = ancestorTransform;

    // Get opacity
    style.opacity = view.getAlpha();

    // Get elevation
        /*if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            style.elevation = view.getElevation();
        }*/

    // Update initial style cache
    mStyleCache = style;
    mLoggedStyleWaitForView = false;
    mLoggedStyleWaitForSize = false;
    mLoggedStyleWaitForTransform = false;
    log(
      "fetchInitialStyle ready layout="
        + style.layout
        + " frame="
        + frame
    );

    //Log.d(LOG_TAG, "Style fetched: " + style);

    // Notify callbacks
    ArrayList<Callback> callbacks = mStyleCallbacks;
    mStyleCallbacks = null;
    for (Callback callback : callbacks) {
      callback.invoke(style, this);
    }
    return true;
  }

  void requestContent(Callback callback) {
    if (mContentCache != null) {
      log("requestContent cache-hit");
      callback.invoke(mContentCache, this);
      return;
    }
    log("requestContent cache-miss");
    if (mContentCallbacks == null) mContentCallbacks = new ArrayList<>();
    mContentCallbacks.add(callback);
    if (!fetchInitialContent()) {
      startRetryLoop();
    }
  }

  private boolean fetchInitialContent() {
    View view = getResolvedView();
    if (view == null) {
      if (!mLoggedContentWaitForView) {
        mLoggedContentWaitForView = true;
        log("fetchInitialContent waiting: resolvedView=null");
      }
      return false;
    }
    if (mContentCallbacks == null) return true;

    // Verify view size
    int width = view.getWidth();
    int height = view.getHeight();
    if (width == 0 && height == 0) {
      if (!mLoggedContentWaitForSize) {
        mLoggedContentWaitForSize = true;
        log("fetchInitialContent waiting: size=0x0");
      }
      return false;
    }

    // Get content size (e.g. the size of the underlying image of an image-view)
    RectF contentSize = RNSharedElementContent.getSize(view);
    if (contentSize == null) {
      // Image has not yet been fetched, listen for it
      if (!mLoggedContentWaitForImage) {
        mLoggedContentWaitForImage = true;
        log(
          "fetchInitialContent waiting: image content unavailable view="
            + view.getClass().getSimpleName()
        );
      }
      addDraweeControllerListener(view);
      return false;
    }

    // Create content
    RNSharedElementContent content = new RNSharedElementContent();
    content.view = view;
    content.size = contentSize;

    // Update cache
    mContentCache = content;
    mLoggedContentWaitForView = false;
    mLoggedContentWaitForSize = false;
    mLoggedContentWaitForImage = false;
    log(
      "fetchInitialContent ready view="
        + view.getClass().getSimpleName()
        + " size="
        + contentSize
    );

    // Log.d(LOG_TAG, "Content fetched: " + content);

    // Notify callbacks
    ArrayList<Callback> callbacks = mContentCallbacks;
    mContentCallbacks = null;
    for (Callback callback : callbacks) {
      callback.invoke(content, this);
    }
    return true;
  }

  private void startRetryLoop() {
    if (mRetryHandler != null) return;

    log("startRetryLoop");

    mRetryHandler = new Handler();
    // final long startTime = System.nanoTime();
    mRetryHandler.postDelayed(new RetryRunnable() {

      @Override
      public void run() {
        if (mRetryHandler == null) return;
        final RetryRunnable runnable = this;

        runnable.numRetries++;
        if (runnable.numRetries <= 5 || runnable.numRetries % 30 == 0) {
          log("retry #" + runnable.numRetries);
        }
        boolean isContentFetched = fetchInitialContent();
        boolean isStyleFetched = fetchInitialStyle();
        if (!isContentFetched || !isStyleFetched) {
          mRetryHandler.postDelayed(runnable, 8);
        } else {
          log("retry complete after #" + runnable.numRetries);
          mRetryHandler = null;
        }
      }
    }, 4);
  }

  private void stopRetryLoop() {
    if (mRetryHandler != null) {
      log("stopRetryLoop");
      mRetryHandler = null;
    }
  }

  private void addDraweeControllerListener(final View view) {
    if (mDraweeControllerListener != null) return;

    if (!(view instanceof GenericDraweeView)) return;
    log("addDraweeControllerListener");
    GenericDraweeView imageView = (GenericDraweeView) view;
    DraweeController controller = imageView.getController();
    if (!(controller instanceof PipelineDraweeController)) return;
    PipelineDraweeController pipelineController = (PipelineDraweeController) controller;

    mDraweeControllerListener = new BaseControllerListener<ImageInfo>() {
      @Override
      public void onSubmit(String id, Object callerContext) {
        //Log.d(LOG_TAG, "mDraweeControllerListener.onSubmit: " + id + ", callerContext: " + callerContext);
      }

      @Override
      public void onFinalImageSet(
              String id,
              @Nullable final ImageInfo imageInfo,
              @Nullable Animatable animatable) {
        log("drawee onFinalImageSet id=" + id + " imageInfo=" + imageInfo);
        removeDraweeControllerListener(view);
        fetchInitialContent();
      }

      @Override
      public void onFailure(String id, Throwable throwable) {
        log("drawee onFailure id=" + id + " throwable=" + throwable);
      }
    };

    pipelineController.addControllerListener(mDraweeControllerListener);
  }

  private void removeDraweeControllerListener(final View view) {
    if (mDraweeControllerListener == null) return;
    log("removeDraweeControllerListener");
    GenericDraweeView imageView = (GenericDraweeView) view;
    DraweeController controller = imageView.getController();
    if (!(controller instanceof PipelineDraweeController)) return;
    PipelineDraweeController pipelineController = (PipelineDraweeController) controller;
    pipelineController.removeControllerListener(mDraweeControllerListener);
    mDraweeControllerListener = null;
  }
}
