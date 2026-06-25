package com.ijzerenhein.sharedelement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.bridge.UIManager;
import com.facebook.react.fabric.FabricUIManager;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.bridge.UiThreadUtil;

@ReactModule(name = RNSharedElementModule.MODULE_NAME)
public class RNSharedElementModule extends ReactContextBaseJavaModule {
  public static final String MODULE_NAME = "RNSharedElementTransition";
  private final RNSharedElementNodeManager mNodeManager;

  public RNSharedElementModule(ReactApplicationContext reactContext) {
    super(reactContext);
    mNodeManager = new RNSharedElementNodeManager(reactContext);
  }

  @NonNull
  @Override
  public String getName() {
    return MODULE_NAME;
  }

  RNSharedElementNodeManager getNodeManager() {
    return mNodeManager;
  }

  @Nullable
  private View resolveViewForTag(int reactTag) {
    UIManager uiManager = UIManagerHelper.getUIManagerForReactTag(getReactApplicationContext(), reactTag);
    if (uiManager instanceof FabricUIManager) {
      FabricUIManager fabricUIManager = (FabricUIManager) uiManager;
      return fabricUIManager.resolveView(reactTag);
    }
    return null;
  }

  @Nullable
  private RNSharedElementNode acquireNodeFromTransitionItem(@Nullable ReadableMap itemMap) {
    if (itemMap == null) return null;
    if (!itemMap.hasKey("node") || itemMap.isNull("node")) return null;
    ReadableMap nodeMap = itemMap.getMap("node");
    ReadableMap ancestorMap = (itemMap.hasKey("ancestor") && !itemMap.isNull("ancestor"))
      ? itemMap.getMap("ancestor")
      : null;
    if (nodeMap == null || !nodeMap.hasKey("nodeHandle")) return null;
    int nodeHandle = nodeMap.getInt("nodeHandle");
    int ancestorHandle =
      (ancestorMap != null && ancestorMap.hasKey("nodeHandle"))
        ? ancestorMap.getInt("nodeHandle")
        : nodeHandle;
    boolean isParent = nodeMap.hasKey("isParent") && nodeMap.getBoolean("isParent");
    ReadableMap styleConfig =
      (nodeMap.hasKey("nodeStyle") && !nodeMap.isNull("nodeStyle"))
        ? nodeMap.getMap("nodeStyle")
        : null;
    View nodeView = resolveViewForTag(nodeHandle);
    if (nodeView == null) {
      return null;
    }
    View ancestorView = resolveViewForTag(ancestorHandle);
    if (ancestorView == null) {
      ancestorView = nodeView;
    }
    return mNodeManager.acquire(nodeHandle, nodeView, isParent, ancestorView, styleConfig);
  }

  private static boolean isRenderable(RNSharedElementTransitionItem item) {
    if (item.getNode() == null) return true;
    return (item.getStyle() != null) && (item.getContent() != null);
  }

  private static boolean hasAnyNode(RNSharedElementTransitionItem startItem, RNSharedElementTransitionItem endItem) {
    return (startItem.getNode() != null) || (endItem.getNode() != null);
  }

  private WritableMap buildWaitResult(
    String reason,
    long startedAtMs,
    RNSharedElementTransitionItem startItem,
    RNSharedElementTransitionItem endItem
  ) {
    WritableMap result = Arguments.createMap();
    boolean hasStartNode = startItem.getNode() != null;
    boolean hasEndNode = endItem.getNode() != null;
    boolean startStyleReady = startItem.getStyle() != null;
    boolean startContentReady = startItem.getContent() != null;
    boolean endStyleReady = endItem.getStyle() != null;
    boolean endContentReady = endItem.getContent() != null;
    boolean ready =
      hasAnyNode(startItem, endItem)
        && isRenderable(startItem)
        && isRenderable(endItem);
    result.putBoolean("ready", ready);
    result.putString("reason", reason);
    result.putInt("elapsedMs", (int) (SystemClock.uptimeMillis() - startedAtMs));
    result.putBoolean("hasStartNode", hasStartNode);
    result.putBoolean("hasEndNode", hasEndNode);
    result.putBoolean("startStyleReady", startStyleReady);
    result.putBoolean("startContentReady", startContentReady);
    result.putBoolean("endStyleReady", endStyleReady);
    result.putBoolean("endContentReady", endContentReady);
    return result;
  }

  @ReactMethod
  public void configure(final ReadableMap config, final Promise promise) {
    final ReactApplicationContext context = getReactApplicationContext();
    final UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
    if (uiManager != null) {
      uiManager.prependUIBlock(mNodeManager::setNativeViewHierarchyManager);
    }
    promise.resolve(true);
  }

  @ReactMethod
  public void waitForTransitionReady(
    @Nullable final ReadableMap startItemMap,
    @Nullable final ReadableMap endItemMap,
    final int timeoutMs,
    final Promise promise
  ) {
    UiThreadUtil.runOnUiThread(() -> {
      final long startedAtMs = SystemClock.uptimeMillis();
      final int effectiveTimeoutMs = timeoutMs > 0 ? timeoutMs : 140;
      final Handler handler = new Handler(Looper.getMainLooper());
      final RNSharedElementTransitionItem startItem = new RNSharedElementTransitionItem(mNodeManager, "probeStart");
      final RNSharedElementTransitionItem endItem = new RNSharedElementTransitionItem(mNodeManager, "probeEnd");
      final boolean[] finished = new boolean[] { false };
      final Runnable[] timeoutRunnableRef = new Runnable[] { null };

      startItem.setNode(acquireNodeFromTransitionItem(startItemMap));
      endItem.setNode(acquireNodeFromTransitionItem(endItemMap));

      Runnable finish = () -> {
        if (finished[0]) return;
        finished[0] = true;
        if (timeoutRunnableRef[0] != null) {
          handler.removeCallbacks(timeoutRunnableRef[0]);
        }
        WritableMap result = buildWaitResult("ready", startedAtMs, startItem, endItem);
        promise.resolve(result);
        startItem.setNode(null);
        endItem.setNode(null);
      };

      Runnable finishTimeout = () -> {
        if (finished[0]) return;
        finished[0] = true;
        WritableMap result = buildWaitResult("timeout", startedAtMs, startItem, endItem);
        promise.resolve(result);
        startItem.setNode(null);
        endItem.setNode(null);
      };

      Runnable maybeResolveReady = () -> {
        if (finished[0]) return;
        if (!hasAnyNode(startItem, endItem)) {
          finished[0] = true;
          WritableMap result = buildWaitResult("no-nodes", startedAtMs, startItem, endItem);
          promise.resolve(result);
          startItem.setNode(null);
          endItem.setNode(null);
          return;
        }
        if (isRenderable(startItem) && isRenderable(endItem)) {
          finish.run();
        }
      };

      timeoutRunnableRef[0] = finishTimeout;
      handler.postDelayed(finishTimeout, effectiveTimeoutMs);

      if (startItem.getNode() != null) {
        startItem.setNeedsStyle(false);
        startItem.getNode().requestStyle(args -> {
          startItem.setStyle((RNSharedElementStyle) args[0]);
          maybeResolveReady.run();
        });
        startItem.setNeedsContent(false);
        startItem.getNode().requestContent(args -> {
          startItem.setContent((RNSharedElementContent) args[0]);
          maybeResolveReady.run();
        });
      }

      if (endItem.getNode() != null) {
        endItem.setNeedsStyle(false);
        endItem.getNode().requestStyle(args -> {
          endItem.setStyle((RNSharedElementStyle) args[0]);
          maybeResolveReady.run();
        });
        endItem.setNeedsContent(false);
        endItem.getNode().requestContent(args -> {
          endItem.setContent((RNSharedElementContent) args[0]);
          maybeResolveReady.run();
        });
      }

      maybeResolveReady.run();
    });
  }
}
