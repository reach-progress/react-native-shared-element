# Architecture

## Overview
react-native-shared-element provides native shared-element transition *primitives* for React Native. It does **not** implement navigation; it exposes a [`SharedElement`](src/SharedElement.tsx) wrapper to capture native view nodes and a [`SharedElementTransition`](src/SharedElementTransition.tsx) view that renders the transition natively based on those nodes.

At runtime, a router/navigation layer renders an overlay view (usually above both screens) that hosts the [`SharedElementTransition`](src/SharedElementTransition.tsx). Native code measures the start/end elements, captures their visual content, hides the originals, and renders a native overlay that interpolates size, position, style, and content until the transition completes.

Key files (JS):
- [`src/SharedElement.tsx`](src/SharedElement.tsx) (node capture)
- [`src/SharedElementTransition.tsx`](src/SharedElementTransition.tsx) (JS wrapper + prop mapping)
- [`src/RNSharedElementTransitionView.tsx`](src/RNSharedElementTransitionView.tsx) (native component bridge)

Key files (Android):
- [`android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransition.java`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransition.java)
- [`android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java)
- [`android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementNode.java`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementNode.java)
- [`android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementDrawable.java`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementDrawable.java)

Key files (iOS):
- [`ios/RNSharedElementTransition.m`](ios/RNSharedElementTransition.m)
- [`ios/RNSharedElementTransitionManager.m`](ios/RNSharedElementTransitionManager.m)
- [`ios/RNSharedElementNode.m`](ios/RNSharedElementNode.m)
- [`ios/RNSharedElementContent.m`](ios/RNSharedElementContent.m)

## Concept map (React Native vs library vs native platform)
This table maps names to their “owner” layer and links to either local source, official docs, or upstream code.

| Name | Layer | Link | Notes |
| --- | --- | --- | --- |
| [`SharedElement`](src/SharedElement.tsx) | Library | [`src/SharedElement.tsx`](src/SharedElement.tsx) | JS wrapper that captures a native node handle. |
| [`SharedElementTransition`](src/SharedElementTransition.tsx) | Library | [`src/SharedElementTransition.tsx`](src/SharedElementTransition.tsx) | JS wrapper that configures the native transition view. |
| [`RNSharedElementTransitionView`](src/RNSharedElementTransitionView.tsx) | Library | [`src/RNSharedElementTransitionView.tsx`](src/RNSharedElementTransitionView.tsx) | `requireNativeComponent` wrapper + native `configure` call. |
| [`SharedElementNode`](src/types.tsx) | Library | [`src/types.tsx`](src/types.tsx) | Node handle shape passed to native. |
| [`requireNativeComponent`](https://reactnative.dev/docs/0.81/legacy/native-components-android) | React Native | [`native-components-android`](https://reactnative.dev/docs/0.81/legacy/native-components-android) | Core API for binding native views to JS. |
| [`findNodeHandle`](https://archive.reactnative.dev/docs/direct-manipulation) | React Native | [`direct-manipulation`](https://archive.reactnative.dev/docs/direct-manipulation) | Returns a native view handle for a component. |
| [`NativeModules`](https://reactnative.dev/docs/0.81/legacy/native-modules-android) | React Native | [`native-modules-android`](https://reactnative.dev/docs/0.81/legacy/native-modules-android) | JS entry point for legacy native modules. |
| [`View`](https://reactnative.dev/docs/view) | React Native | [`view`](https://reactnative.dev/docs/view) | Core RN component; maps to platform views. |
| [`StyleSheet`](https://reactnative.dev/docs/stylesheet) | React Native | [`stylesheet`](https://reactnative.dev/docs/stylesheet) | Used to extract styles for Android. |
| [`Platform`](https://reactnative.dev/docs/platform) | React Native | [`platform`](https://reactnative.dev/docs/platform) | Platform branching in JS. |
| [`ReactPackage`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/ReactPackage.java) | React Native (Android) | [`ReactPackage.java`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/ReactPackage.java) | Android registration entry point. |
| [`SimpleViewManager`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/SimpleViewManager.java) | React Native (Android) | [`SimpleViewManager.java`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/SimpleViewManager.java) | Base class for view managers. |
| [`UIManagerModule`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/UIManagerModule.java) | React Native (Android) | [`UIManagerModule.java`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/UIManagerModule.java) | Provides access to native view hierarchy manager. |
| [`NativeViewHierarchyManager`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/NativeViewHierarchyManager.java) | React Native (Android) | [`NativeViewHierarchyManager.java`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/NativeViewHierarchyManager.java) | Resolves native views by react tag. |
| [`RCTEventEmitter`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/events/RCTEventEmitter.java) | React Native (Android) | [`RCTEventEmitter.java`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/events/RCTEventEmitter.java) | Sends native events to JS. |
| [`RCTBridge`](https://github.com/facebook/react-native/blob/main/React/Base/RCTBridge.h) | React Native (iOS) | [`RCTBridge.h`](https://github.com/facebook/react-native/blob/main/React/Base/RCTBridge.h) | Core iOS bridge. |
| [`RCTUIManager`](https://github.com/facebook/react-native/blob/main/React/Modules/RCTUIManager.h) | React Native (iOS) | [`RCTUIManager.h`](https://github.com/facebook/react-native/blob/main/React/Modules/RCTUIManager.h) | iOS view registry / manager. |
| [`RCTView`](https://github.com/facebook/react-native/blob/main/React/Views/RCTView.h) | React Native (iOS) | [`RCTView.h`](https://github.com/facebook/react-native/blob/main/React/Views/RCTView.h) | Base RN view class on iOS. |
| [`ViewGroup`](https://developer.android.com/reference/android/view/ViewGroup.html) | Android | [`ViewGroup`](https://developer.android.com/reference/android/view/ViewGroup.html) | Native view container. |
| [`View`](https://developer.android.com/reference/android/view/View) | Android | [`View`](https://developer.android.com/reference/android/view/View) | Base Android view. |
| [`Drawable`](https://developer.android.com/reference/android/graphics/drawable/Drawable) | Android | [`Drawable`](https://developer.android.com/reference/android/graphics/drawable/Drawable) | Used for drawing in `RNSharedElementDrawable`. |
| [`Canvas`](https://developer.android.com/reference/android/graphics/Canvas) | Android | [`Canvas`](https://developer.android.com/reference/android/graphics/Canvas) | Rendering surface for custom drawing. |
| [`ImageView`](https://developer.android.com/reference/android/widget/ImageView) | Android | [`ImageView`](https://developer.android.com/reference/android/widget/ImageView) | Used for image content. |
| [`Matrix`](https://developer.android.com/reference/android/graphics/Matrix) | Android | [`Matrix`](https://developer.android.com/reference/android/graphics/Matrix) | Used to transform images/layout. |
| [`Rect`](https://developer.android.com/reference/android/graphics/Rect) | Android | [`Rect`](https://developer.android.com/reference/android/graphics/Rect) | Integer rectangle layout data. |
| [`RectF`](https://developer.android.com/reference/android/graphics/RectF) | Android | [`RectF`](https://developer.android.com/reference/android/graphics/RectF) | Float rectangle layout data. |
| [`Handler`](https://developer.android.com/reference/android/os/Handler) | Android | [`Handler`](https://developer.android.com/reference/android/os/Handler) | Retry loop for layout/content readiness. |
| [`GenericDraweeView`](https://frescolib.org/docs/drawee-views.html) | Android (Fresco) | [`Fresco Drawee Views`](https://frescolib.org/docs/drawee-views.html) | Used for image-loading readiness. |
| [`UIView`](https://developer.apple.com/documentation/uikit/uiview) | iOS | [`UIView`](https://developer.apple.com/documentation/uikit/uiview) | Base iOS view type. |
| [`UIImageView`](https://developer.apple.com/documentation/uikit/uiimageview) | iOS | [`UIImageView`](https://developer.apple.com/documentation/uikit/uiimageview) | Used for image content. |
| [`CALayer`](https://developer.apple.com/documentation/quartzcore/calayer) | iOS | [`CALayer`](https://developer.apple.com/documentation/quartzcore/calayer) | Styling, shadows, and masking. |
| [`CADisplayLink`](https://developer.apple.com/documentation/quartzcore/cadisplaylink) | iOS | [`CADisplayLink`](https://developer.apple.com/documentation/quartzcore/cadisplaylink) | Retry loop for layout/content readiness. |
| [`snapshotViewAfterScreenUpdates:`](https://developer.apple.com/documentation/uikit/uiview/1622660-snapshotviewafterscreenupd) | iOS | [`snapshotViewAfterScreenUpdates:`](https://developer.apple.com/documentation/uikit/uiview/1622660-snapshotviewafterscreenupd) | Snapshot-based content cloning. |
| [`CATransform3D`](https://developer.apple.com/documentation/quartzcore/catransform3d) | iOS | [`CATransform3D`](https://developer.apple.com/documentation/quartzcore/catransform3d) | Transform compensation on iOS. |
| [`UIEdgeInsets`](https://developer.apple.com/documentation/uikit/uiedgeinsets) | iOS | [`UIEdgeInsets`](https://developer.apple.com/documentation/uikit/uiedgeinsets) | Clip inset calculations. |

## Features
- **Pure native transitions**: measurement, cloning, hiding, and interpolation are executed on the native side; JS only provides node handles.
- **Multiple animation modes**: move, cross-fade, fade-in, fade-out.
- **Resize/align strategies**: stretch, clip, none, plus alignment rules to keep content anchored during size changes.
- **Visual fidelity**: captures view content (images or snapshots), interpolates border radius, background/border colors, and opacity.
- **Debug info**: optional [`onMeasure`](src/SharedElementTransition.tsx) and debug overlay support via JS.
- **Image resolver support (iOS)**: resolves underlying image views for common libraries.

## JS-to-Native integration
1. **Node capture** ([`SharedElement`](src/SharedElement.tsx))
   - [`SharedElement`](src/SharedElement.tsx) wraps a child and stores its native node handle using [`findNodeHandle`](https://archive.reactnative.dev/docs/direct-manipulation).
   - It emits a [`SharedElementNode`](src/types.tsx) object `{ nodeHandle, isParent, parentInstance }` via [`onNode`](src/SharedElement.tsx).

2. **Transition view** ([`SharedElementTransition`](src/SharedElementTransition.tsx))
   - Accepts [`start`](src/SharedElementTransition.tsx)/[`end`](src/SharedElementTransition.tsx) node pairs and relies on native-driven progress.
   - Converts JS props into native enum values and packages node metadata.
   - On Android, it also extracts a subset of style (background/border colors, `resizeMode`) from the React child to assist native rendering.

3. **Native component bridge** ([`RNSharedElementTransitionView`](src/RNSharedElementTransitionView.tsx))
   - Uses [`requireNativeComponent`](https://reactnative.dev/docs/0.81/legacy/native-components-android)([`"RNSharedElementTransition"`](src/RNSharedElementTransitionView.tsx)) to render the native view manager.
   - Calls a native [`configure`](src/RNSharedElementTransitionView.tsx) method at startup:
     - **Android**: attaches the [`NativeViewHierarchyManager`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/NativeViewHierarchyManager.java) so native code can resolve view handles efficiently.
     - **iOS**: sets image resolver chains to locate underlying [`UIImageView`](https://developer.apple.com/documentation/uikit/uiimageview) instances for specific RN image libraries.

4. **Events back to JS**
   - Native fires an [`onMeasureNode`](src/SharedElementTransition.tsx) event with layout, visible layout, content layout, and content type; JS uses it for debugging or external measurements.

## Android implementation
### High-level flow
- **View manager**: [`RNSharedElementTransitionManager`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java) exposes props ([`animation`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java), [`resize`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java), [`align`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java), [`startNode`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java), [`endNode`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java)) and creates [`RNSharedElementTransition`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransition.java).
- **Node manager**: [`RNSharedElementNodeManager`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementNodeManager.java) caches [`RNSharedElementNode`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementNode.java) objects by react tag and handles ref counting.
- **Transition view**: [`RNSharedElementTransition`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransition.java) is a [`ViewGroup`](https://developer.android.com/reference/android/view/ViewGroup.html) hosting two internal [`RNSharedElementView`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementView.java) instances (start/end) for rendering.

### Node resolution & measurement
- [`RNSharedElementTransitionManager`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionManager.java) resolves native [`View`](https://developer.android.com/reference/android/view/View) objects for both node and ancestor using [`NativeViewHierarchyManager`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/uimanager/NativeViewHierarchyManager.java) (via `resolveView`).
- [`RNSharedElementNode`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementNode.java):
  - Resolves the *actual* view to render (e.g., unwraps a single child [`ImageView`](https://developer.android.com/reference/android/widget/ImageView) when it matches bounds).
  - Fetches **style**: layout, transform, border/background, opacity, scale type, etc.
  - Fetches **content**: image intrinsic size or fallback to view size.
  - Uses a retry loop ([`Handler`](https://developer.android.com/reference/android/os/Handler)) if layout or image data is not yet ready, and listens for Fresco image loading ([`GenericDraweeView`](https://frescolib.org/docs/drawee-views.html)) when necessary.

### Rendering & interpolation
- [`RNSharedElementTransition.updateLayout()`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransition.java):
  - Computes normalized layouts, compensates for ancestor transforms, and resolves clipped visible areas (e.g., inside scroll views).
  - Interpolates layout, style, and clip insets based on native-timer progress.
  - Chooses opacity strategy based on animation type (move vs. fade variants).
  - Updates [`RNSharedElementView`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementView.java) instances to draw content.
- [`RNSharedElementView`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementView.java):
  - Hosts a [`RNSharedElementDrawable`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementDrawable.java) as background.
  - Uses GPU scaling for certain view types to avoid expensive re-rasterization.
- [`RNSharedElementDrawable`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementDrawable.java):
  - Renders based on view type:
    - [`ReactImageView`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/views/image/ReactImageView.java) / [`ImageView`](https://developer.android.com/reference/android/widget/ImageView) uses the underlying drawable with proper scale type and rounded corners.
    - [`ReactViewGroup`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/views/view/ReactViewGroup.java) with no children renders background/border.
    - `GENERIC` draws the real view into the [`Canvas`](https://developer.android.com/reference/android/graphics/Canvas).

### Hiding originals
- [`RNSharedElementTransitionItem`](android/src/main/java/com/ijzerenhein/sharedelement/RNSharedElementTransitionItem.java) manages hiding logic. When a node is “active” in the transition, the original view’s alpha is set to 0 (tracked via hide ref count). This is reverted when the transition view releases the node.

## iOS implementation
### High-level flow
- **View manager**: [`RNSharedElementTransitionManager`](ios/RNSharedElementTransitionManager.m) exposes props and creates [`RNSharedElementTransition`](ios/RNSharedElementTransition.m).
- **Node manager**: [`RNSharedElementNodeManager`](ios/RNSharedElementNodeManager.m) caches [`RNSharedElementNode`](ios/RNSharedElementNode.m) instances by react tag with ref counting.
- **Transition view**: [`RNSharedElementTransition`](ios/RNSharedElementTransition.m) is a [`UIView`](https://developer.apple.com/documentation/uikit/uiview) that builds a small internal view tree:
  - [`_outerStyleView`](ios/RNSharedElementTransition.m) applies border, background, and shadow.
  - [`_innerClipView`](ios/RNSharedElementTransition.m) hosts image/snapshot content and clips it.
  - [`_primaryImageView`](ios/RNSharedElementTransition.m) / [`_secondaryImageView`](ios/RNSharedElementTransition.m) render start/end images during fade variants.

### Node resolution & measurement
- [`RNSharedElementNode`](ios/RNSharedElementNode.m):
  - Resolves the source view (including detecting underlying image views and custom image resolvers).
  - Observes `bounds` and `frame` changes to update style.
  - Observes `image` changes for image views to update content.
  - Uses a [`CADisplayLink`](https://developer.apple.com/documentation/quartzcore/cadisplaylink) retry loop to poll until layout/content are ready.
  - Builds [`RNSharedElementContent`](ios/RNSharedElementContent.m) as either:
    - Raw image content for image views.
    - Snapshot views for general content ([`snapshotViewAfterScreenUpdates:`](https://developer.apple.com/documentation/uikit/uiview/1622660-snapshotviewafterscreenupd)).

### Rendering & interpolation
- [`RNSharedElementTransition.updateStyle()`](ios/RNSharedElementTransition.m):
  - Computes normalized layouts (compensating for transforms applied by navigators).
  - Computes visible layout intersections to respect clipping/masks.
  - Interpolates style and layout based on native-timer progress.
  - Applies style to [`_outerStyleView`](ios/RNSharedElementTransition.m) (border, background, shadow, corner radii).
  - Updates [`_innerClipView`](ios/RNSharedElementTransition.m) and content frames, including resize/align logic.
  - For fade animations, cross-fades two content views with per-view opacity.

### Hiding originals
- The [`hidden`](ios/RNSharedElementNode.m) property is toggled on the resolved native view via a hide ref counter in [`RNSharedElementNode`](ios/RNSharedElementNode.m) and [`RNSharedElementTransitionItem`](ios/RNSharedElementTransitionItem.m).

## Lifecycle summary
1. **JS captures nodes** via [`SharedElement`](src/SharedElement.tsx) and passes them to [`SharedElementTransition`](src/SharedElementTransition.tsx).
2. **Native transition view** resolves native views, requests style and content, and hides originals.
3. **Overlay rendering** draws a copy (image or snapshot) and interpolates layout/style using native-timer progress.
4. **Events** can be emitted to JS for measurement/debug.
5. **Unmount** releases node references and restores original view visibility.

## Platform differences
- **Android** draws content via a custom [`Drawable`](https://developer.android.com/reference/android/graphics/drawable/Drawable) (including [`ReactImageView`](https://github.com/facebook/react-native/blob/main/ReactAndroid/src/main/java/com/facebook/react/views/image/ReactImageView.java) and view-group backgrounds). Original views are hidden using `alpha`.
- **iOS** uses snapshot views or raw image views and applies shadow/border via container layers ([`CALayer`](https://developer.apple.com/documentation/quartzcore/calayer)). Original views are hidden using `hidden`.
- **Image resolution**: iOS supports resolver chains for third-party image libraries; Android uses Fresco listeners for [`GenericDraweeView`](https://frescolib.org/docs/drawee-views.html).

## Porting to the New Architecture (Fabric)
This library is currently a **legacy/Paper** native component + module (using [`requireNativeComponent`](../react-native/packages/react-native/Libraries/ReactNative/requireNativeComponent.js) and [`NativeModules`](../react-native/packages/react-native/Libraries/BatchedBridge/NativeModules.js)). To support the **New Architecture**, you have two phases: **Interop Layer compatibility** (short-term) and **native Fabric/TurboModule migration** (long-term).

### Phase 1: Interop Layer compatibility (recommended first)
React Native’s guidance is to **make legacy libraries work with the Interop Layer first** before fully migrating to Fabric/TurboModules.  
Docs: [`enable-libraries.md`](../react-native-new-architecture/docs/enable-libraries.md)

**Why this is viable here**  
Interop breaks with custom `ShadowNode`s; this library does not define any custom `ShadowNode`, so it is likely compatible after a few fixes.

**Interop-specific fixes for this repo**
- **Android: avoid `ThemedContext.getNativeModule()`**  
  In Interop/bridgeless mode, `ThemedContext.getNativeModule()` can call the wrong implementation. Update code paths that rely on it to use `getReactApplicationContext()` instead.  
  In this repo, `RNSharedElementTransitionManager.createViewInstance(...)` calls `reactContext.getNativeModule(...)` and should be updated to the recommended pattern.
- **JavaScript: avoid spreading `NativeModules.<Module>`**  
  The interop layer wraps modules with lazy prototypes, so spreading or `Object.keys` can fail. This library does not currently spread `NativeModules`, but keep this in mind when refactoring.

### Phase 2: Full Fabric + TurboModule migration
Once interop is stable, migrate to **native Fabric components** and **TurboModules**. Start from the prerequisites guide: [`enable-libraries-prerequisites.md`](../react-native-new-architecture/docs/enable-libraries-prerequisites.md).  
Platform steps: [`enable-libraries-android.md`](../react-native-new-architecture/docs/enable-libraries-android.md), [`enable-libraries-ios.md`](../react-native-new-architecture/docs/enable-libraries-ios.md)

**JS API surface changes**
- **Replace `requireNativeComponent` with `codegenNativeComponent`**  
  Create `RNSharedElementTransitionNativeComponent.(ts|js)` and define typed props + events. Use [`codegenNativeComponent`](../react-native/packages/react-native/Libraries/Utilities/codegenNativeComponent.js) per the Fabric spec guidance.
- **Move `configure` to a TurboModule**  
  Define `NativeRNSharedElementTransition.(ts|js)` spec using [`TurboModuleRegistry`](../react-native/packages/react-native/Libraries/TurboModule/TurboModuleRegistry.js). Replace direct `NativeModules.RNSharedElementTransition.configure(...)` calls with the TurboModule.
- **Define event types in the spec**  
  The `onMeasureNode` event should be modeled as a `DirectEventHandler` in the Fabric spec.

**Android native changes**
- **Codegen + ViewManager delegate**  
  Implement the generated view manager interface and delegate. Use the backward-compatible Fabric component pattern to share implementation between Paper and Fabric view managers. Docs: [`backwards-compat-fabric-component.md`](../react-native-new-architecture/docs/backwards-compat-fabric-component.md).
- **TurboModule class**  
  Implement the generated TurboModule spec class for `configure(...)`. Docs: [`backwards-compat-turbo-modules.md`](../react-native-new-architecture/docs/backwards-compat-turbo-modules.md).
- **View lookup changes**  
  This library relies on `NativeViewHierarchyManager.resolveView(...)` via `UIManagerModule`. Fabric doesn’t use the legacy view hierarchy; resolving a native view from a tag needs a Fabric-friendly path. This is the highest-risk change and should be validated early.

**iOS native changes**
- **Podspec New Architecture setup**  
  Update the podspec with `install_modules_dependencies(s)` as described in [`enable-libraries-ios.md`](../react-native-new-architecture/docs/enable-libraries-ios.md).
- **Fabric component class + protocol conformance**  
  Implement the generated component protocols and register the Fabric component; use the backward-compatible pattern to keep Paper support. Docs: [`backwards-compat-fabric-component.md`](../react-native-new-architecture/docs/backwards-compat-fabric-component.md).
- **TurboModule implementation**  
  Implement `getTurboModule:` with the generated spec class for `configure(...)`. Docs: [`enable-libraries-ios.md`](../react-native-new-architecture/docs/enable-libraries-ios.md).

**Interop layer vs full migration: recommendation**
- **Short-term**: Use Interop Layer for compatibility and ship a version that works under the New Architecture without rewriting the component.
- **Long-term**: Move to Fabric/TurboModules to avoid dependency on the interop layer, which will be removed in the future.

## Notes for integrators
- Transitions run entirely on the native side after initial node/prop setup.
- Ensure shared elements map to *real* native views (non-collapsable) and provide correct ancestors.
- Use [`resize`](src/SharedElementTransition.tsx)/[`align`](src/SharedElementTransition.tsx) only when start/end visuals differ (e.g., text expansion).

## Glossary
- **Shared element**: A UI element that appears on both screens and transitions seamlessly between them.
- **Node handle**: The native view identifier returned by [`findNodeHandle`](https://archive.reactnative.dev/docs/direct-manipulation), used to resolve a native view.
- **Ancestor**: The nearest parent view used to normalize measurements (e.g., when a screen is transformed by navigation).
- **Overlay/transition view**: A top-level native view that renders the animated copy of the shared element.
- **Content snapshot**: A captured view or image used to render the element during the transition.
- **Layout**: The element’s position and size in screen coordinates.
- **Visible layout**: The portion of the layout that is not clipped by ancestor views (e.g., scroll views).
- **Content layout**: The layout of the underlying content (e.g., image bounds) within the element.
- **Resize**: How the content scales between start and end sizes (`auto`, `stretch`, `clip`, `none`).
- **Align**: How content is positioned within the transitioning frame (e.g., `center-center`).
