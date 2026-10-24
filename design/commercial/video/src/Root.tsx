import React from "react";
import { Still } from "remotion";
import { Arrival, Assign, Review, Scan, Shares } from "./Frames";

const size = { width: 1080, height: 1920 };

export const Root: React.FC = () => (
  <>
    <Still id="f1-arrival" component={Arrival} {...size} />
    <Still id="f2-scan" component={Scan} {...size} />
    <Still id="f3-review" component={Review} {...size} />
    <Still id="f4-assign" component={Assign} {...size} />
    <Still id="f5-shares" component={Shares} {...size} />
  </>
);
