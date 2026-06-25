package com.ijzerenhein.sharedelement;

import java.util.Map;

import android.view.View;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.UIManager;
import com.facebook.react.fabric.FabricUIManager;
import com.facebook.react.uimanager.UIManagerHelper;

public class RNSharedElementTransitionManager extends SimpleViewManager<RNSharedElementTransition> {
  public static final String REACT_CLASS = "RNSharedElementTransition";
  private static final String LOG_TAG = "RNSharedElementTransitionManager";
  private static final boolean DEBUG = false;

  private static void log(String message) {
    if (DEBUG) Log.d(LOG_TAG, message);
  }

  public RNSharedElementTransitionManager(ReactApplicationContext reactContext) {
    super();
  }

  @NonNull
  @Override
  public String getName() {
    return REACT_CLASS;
  }

  @Nullable
  @Override
  public Map<String, Object> getExportedCustomBubblingEventTypeConstants() {
    return MapBuilder.<String, Object>builder()
            .put(
                    "onMeasureNode",
                    MapBuilder.of(
                            "phasedRegistrationNames",
                            MapBuilder.of("bubbled", "onMeasureNode")))
            .build();
  }

  @NonNull
  @Override
  public RNSharedElementTransition createViewInstance(ThemedReactContext reactContext) {
    RNSharedElementModule module = reactContext.getNativeModule(RNSharedElementModule.class);
    return new RNSharedElementTransition(reactContext, module.getNodeManager());
  }

  @Override
  public void onDropViewInstance(@NonNull RNSharedElementTransition view) {
    super.onDropViewInstance(view);
    view.releaseData();
  }

  @ReactProp(name = "nodePosition")
  public void setNodePosition(final RNSharedElementTransition view, final float nodePosition) {
    view.setNodePosition(nodePosition);
  }

  @ReactProp(name = "animation")
  public void setAnimation(final RNSharedElementTransition view, final int animation) {
    view.setAnimation(RNSharedElementAnimation.values()[animation]);
  }

  @ReactProp(name = "resize")
  public void setResize(final RNSharedElementTransition view, final int resize) {
    view.setResize(RNSharedElementResize.values()[resize]);
  }

  @ReactProp(name = "align")
  public void setAlign(final RNSharedElementTransition view, final int align) {
    view.setAlign(RNSharedElementAlign.values()[align]);
  }

  // Native-timer props for Fabric interop path.
  @ReactProp(name = "nativeDriver")
  public void setNativeDriver(final RNSharedElementTransition view, final boolean nativeDriver) {
    view.setNativeDriver(nativeDriver);
  }

  @ReactProp(name = "nativeDuration")
  public void setNativeDuration(final RNSharedElementTransition view, final float nativeDuration) {
    view.setNativeDuration(nativeDuration);
  }

  @ReactProp(name = "nativeDelay")
  public void setNativeDelay(final RNSharedElementTransition view, final float nativeDelay) {
    view.setNativeDelay(nativeDelay);
  }

  @ReactProp(name = "nativeFrom")
  public void setNativeFrom(final RNSharedElementTransition view, final float nativeFrom) {
    view.setNativeFrom(nativeFrom);
  }

  @ReactProp(name = "nativeTo")
  public void setNativeTo(final RNSharedElementTransition view, final float nativeTo) {
    view.setNativeTo(nativeTo);
  }

  @ReactProp(name = "nativeGroup")
  public void setNativeGroup(final RNSharedElementTransition view, final String nativeGroup) {
    // iOS uses this to synchronize multiple shared elements. Android keeps
    // its existing per-view native driver for now, but accepts the prop.
  }

  @ReactProp(name = "nativeGroupSize")
  public void setNativeGroupSize(final RNSharedElementTransition view, final int nativeGroupSize) {
    // See setNativeGroup.
  }

  private void setViewItem(final RNSharedElementTransition view, RNSharedElementTransition.Item item, final ReadableMap map) {
    if (map == null) {
      log("setViewItem " + item + " map=null -> clear node");
      view.setItemNode(item, null);
      return;
    }
    if (!map.hasKey("node") || map.isNull("node")) {
      log("setViewItem " + item + " missing node -> clear node");
      view.setItemNode(item, null);
      return;
    }
    final ReadableMap nodeMap = map.getMap("node");
    final ReadableMap ancestorMap = map.hasKey("ancestor") ? map.getMap("ancestor") : null;
    if (nodeMap == null) {
      log("setViewItem " + item + " nodeMap=null -> clear node");
      view.setItemNode(item, null);
      return;
    }
    if (!nodeMap.hasKey("nodeHandle")) {
      log("setViewItem " + item + " missing nodeHandle -> clear node");
      view.setItemNode(item, null);
      return;
    }
    int nodeHandle = nodeMap.getInt("nodeHandle");
    int ancestorHandle = ancestorMap != null && ancestorMap.hasKey("nodeHandle")
      ? ancestorMap.getInt("nodeHandle")
      : nodeHandle;
    boolean isParent = nodeMap.hasKey("isParent") && nodeMap.getBoolean("isParent");
    ReadableMap styleConfig = nodeMap.getMap("nodeStyle");
    try {
      View nodeView = null;
      View ancestorView = null;
      ThemedReactContext themedContext = (ThemedReactContext) view.getContext();
      UIManager uiManager = UIManagerHelper.getUIManagerForReactTag(themedContext, nodeHandle);
      if (uiManager instanceof FabricUIManager) {
        FabricUIManager fabricUIManager = (FabricUIManager) uiManager;
        nodeView = fabricUIManager.resolveView(nodeHandle);
        ancestorView = fabricUIManager.resolveView(ancestorHandle);
      } else {
        log("setViewItem " + item + " Fabric UIManager unavailable for node=" + nodeHandle + " ancestor=" + ancestorHandle);
      }
      if (nodeView == null) {
        log("setViewItem " + item + " resolve nodeView failed handle=" + nodeHandle);
        view.setItemNode(item, null);
        return;
      }
      if (ancestorView == null) {
        log("setViewItem " + item + " resolve ancestor failed handle=" + ancestorHandle + " (fallback to node)");
        ancestorView = nodeView;
      }
      log("setViewItem " + item + " node=" + nodeHandle + " ancestor=" + ancestorHandle + " isParent=" + isParent);
      RNSharedElementNode node = view.getNodeManager().acquire(nodeHandle, nodeView, isParent, ancestorView, styleConfig);
      view.setItemNode(item, node);
    } catch (Exception e) {
      log("setViewItem " + item + " error=" + e.getMessage());
      view.setItemNode(item, null);
    }
  }

  @ReactProp(name = "startNode")
  public void setStartNode(final RNSharedElementTransition view, final ReadableMap startNode) {
    setViewItem(view, RNSharedElementTransition.Item.START, startNode);
  }

  @ReactProp(name = "endNode")
  public void setEndNode(final RNSharedElementTransition view, final ReadableMap endNode) {
    setViewItem(view, RNSharedElementTransition.Item.END, endNode);
  }
}
