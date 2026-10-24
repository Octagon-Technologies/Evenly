import React from "react";
import { AbsoluteFill, Img, staticFile } from "remotion";
import { C, Caption, Card, Fonts, LightStage, Phone } from "./ui";

// Real device screenshots of the owner's "Trip to Puerto Rico" group and the Jala lunch bill.
const SCREENS = {
  newGroup: "screens/s1-new-group.png",
  scan: "screens/s2-scan.png",
  review: "screens/s3-review.png",
  assign: "screens/s4-assign.png",
  shares: "screens/s5-shares.png",
};

// Must match the bill in the screenshots: three dishes, $60.00 + $5.82 tax and tip = $65.82.
const DISHES = [
  ["Chicken Tikka Masala", "24.00", "bg/food1.png"],
  ["24k Gold Garlic Naan", "12.00", "bg/food2.png"],
  ["Butter Chicken", "24.00", "bg/food3.png"],
] as const;

const Receipt: React.FC<{ left: number; top: number; rotate: number; width?: number }> = ({
  left,
  top,
  rotate,
  width = 340,
}) => (
  <Card
    style={{
      left,
      top,
      width,
      padding: "34px 28px 44px",
      borderRadius: 6,
      transform: `rotate(${rotate}deg)`,
      fontFamily: "PlexMono",
      fontSize: 20,
      color: "#1d2233",
    }}
  >
    <div style={{ textAlign: "center", fontWeight: 600, fontSize: 25, letterSpacing: 3 }}>RECEIPT</div>
    <div style={{ textAlign: "center", fontSize: 16, color: "#6a7184", marginTop: 6 }}>Table 12 · 2 guests</div>
    <div style={{ borderTop: "2px dashed #b9c0d0", margin: "18px 0" }} />
    {DISHES.map(([name, price]) => (
      <div key={name} style={{ display: "flex", justifyContent: "space-between", margin: "14px 0", gap: 12 }}>
        <span style={{ flex: 1 }}>{name}</span>
        <span>{price}</span>
      </div>
    ))}
    <div style={{ borderTop: "2px dashed #b9c0d0", margin: "18px 0" }} />
    {[
      ["Subtotal", "60.00"],
      ["Tax & tip", "5.82"],
    ].map(([k, v]) => (
      <div key={k} style={{ display: "flex", justifyContent: "space-between", margin: "10px 0" }}>
        <span>{k}</span>
        <span>{v}</span>
      </div>
    ))}
    <div style={{ display: "flex", justifyContent: "space-between", marginTop: 16, fontWeight: 600, fontSize: 24 }}>
      <span>Total</span>
      <span>65.82</span>
    </div>
  </Card>
);

/** 0–4s. The real phone sits where the concept art's generated phone was, so it covers it fully. */
export const Arrival: React.FC = () => (
  <AbsoluteFill style={{ fontFamily: "Plex" }}>
    <Fonts />
    <Img src={staticFile("bg/arrival.png")} style={{ width: 1080, height: 1920 }} />
    <AbsoluteFill
      style={{ background: "linear-gradient(180deg, rgba(10,30,80,0.25) 0%, rgba(10,30,80,0) 32%)" }}
    />
    <Caption
      top={260}
      left={96}
      before="Going on a trip with "
      accent="friends?"
      width={520}
      size={66}
      color="#fff"
      accentColor="#fff"
    />
    <Phone src={SCREENS.newGroup} left={560} top={590} width={500} tilt={-6} />
  </AbsoluteFill>
);

/** The paper receipt sits beside the scan card it is about to go into. */
export const Scan: React.FC = () => (
  <LightStage>
    <Caption top={180} before="At restaurants, " accent="scan the receipt" width={880} />
    <Phone src={SCREENS.scan} left={470} top={380} width={560} tilt={-8} />
    <Receipt left={50} top={720} rotate={-6} />
  </LightStage>
);

/** Receipt lines lift off as cards and land as the phone's item rows (no connector graphics). */
export const Review: React.FC = () => (
  <LightStage>
    <Caption top={180} before="Every item, " accent="read for you" width={880} />
    <Receipt left={28} top={520} rotate={-4} width={300} />
    {DISHES.map(([name, price, img], i) => (
      <Card
        key={name}
        style={{
          left: 318 + (i % 2) * 14,
          top: 600 + i * 150,
          width: 300,
          height: 112,
          borderRadius: 22,
          display: "flex",
          alignItems: "center",
          gap: 16,
          padding: "0 18px",
          transform: `rotate(${-2 + i * 1.5}deg)`,
          boxShadow: "0 18px 40px rgba(55,98,227,0.28), 0 0 0 2px rgba(76,123,255,0.25)",
        }}
      >
        <Img src={staticFile(img)} style={{ width: 76, height: 76, borderRadius: 38, objectFit: "cover", flexShrink: 0 }} />
        <div>
          <div style={{ fontSize: 23, fontWeight: 600, color: C.ink, lineHeight: 1.15 }}>{name}</div>
          <div style={{ fontSize: 23, fontFamily: "PlexMono", color: C.inkSoft, marginTop: 4 }}>${price}</div>
        </div>
      </Card>
    ))}
    <Phone src={SCREENS.review} left={640} top={400} width={430} tilt={-8} />
  </LightStage>
);

export const Assign: React.FC = () => (
  <LightStage>
    <Caption top={170} before="Everyone " accent="picks what they ate" width={880} />
    <Phone src={SCREENS.assign} left={235} top={360} width={590} />
  </LightStage>
);

export const Shares: React.FC = () => (
  <LightStage>
    <Caption top={170} before="Everyone's share, " accent="tax and tip included" width={880} />
    <Phone src={SCREENS.shares} left={235} top={360} width={590} />
  </LightStage>
);
