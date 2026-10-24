import React from "react";
import { AbsoluteFill, Img, staticFile } from "remotion";
import { FEATHER_PATH } from "./feather";

// Same faces the app ships (composeResources/font), so captions and props match the captured UI.
const fontFaces = [
  ["Plex", "ibm_plex_sans_regular", 400],
  ["Plex", "ibm_plex_sans_medium", 500],
  ["Plex", "ibm_plex_sans_semibold", 600],
  ["Plex", "ibm_plex_sans_bold", 700],
  ["PlexMono", "ibm_plex_mono_regular", 400],
  ["PlexMono", "ibm_plex_mono_medium", 500],
  ["PlexMono", "ibm_plex_mono_semibold", 600],
] as const;

export const Fonts: React.FC = () => (
  <style>
    {fontFaces
      .map(
        ([family, file, weight]) =>
          `@font-face{font-family:${family};src:url(${staticFile(`fonts/${file}.ttf`)});font-weight:${weight};}`,
      )
      .join("\n")}
  </style>
);

// Brand blue #3762E3 is the same value as EvenlySplashBlue / colors.xml / LaunchBackground.
export const C = {
  blue: "#3762E3",
  blueBright: "#4C7BFF",
  ink: "#0B1020",
  inkSoft: "#5B6478",
  paper: "#F4F7FD",
};

export const LightStage: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <AbsoluteFill
    style={{
      background: `radial-gradient(1100px 900px at 85% 8%, #DCE6FB 0%, rgba(220,230,251,0) 60%),
        radial-gradient(900px 900px at 0% 95%, #D6E1FA 0%, rgba(214,225,250,0) 60%),
        ${C.paper}`,
      fontFamily: "Plex",
    }}
  >
    <Fonts />
    {children}
  </AbsoluteFill>
);

/** Caption in the revised small style: dark ink with one blue phrase. */
export const Caption: React.FC<{
  before?: string;
  accent: string;
  after?: string;
  top: number;
  left?: number;
  width?: number;
  color?: string;
  accentColor?: string;
  size?: number;
}> = ({ before, accent, after, top, left = 64, width = 900, color = C.ink, accentColor = C.blue, size = 60 }) => (
  <div
    style={{
      position: "absolute",
      top,
      left,
      width,
      fontFamily: "Plex",
      fontWeight: 600,
      fontSize: size,
      lineHeight: 1.15,
      letterSpacing: -1.2,
      color,
    }}
  >
    {before}
    <span style={{ color: accentColor }}>{accent}</span>
    {after}
  </div>
);

/**
 * iPhone shell around a real device screenshot (921×2000).
 * The captures carry a 10:59 clock, a Focus icon and a live activity in the island, so the shell paints
 * a clean 9:41 status bar over the top band instead of trusting the screenshot's.
 */
export const Phone: React.FC<{
  src: string;
  left: number;
  top: number;
  width: number;
  tilt?: number;
  style?: React.CSSProperties;
}> = ({ src, left, top, width, tilt = 0, style }) => {
  const height = width * (2000 / 921);
  const band = height * (118 / 2000);
  const bezel = width * 0.028;
  const radius = width * 0.155;
  return (
    <div
      style={{
        position: "absolute",
        left,
        top,
        width: width + bezel * 2,
        height: height + bezel * 2,
        borderRadius: radius + bezel,
        background: "linear-gradient(145deg, #6B7183 0%, #23262F 30%, #15171D 70%, #565B6B 100%)",
        padding: bezel,
        boxShadow: "0 60px 120px rgba(18,32,80,0.35), 0 18px 40px rgba(18,32,80,0.25)",
        transform: `perspective(2600px) rotateY(${tilt}deg)`,
        ...style,
      }}
    >
      <div
        style={{
          width,
          height,
          borderRadius: radius,
          overflow: "hidden",
          background: "#000",
          position: "relative",
        }}
      >
        <Img src={staticFile(src)} style={{ width: "100%", height: "100%", display: "block" }} />
        <StatusBar width={width} height={band} />
        <div
          style={{
            position: "absolute",
            top: width * 0.03,
            left: "50%",
            transform: "translateX(-50%)",
            width: width * 0.29,
            height: width * 0.085,
            borderRadius: width,
            background: "#000",
          }}
        />
      </div>
    </div>
  );
};

const StatusBar: React.FC<{ width: number; height: number }> = ({ width, height }) => {
  const u = width / 100;
  return (
    <div
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        width,
        height,
        background: "#000",
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: `${u * 1.2}px ${u * 7.5}px 0 ${u * 9}px`,
        boxSizing: "border-box",
        color: "#fff",
        fontFamily: "-apple-system, 'SF Pro Text', Plex",
        fontWeight: 600,
        fontSize: u * 4.2,
      }}
    >
      <span>9:41</span>
      <svg width={u * 19} height={u * 3.6} viewBox="0 0 76 14" fill="#fff">
        {[0, 1, 2, 3].map((i) => (
          <rect key={i} x={i * 5} y={10 - i * 3} width="3.4" height={4 + i * 3} rx="1" />
        ))}
        <path d="M33 4.2a11 11 0 0 1 15 0l-1.6 1.7a8.6 8.6 0 0 0-11.8 0zM36 7.4a6.6 6.6 0 0 1 9 0l-1.6 1.7a4.3 4.3 0 0 0-5.8 0zM39 10.5a2.3 2.3 0 0 1 3 0L40.5 12z" />
        <rect x="53" y="1.5" width="20" height="11" rx="3.2" fill="none" stroke="#fff" strokeOpacity="0.45" strokeWidth="1.2" />
        <rect x="55" y="3.5" width="16" height="7" rx="1.8" />
        <rect x="74" y="5" width="1.6" height="4" rx="0.8" fillOpacity="0.45" />
      </svg>
    </div>
  );
};

export const Feather: React.FC<{ size: number; color: string }> = ({ size, color }) => (
  <svg width={size} height={size} viewBox="0 0 100 100">
    <path d={FEATHER_PATH} fill={color} />
  </svg>
);

export const Card: React.FC<{ style?: React.CSSProperties; children: React.ReactNode }> = ({ style, children }) => (
  <div
    style={{
      position: "absolute",
      background: "#fff",
      borderRadius: 28,
      boxShadow: "0 30px 70px rgba(30,60,140,0.18), 0 6px 18px rgba(30,60,140,0.10)",
      ...style,
    }}
  >
    {children}
  </div>
);
