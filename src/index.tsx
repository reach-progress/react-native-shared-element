import { NativeModules } from "react-native";

import {
  SharedElementTransition,
  SharedElementTransitionProps,
} from "./SharedElementTransition";

export * from "./SharedElement";
export * from "./SharedElementTransition";
export * from "./types";

type NativeTransitionNode = {
  node?: {
    nodeHandle: number;
    isParent: boolean;
    nodeStyle?: Record<string, unknown>;
    debugName?: string;
  };
  ancestor?: {
    nodeHandle: number;
    isParent: boolean;
    nodeStyle?: Record<string, unknown>;
    debugName?: string;
  };
};

type NativeTransitionReadyResult = {
  ready: boolean;
  reason: string;
  elapsedMs: number;
  hasStartNode: boolean;
  hasEndNode: boolean;
  startStyleReady: boolean;
  startContentReady: boolean;
  endStyleReady: boolean;
  endContentReady: boolean;
};

type RNSharedElementTransitionNativeModule = {
  waitForTransitionReady?: (
    startItem: NativeTransitionNode,
    endItem: NativeTransitionNode,
    timeoutMs: number
  ) => Promise<NativeTransitionReadyResult>;
};

export type SharedElementTransitionsReadyOptions = {
  timeoutMs?: number;
  maxTransitions?: number;
};

export type SharedElementTransitionsReadyResult = {
  ready: boolean;
  reason: string;
  waitedMs: number;
  transitionCount: number;
  results: NativeTransitionReadyResult[];
};

const moduleName = "RNSharedElementTransition";

function getNativeModule(): RNSharedElementTransitionNativeModule | undefined {
  return NativeModules[moduleName] as
    | RNSharedElementTransitionNativeModule
    | undefined;
}

function prepareTransitionItem(
  node: SharedElementTransitionProps["start"]
): NativeTransitionNode {
  return {
    node: SharedElementTransition.prepareNode(node?.node || null),
    ancestor: SharedElementTransition.prepareNode(node?.ancestor || null),
  };
}

export async function waitForSharedElementTransitionsReady(
  transitions: SharedElementTransitionProps[],
  options: SharedElementTransitionsReadyOptions = {}
): Promise<SharedElementTransitionsReadyResult> {
  const startedAt = Date.now();
  const timeoutMs = options.timeoutMs ?? 140;
  const maxTransitions = options.maxTransitions ?? transitions.length;
  const nativeModule = getNativeModule();
  if (
    !nativeModule ||
    typeof nativeModule.waitForTransitionReady !== "function"
  ) {
    return {
      ready: false,
      reason: "native-method-unavailable",
      waitedMs: Date.now() - startedAt,
      transitionCount: 0,
      results: [],
    };
  }

  const probes = transitions
    .slice(0, maxTransitions)
    .map((transition) => ({
      startItem: prepareTransitionItem(transition.start),
      endItem: prepareTransitionItem(transition.end),
    }))
    .filter(({ startItem, endItem }) => !!startItem.node || !!endItem.node);

  if (!probes.length) {
    return {
      ready: false,
      reason: "no-transition-nodes",
      waitedMs: Date.now() - startedAt,
      transitionCount: 0,
      results: [],
    };
  }

  try {
    const results = await Promise.all(
      probes.map(({ startItem, endItem }) =>
        nativeModule.waitForTransitionReady!(startItem, endItem, timeoutMs)
      )
    );
    const ready = results.every((result) => result.ready);
    const output: SharedElementTransitionsReadyResult = {
      ready,
      reason: ready ? "ready" : "not-ready",
      waitedMs: Date.now() - startedAt,
      transitionCount: results.length,
      results,
    };
    return output;
  } catch (error) {
    return {
      ready: false,
      reason: `native-error:${String(error)}`,
      waitedMs: Date.now() - startedAt,
      transitionCount: 0,
      results: [],
    };
  }
}

export const __RNSE_BUILD_ID__ =
  "rnse-native-only-2026-02-26-ios-close-wait-sync-v2";
