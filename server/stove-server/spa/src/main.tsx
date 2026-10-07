import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { lazy, StrictMode, Suspense } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";

const DemoToolbar = __STOVE_DEMO__ ? lazy(() => import("./demo/DemoToolbar")) : null;

import { ThemeProvider } from "./hooks/useTheme";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        {DemoToolbar ? (
          <div className="stove-demo-root">
            <Suspense fallback={<div role="status">Loading demo…</div>}>
              <DemoToolbar />
            </Suspense>
            <App />
          </div>
        ) : (
          <App />
        )}
      </ThemeProvider>
    </QueryClientProvider>
  </StrictMode>,
);
