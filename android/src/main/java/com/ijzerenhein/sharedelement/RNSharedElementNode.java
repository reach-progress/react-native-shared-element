package com.ijzerenhein.sharedelement;

import java.util.ArrayList;

import javax.annotation.Nullable;

import android.os.Handler;
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
  }

  int getReactTag() {
    return mReactTag;
  }

  int addRef() {
    return ++mRefCount;
  }

  int releaseRef() {
    if (--mRefCount == 0) {
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
    }
  }

  void releaseHideRef() {
    mHideRefCount--;
    if (mHideRefCount == 0) {
      mView.setAlpha(mHideAlpha);
    }
  }

  private static View resolveView(View view, RNSharedElementStyle style) {
    if (view == null) return null;

    // If the view is a ViewGroup and it contains exactly one
    // imageview with the same size, then use that imageview
    if (view instanceof ViewGroup) {
      ViewGroup viewGroup = (ViewGroup) view;
      if (isExpoImageViewWrapper(viewGroup)) {
        ImageView imageView = getVisibleImageViewDescendant(viewGroup, style);
        if (imageView != null) return imageView;
      } else if (viewGroup.getChildCount() == 1) {
        View childView = viewGroup.getChildAt(0);
        if (childView instanceof ImageView) {
          if (imageViewFillsViewGroup((ImageView) childView, viewGroup, style)) {
            return childView;
          }
        }
      }
    }
    return view;
  }

  private static boolean isExpoImageViewWrapper(View view) {
    return view.getClass().getName().equals("expo.modules.image.ExpoImageViewWrapper");
  }

  private static ImageView getVisibleImageViewDescendant(ViewGroup viewGroup, RNSharedElementStyle style) {
    ImageView fallbackImageView = null;
    for (int i = 0; i < viewGroup.getChildCount(); i++) {
      View childView = viewGroup.getChildAt(i);
      if (childView instanceof ImageView) {
        ImageView imageView = (ImageView) childView;
        if (!imageViewFillsViewGroup(imageView, viewGroup, style)) continue;

        if (fallbackImageView == null) {
          fallbackImageView = imageView;
        }

        if ((imageView.getVisibility() == View.VISIBLE) && (imageView.getDrawable() != null)) {
          return imageView;
        }
      } else if (childView instanceof ViewGroup) {
        ImageView imageView = getVisibleImageViewDescendant((ViewGroup) childView, style);
        if (imageView == null) continue;

        if (fallbackImageView == null) {
          fallbackImageView = imageView;
        }

        if ((imageView.getVisibility() == View.VISIBLE) && (imageView.getDrawable() != null)) {
          return imageView;
        }
      }
    }
    return fallbackImageView;
  }

  private static boolean imageViewFillsViewGroup(ImageView imageView, ViewGroup viewGroup, RNSharedElementStyle style) {
    int left = imageView.getLeft();
    int top = imageView.getTop();
    int width = imageView.getWidth();
    int height = imageView.getHeight();
    int expectedLeft = Math.round(style.borderWidth);
    int expectedTop = Math.round(style.borderWidth);
    int expectedWidth = Math.round((float) viewGroup.getWidth() - (style.borderWidth * 2));
    int expectedHeight = Math.round((float) viewGroup.getHeight() - (style.borderWidth * 2));
    return ((left >= expectedLeft - 1) && (left <= expectedLeft + 1)) &&
            ((top >= expectedTop - 1) && (top <= expectedTop + 1)) &&
            ((width >= expectedWidth - 1) && (width <= expectedWidth + 1)) &&
            ((height >= expectedHeight - 1) && (height <= expectedHeight + 1));
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
        return null;
      }
    }
    mResolvedView = RNSharedElementNode.resolveView(view, mResolveStyle);
    return mResolvedView;
  }

  void requestStyle(Callback callback) {
    if (mStyleCache != null) {
      callback.invoke(mStyleCache, this);
      return;
    }
    if (mStyleCallbacks == null) mStyleCallbacks = new ArrayList<>();
    mStyleCallbacks.add(callback);
    if (!fetchInitialStyle()) {
      startRetryLoop();
    }
  }

  private boolean fetchInitialStyle() {
    View view = getResolvedView();
    if (view == null) {
      return false;
    }
    if (mStyleCallbacks == null) return true;

    // Get relative size and position within parent
    int left = view.getLeft();
    int top = view.getTop();
    int width = view.getWidth();
    int height = view.getHeight();
    if (width == 0 && height == 0) {
      return false;
    }
    Matrix transform = RNSharedElementStyle.getAbsoluteViewTransform(view);
    Matrix ancestorTransform = RNSharedElementStyle.getAbsoluteViewTransform(mAncestorView);
    if ((transform == null) || (ancestorTransform == null)) {
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
      callback.invoke(mContentCache, this);
      return;
    }
    if (mContentCallbacks == null) mContentCallbacks = new ArrayList<>();
    mContentCallbacks.add(callback);
    if (!fetchInitialContent()) {
      startRetryLoop();
    }
  }

  private boolean fetchInitialContent() {
    View view = getResolvedView();
    if (view == null) {
      return false;
    }
    if (mContentCallbacks == null) return true;

    // Verify view size
    int width = view.getWidth();
    int height = view.getHeight();
    if (width == 0 && height == 0) {
      return false;
    }

    // Get content size (e.g. the size of the underlying image of an image-view)
    RectF contentSize = RNSharedElementContent.getSize(view);
    if (contentSize == null) {
      // Image has not yet been fetched, listen for it
      addDraweeControllerListener(view);
      return false;
    }

    // Create content
    RNSharedElementContent content = new RNSharedElementContent();
    content.view = view;
    content.size = contentSize;

    // Update cache
    mContentCache = content;

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

    mRetryHandler = new Handler();
    // final long startTime = System.nanoTime();
    mRetryHandler.postDelayed(new RetryRunnable() {

      @Override
      public void run() {
        if (mRetryHandler == null) return;
        final RetryRunnable runnable = this;

        runnable.numRetries++;
        boolean isContentFetched = fetchInitialContent();
        boolean isStyleFetched = fetchInitialStyle();
        if (!isContentFetched || !isStyleFetched) {
          mRetryHandler.postDelayed(runnable, 8);
        } else {
          mRetryHandler = null;
        }
      }
    }, 4);
  }

  private void stopRetryLoop() {
    if (mRetryHandler != null) {
      mRetryHandler = null;
    }
  }

  private void addDraweeControllerListener(final View view) {
    if (mDraweeControllerListener != null) return;

    if (!(view instanceof GenericDraweeView)) return;
    GenericDraweeView imageView = (GenericDraweeView) view;
    DraweeController controller = imageView.getController();
    if (!(controller instanceof PipelineDraweeController)) return;
    PipelineDraweeController pipelineController = (PipelineDraweeController) controller;

    mDraweeControllerListener = new BaseControllerListener<ImageInfo>() {
      @Override
      public void onSubmit(String id, Object callerContext) {
      }

      @Override
      public void onFinalImageSet(
              String id,
              @Nullable final ImageInfo imageInfo,
              @Nullable Animatable animatable) {
        removeDraweeControllerListener(view);
        fetchInitialContent();
      }

      @Override
      public void onFailure(String id, Throwable throwable) {
      }
    };

    pipelineController.addControllerListener(mDraweeControllerListener);
  }

  private void removeDraweeControllerListener(final View view) {
    if (mDraweeControllerListener == null) return;
    GenericDraweeView imageView = (GenericDraweeView) view;
    DraweeController controller = imageView.getController();
    if (!(controller instanceof PipelineDraweeController)) return;
    PipelineDraweeController pipelineController = (PipelineDraweeController) controller;
    pipelineController.removeControllerListener(mDraweeControllerListener);
    mDraweeControllerListener = null;
  }
}
