# Interop Fix Plan (iOS, New Architecture)

Goal: make `RNSharedElementTransition` run under the Fabric Interop Layer with minimal functional changes. Legacy/Paper compatibility is *not* required.

## Problem summary
- `RNSharedElementTransition` only starts its layout/content pipeline inside `-reactSetFrame:`.
- Under Fabric interop, legacy views are hosted inside `RCTLegacyViewManagerInteropComponentView`, which uses `updateLayoutMetrics` and **does not call `reactSetFrame:`** on the hosted (paper) view.
- Result: `_reactFrameSet` / `_initialLayoutPassCompleted` never flip to `YES`, `updateStyle` returns early, and the transition never renders.

## Strategy
Move the “initial layout pass completed” trigger off `reactSetFrame:` and onto a Fabric-safe lifecycle path that fires in both interop and native view layout.

## Proposed minimal changes (iOS)
1. **Add a one-time layout bootstrap in `layoutSubviews`**
   - In `ios/RNSharedElementTransition.m`, implement `-layoutSubviews` to:
     - Call `[super layoutSubviews]`.
     - If `_reactFrameSet` is `NO` and the view has non-zero bounds (or a window), schedule the existing startup sequence:
       - request style + content for items (same block as in `reactSetFrame:`)
       - set `_initialLayoutPassCompleted = YES`
       - call `updateStyle` + `updateNodeVisibility`
   - This mirrors what `reactSetFrame:` does today but is compatible with Fabric interop.

2. **Keep `reactSetFrame:` but allow idempotent startup**
   - `reactSetFrame:` should keep its current logic for non-interop cases, but it must not conflict with the new `layoutSubviews` path.
   - Use the existing `_reactFrameSet` flag to prevent double initialization.

3. **Guard against zero-sized layout**
   - If `bounds.size` is zero, skip initialization to avoid invalid measurements.
   - The next layout pass will re-run and initialize correctly.

4. **Verify native view lookup still works**
   - `nodeFromJson` uses `bridge.uiManager viewForReactTag:` which is Fabric-aware and falls back to the legacy registry.
   - No change required here unless view lookup returns `nil` in practice.

## Optional (debug/validation)
- Add a temporary `NSLog` in `layoutSubviews` for `_reactFrameSet` / `_initialLayoutPassCompleted` to confirm bootstrapping.
- Add a temporary JS log for `onMeasureNode` to confirm events fire.

## Risk assessment
- **Low**: This change only shifts when initialization happens.
- **Potential regression**: If `layoutSubviews` runs before React props are set, it might initialize too early. Use the same guard as `reactSetFrame:` (only once, after layout, on main queue) and re-run if needed.

## Rollout plan
1. Implement the iOS change.
2. Test interop on iOS:
   - Transition visible during navigation.
   - `onMeasureNode` events fire.
   - Original nodes hide/unhide correctly.
3. If successful, document the interop-specific behavior in `architecture.md`.
