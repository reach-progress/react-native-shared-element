package com.ijzerenhein.sharedelement;

import android.view.View;
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
}
