import * as React from "react";
import {
  View,
  Text,
  Animated,
  Dimensions,
  StyleSheet,
  processColor,
  Platform,
} from "react-native";

import { RNSharedElementTransitionView } from "./RNSharedElementTransitionView";
import {
  SharedElementNode,
  SharedElementAnimation,
  SharedElementResize,
  SharedElementAlign,
  SharedElementNodeType,
  SharedElementContentType,
} from "./types";

export type SharedElementMeasureData = {
  node: SharedElementNodeType;
  layout: {
    x: number;
    y: number;
    width: number;
    height: number;
    visibleX: number;
    visibleY: number;
    visibleWidth: number;
    visibleHeight: number;
    contentX: number;
    contentY: number;
    contentWidth: number;
    contentHeight: number;
  };
  contentType: SharedElementContentType;
  style: {
    borderRadius: number;
  };
};

export type SharedElementOnMeasureEvent = {
  nativeEvent: SharedElementMeasureData;
};

export type SharedElementTransitionProps = {
  /** Start node/ancestor for the shared element transition. */
  start: {
    /** Start node to snapshot and animate from. */
    node: SharedElementNode | null;
    /** Ancestor for coordinate normalization and transform compensation. */
    ancestor: SharedElementNode | null;
  };
  /** End node/ancestor for the shared element transition. */
  end: {
    /** End node to snapshot and animate to. */
    node: SharedElementNode | null;
    /** Ancestor for coordinate normalization and transform compensation. */
    ancestor: SharedElementNode | null;
  };
  /** High-level animation type (move, fade, fade-in, fade-out). */
  animation: SharedElementAnimation;
  /** Resize behavior for the shared element content. */
  resize?: SharedElementResize;
  /** Alignment behavior for the shared element content. */
  align?: SharedElementAlign;
  /**
   * Fabric interop path: when true, native code drives progress on the
   * native thread using CADisplayLink (fixed duration).
   */
  nativeDriver?: boolean;
  /** Native duration in milliseconds for the CADisplayLink-driven path. */
  nativeDuration?: number;
  /** Native delay in milliseconds before starting the CADisplayLink path. */
  nativeDelay?: number;
  /** Optional native start progress value (defaults to current position). */
  nativeFrom?: number;
  /** Optional native end progress value (defaults to 1.0). */
  nativeTo?: number;
  /** Enable debug overlays and boundary visuals. */
  debug?: boolean;
  /** Additional style applied to the transition view. */
  style?: any;
  /** Measure callback for debugging and instrumentation. */
  onMeasure?: (event: SharedElementOnMeasureEvent) => void;
  /** Override the native transition component (advanced usage). */
  SharedElementComponent?: any;
};

const NativeAnimationType = new Map<SharedElementAnimation, number>([
  ["move", 0],
  ["fade", 1],
  ["fade-in", 2],
  ["fade-out", 3],
]);

const NativeResizeType = new Map<SharedElementResize, number>([
  ["auto", 0],
  ["stretch", 1],
  ["clip", 2],
  ["none", 3],
]);

const NativeAlignType = new Map<SharedElementAlign, number>([
  ["auto", 0],
  ["left-top", 1],
  ["left-center", 2],
  ["left-bottom", 3],
  ["right-top", 4],
  ["right-center", 5],
  ["right-bottom", 6],
  ["center-top", 7],
  ["center-center", 8],
  ["center-bottom", 9],
]);

const debugColors = {
  startNode: "#82B2E8",
  endNode: "#5EFF9B",
  pink: "#DC9CFF",
  startAncestor: "#E88F82",
  endAncestor: "#FFDC8F",
};

const debugStyles = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFill,
    backgroundColor: "black",
    opacity: 0.3,
  },
  text: {
    marginLeft: 3,
    marginTop: 3,
    fontSize: 10,
  },
  box: {
    position: "absolute",
    borderWidth: 1,
    borderStyle: "dashed",
  },
});

type StateType = {
  startNode?: SharedElementMeasureData;
  endNode?: SharedElementMeasureData;
  startAncestor?: SharedElementMeasureData;
  endAncestor?: SharedElementMeasureData;
};

export const RNAnimatedSharedElementTransitionView =
  RNSharedElementTransitionView
    ? Animated.createAnimatedComponent(RNSharedElementTransitionView)
    : undefined;

const getResizeModeFromContentFit = (contentFit: unknown) => {
  if (contentFit === "cover" || contentFit === "contain") {
    return contentFit;
  }

  if (contentFit === "fill") {
    return "stretch";
  }

  if (contentFit === "none" || contentFit === "scale-down") {
    return "center";
  }

  return undefined;
};

export class SharedElementTransition extends React.Component<
  SharedElementTransitionProps,
  StateType
> {
  static prepareNode(node: SharedElementNode | null): any {
    let nodeStyle: any = {};
    if (Platform.OS === "android" && node && node.parentInstance) {
      const child = React.Children.only(node.parentInstance.props.children);
      const props = child ? child.props : {};
      nodeStyle = StyleSheet.flatten([props.style]) || {};
      delete nodeStyle.transform;
      delete nodeStyle.opacity;
      nodeStyle.resizeMode =
        nodeStyle.resizeMode ||
        props.resizeMode ||
        getResizeModeFromContentFit(props.contentFit);
      if (nodeStyle.backgroundColor)
        nodeStyle.backgroundColor = processColor(nodeStyle.backgroundColor);
      if (nodeStyle.borderColor)
        nodeStyle.borderColor = processColor(nodeStyle.borderColor);
      if (nodeStyle.color) nodeStyle.color = processColor(nodeStyle.color);
    }
    return node
      ? {
          nodeHandle: node.nodeHandle,
          isParent: node.isParent,
          nodeStyle,
        }
      : undefined;
  }

  static defaultProps = {
    start: {},
    end: {},
    SharedElementComponent: RNAnimatedSharedElementTransitionView,
    animation: "move",
    resize: "auto",
    align: "auto",
  };

  constructor(props: SharedElementTransitionProps) {
    super(props);
    if (
      !props.SharedElementComponent &&
      !SharedElementTransition.isNotAvailableWarningShown
    ) {
      SharedElementTransition.isNotAvailableWarningShown = true;
      if (Platform.OS === "android" || Platform.OS === "ios") {
        console.warn(
          "RNSharedElementTransition is not available, did you forget to link `react-native-shared-element` into your project?"
        );
      } else {
        console.warn(
          "RNSharedElementTransition is not available on this platform"
        );
      }
    }
  }

  state: StateType = {};

  private static isNotAvailableWarningShown = false;

  onMeasureNode = (event: SharedElementOnMeasureEvent) => {
    const { nativeEvent } = event;
    const { onMeasure } = this.props;
    this.setState({
      [`${nativeEvent.node}`]: nativeEvent,
    });
    // console.log("onMeasure: ", nativeEvent);
    if (onMeasure) {
      onMeasure(event);
    }
  };

  renderDebugOverlay(): React.ReactNode {
    if (!this.props.debug) {
      return null;
    }
    return <View style={debugStyles.overlay} />;
  }

  renderDebugLayer(name: SharedElementNodeType): React.ReactNode {
    const event = this.state[name];
    if (!event || !this.props.debug) return null;
    const { layout, style } = event;
    const isContentDifferent =
      layout.x !== layout.contentX ||
      layout.y !== layout.contentY ||
      layout.width !== layout.contentWidth ||
      layout.height !== layout.contentHeight;
    const isFullScreen =
      layout.visibleX === 0 &&
      layout.visibleY === 0 &&
      layout.visibleWidth === Dimensions.get("window").width &&
      layout.visibleHeight === Dimensions.get("window").height;

    const color = debugColors[name];
    return (
      <View style={StyleSheet.absoluteFill}>
        {isContentDifferent ? (
          <View
            style={[
              debugStyles.box,
              {
                left: layout.contentX,
                top: layout.contentY,
                width: layout.contentWidth,
                height: layout.contentHeight,
                borderColor: color,
                opacity: 0.5,
              },
            ]}
          >
            <Text style={[debugStyles.text, { color }]}>Content</Text>
          </View>
        ) : undefined}
        <View
          style={[
            debugStyles.box,
            {
              left: layout.x,
              top: layout.y,
              width: layout.width,
              height: layout.height,
              borderColor: color,
              borderRadius: style.borderRadius || 0,
            },
          ]}
        >
          <Text
            style={[
              debugStyles.text,
              { color, marginTop: Math.max((style.borderRadius || 0) - 7, 3) },
            ]}
          >
            {name}
          </Text>
        </View>
        <View
          style={{
            position: "absolute",
            overflow: "hidden",
            left: layout.visibleX,
            top: layout.visibleY,
            width: layout.visibleWidth,
            height: layout.visibleHeight,
          }}
        >
          <View
            style={[
              {
                position: "absolute",
                left: layout.x - layout.visibleX,
                top: layout.y - layout.visibleY,
                width: layout.width,
                height: layout.height,
                borderRadius: style.borderRadius || 0,
                backgroundColor: isFullScreen ? "transparent" : color + "80",
              },
            ]}
          />
        </View>
      </View>
    );
  }

  render() {
    const {
      SharedElementComponent,
      start,
      end,
      animation,
      resize,
      align,
      onMeasure,
      debug,
      // style,
      ...otherProps
    } = this.props;
    if (!SharedElementComponent) {
      return null;
    }
    return (
      <View style={StyleSheet.absoluteFill}>
        <SharedElementComponent
          startNode={{
            node: SharedElementTransition.prepareNode(start.node),
            ancestor: SharedElementTransition.prepareNode(start.ancestor),
          }}
          endNode={{
            node: SharedElementTransition.prepareNode(end.node),
            ancestor: SharedElementTransition.prepareNode(end.ancestor),
          }}
          style={StyleSheet.absoluteFill}
          animation={NativeAnimationType.get(animation)}
          // @ts-ignore
          resize={NativeResizeType.get(resize)}
          // @ts-ignore
          align={NativeAlignType.get(align)}
          nativeDriver={true}
          onMeasureNode={debug ? this.onMeasureNode : onMeasure}
          // style={debug && style ? [debugStyles.content, style] : style}
          {...otherProps}
        />
        {/*this.renderDebugOverlay()*/}
        {this.renderDebugLayer("startNode")}
        {this.renderDebugLayer("endNode")}
      </View>
    );
  }
}
