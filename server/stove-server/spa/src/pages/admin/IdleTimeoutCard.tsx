import { DEFAULT_IDLE_TIMEOUT_SECONDS, MAX_IDLE_TIMEOUT_SECONDS } from "../../utils/idle-timeout";

interface IdleTimeoutCardProps {
  seconds: number;
  valid: boolean;
  busy: boolean;
  onChange: (seconds: number) => void;
  onApply: () => void;
}

export function IdleTimeoutCard({ seconds, valid, busy, onChange, onApply }: IdleTimeoutCardProps) {
  return (
    <section className="stove-admin-card">
      <h3>Idle run cleanup</h3>
      <p>
        Delete running runs and all their tests and evidence after this period without new
        ingestion. Default: {DEFAULT_IDLE_TIMEOUT_SECONDS} seconds (1 hour). Use 0 to disable.
      </p>
      <label className="stove-admin-field">
        <span>Idle timeout (seconds)</span>
        <input
          type="number"
          min="0"
          max={MAX_IDLE_TIMEOUT_SECONDS}
          step="1"
          value={Number.isNaN(seconds) ? "" : seconds}
          aria-invalid={!valid}
          onChange={(event) => onChange(event.target.valueAsNumber)}
        />
      </label>
      {!valid && (
        <p role="alert">Enter a whole number between 0 and {MAX_IDLE_TIMEOUT_SECONDS} seconds.</p>
      )}
      <p>
        Saved for all server instances. Applies on the next sweep, within one minute. Reducing this
        value can permanently delete runs already idle beyond the new timeout.
      </p>
      <button type="button" disabled={busy || !valid} onClick={onApply}>
        Apply idle timeout
      </button>
    </section>
  );
}
