package com.ijzerenhein.sharedelement;

import android.view.View;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.facebook.drawee.drawable.ScalingUtils.ScaleType;
import com.facebook.drawee.view.GenericDraweeView;
import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.interfaces.DraweeController;

class RNSharedElementContent {
  View view;
  RectF size;

  private static RectF getImageViewLayout(RectF layout, ImageView imageView) {
    Drawable drawable = imageView.getDrawable();
    int viewWidth = imageView.getWidth();
    int viewHeight = imageView.getHeight();
    if (drawable == null || viewWidth <= 0 || viewHeight <= 0) {
      return null;
    }

    int drawableWidth = drawable.getIntrinsicWidth();
    int drawableHeight = drawable.getIntrinsicHeight();
    if (drawableWidth <= 0 || drawableHeight <= 0) {
      return null;
    }

    RectF mappedDrawable = new RectF(0, 0, drawableWidth, drawableHeight);
    Matrix imageMatrix = imageView.getImageMatrix();
    imageMatrix.mapRect(mappedDrawable);

    float scaleX = layout.width() / (float) viewWidth;
    float scaleY = layout.height() / (float) viewHeight;
    return new RectF(
            layout.left + (mappedDrawable.left * scaleX),
            layout.top + (mappedDrawable.top * scaleY),
            layout.left + (mappedDrawable.right * scaleX),
            layout.top + (mappedDrawable.bottom * scaleY)
    );
  }

  static RectF getSize(View view) {
    if (view instanceof GenericDraweeView) {
      GenericDraweeView imageView = (GenericDraweeView) view;
      DraweeController controller = imageView.getController();
      GenericDraweeHierarchy hierarchy = imageView.getHierarchy();
      if (controller == null || controller.toString().contains("fetchedImage=0,")) {
        return null;
      }
      RectF imageBounds = new RectF();
      hierarchy.getActualImageBounds(imageBounds);
      return imageBounds;
    } else if (view instanceof ImageView) {
      ImageView imageView = (ImageView) view;
      Drawable drawable = imageView.getDrawable();
      if (drawable == null) return null;
      int width = drawable.getIntrinsicWidth();
      int height = drawable.getIntrinsicHeight();
      if ((width <= 0) || (height <= 0)) {
        return null;
      }
      return new RectF(0, 0, width, height);
    }
    return new RectF(0, 0, view.getWidth(), view.getHeight());
  }

  static RectF getLayout(RectF layout, RectF contentSize, ScaleType scaleType, boolean reverse) {
    if (contentSize == null || contentSize.width() <= 0 || contentSize.height() <= 0) {
      return new RectF(layout);
    }

    float width = layout.width();
    float height = layout.height();
    float contentAspectRatio = contentSize.width() / contentSize.height();
    boolean layoutIsNarrowerThanContent = (width / height) < contentAspectRatio;
    boolean shouldConstrainByWidth = reverse
            ? !layoutIsNarrowerThanContent
            : layoutIsNarrowerThanContent;

    if (scaleType == ScaleType.FIT_CENTER) {
      if (shouldConstrainByWidth) {
        height = width / contentAspectRatio;
      } else {
        width = height * contentAspectRatio;
      }
    } else if (scaleType == ScaleType.CENTER_CROP) {
      if (shouldConstrainByWidth) {
        width = height * contentAspectRatio;
      } else {
        height = width / contentAspectRatio;
      }
    } else if (scaleType == ScaleType.CENTER_INSIDE) {
      width = contentSize.width();
      height = contentSize.height();
    }

    float horizontalInset = (layout.width() - width) / 2f;
    float verticalInset = (layout.height() - height) / 2f;
    return new RectF(
            layout.left + horizontalInset,
            layout.top + verticalInset,
            layout.right - horizontalInset,
            layout.bottom - verticalInset
    );
  }

  static RectF getLayout(RectF layout, RNSharedElementContent content, ScaleType scaleType, boolean reverse) {
    // Expo Image uses a MATRIX scale type so it can apply contentPosition.
    // Preserve that exact endpoint crop instead of rebuilding a centered one.
    if (!reverse && content != null && content.view instanceof ImageView) {
      ImageView imageView = (ImageView) content.view;
      if (imageView.getScaleType() != ImageView.ScaleType.MATRIX) {
        return getLayout(layout, content.size, scaleType, false);
      }
      RectF imageViewLayout = getImageViewLayout(layout, imageView);
      if (imageViewLayout != null) {
        return imageViewLayout;
      }
    }
    return getLayout(layout, content != null ? content.size : null, scaleType, reverse);
  }
}
