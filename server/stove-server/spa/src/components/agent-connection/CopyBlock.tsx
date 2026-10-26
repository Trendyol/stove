import { useState } from "react";

type CopyBlockProps = Readonly<{ label: string; text: string }>;
const messages = {
  idle: "",
  copied: "Copied",
  failed: "Clipboard unavailable. Select and copy the text below.",
} as const;

export function CopyBlock(props: CopyBlockProps) {
  return <CopyContent key={props.text} {...props} />;
}

function CopyContent({ label, text }: CopyBlockProps) {
  const [status, setStatus] = useState<keyof typeof messages>("idle");
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setStatus("copied");
    } catch {
      setStatus("failed");
    }
  }
  return (
    <div className="agent-copy-block">
      <div className="agent-copy-heading">
        <span>{label}</span>
        <button
          type="button"
          className="stove-focus-ring"
          aria-label={`Copy ${label}`}
          onClick={() => void copy()}
        >
          {status === "copied" ? "Copied ✓" : "Copy"}
        </button>
      </div>
      <pre>
        <code>{text}</code>
      </pre>
      <span className={status === "failed" ? "agent-copy-error" : "sr-only"} role="status">
        {messages[status]}
      </span>
    </div>
  );
}
