# Interop Fix Implementation Notes

## Context
Goal: get `react-native-shared-element` working under the New Architecture (Fabric interop) on iOS with minimal functional changes.

## Progress so far
1) **Bootstrap not running under Fabric interop**
   - Root cause: `RNSharedElementTransition` only initializes in `reactSetFrame:`, which is not called for legacy views hosted in Fabric interop.
   - Fix: added a Fabric-safe bootstrap in `layoutSubviews` via `startTransitionIfNeeded:`.
   - Logs added via `DebugLog` for bootstrap/skips.

2) **Transition view had zero height under Fabric**
   - Root cause: native component had no explicit style, so Fabric measured it to `height: 0` even though the wrapper View was absolute-filled.
   - Fix: set `style={StyleSheet.absoluteFill}` on the native `SharedElementComponent`.

## Current status
- Transition element is **visible** during the transition.
- It **does not animate to the new location** yet.
- Latest logs show bootstrap is now running with full bounds:
  - `RNSharedElementTransition: bootstrap (layoutSubviews), bounds={{0, 0}, {440, 956}} super={{0, 0}, {440, 956}}`
- Attempting to switch to JS-driven animations triggered:
  - `Attempting to run JS driven animation on animated node that has been moved to "native" earlier by starting an animation with useNativeDriver: true`

## Files changed
- `ios/RNSharedElementTransition.m`
  - Added `startTransitionIfNeeded:`
  - Called from `layoutSubviews`
  - Improved bootstrap logging and bounds checks
  - Added native timer animation (CADisplayLink) driven internally (native-only)
- `src/SharedElementTransition.tsx`
  - Added `style={StyleSheet.absoluteFill}` to the native transition component
- `package.json`
  - Removed `codegenConfig` to avoid Codegen failures during `pod install`
- `example/src/components/router/Router.tsx`
  - Simplified to native-only timer without JS progress props

## Remaining issue (next step)
The element renders but does not animate to the end position. This suggests that **node measurements (start/end)** are not reaching native correctly under Fabric interop. Likely focus areas:
- `onMeasureNode` events firing (or not)
- Node handles/ancestor handles being resolved

## React Navigation integration plan (Fabric)
Short term (minimal):
- Provide a **native-timer path** (CADisplayLink) so shared elements can animate without relying on React Navigation’s `progress` value.
- This ignores interactive progress and uses a fixed duration.

Long term (preferred):
- Add a **Reanimated integration** path that drives native progress via `useAnimatedProps` on a Fabric component.
- Avoid JS progress plumbing; keep the native timer path as the minimal baseline.
