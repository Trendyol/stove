import { createContext, useContext } from "react";

export interface ProfileCorrelation {
  traceId: string;
  spanId?: string;
  name: string;
}

const Context = createContext<
  ((runId: string, correlation: ProfileCorrelation) => void) | undefined
>(undefined);
export const DrillDownNavigationProvider = Context.Provider;
export const useDrillDownNavigation = () => useContext(Context);
