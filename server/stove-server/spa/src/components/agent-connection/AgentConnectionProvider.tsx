import { createContext, type ReactNode, useContext, useState } from "react";
import { AgentConnectionDialog } from "./AgentConnectionDialog";

const ConnectionContext = createContext<(() => void) | null>(null);

export function AgentConnectionProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <ConnectionContext value={() => setOpen(true)}>
      {children}
      {open && <AgentConnectionDialog onClose={() => setOpen(false)} />}
    </ConnectionContext>
  );
}

export function ConnectAgentButton({
  className = "stove-topbar-link stove-focus-ring",
}: {
  className?: string;
}) {
  const open = useContext(ConnectionContext);
  if (!open) return null;
  return (
    <button type="button" className={className} onClick={open}>
      Connect agent
    </button>
  );
}
