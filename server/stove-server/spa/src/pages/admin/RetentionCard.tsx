import { useId } from "react";
import type { AppSummary } from "../../api/types";
import { MetadataFilters } from "./MetadataFilters";
import { createRuleDraft, type RetentionDraft, type RetentionRuleDraft } from "./retention-draft";
import { useAdminMetadata } from "./useAdminMetadata";

interface Props {
  apps: AppSummary[];
  retention: RetentionDraft;
  valid: boolean;
  busy: boolean;
  onRetentionChange: (value: RetentionDraft) => void;
  onApply: () => void;
}

export function RetentionCard({ apps, retention, valid, busy, onRetentionChange, onApply }: Props) {
  const updateRule = (index: number, rule: RetentionRuleDraft) =>
    onRetentionChange({
      ...retention,
      rules: retention.rules.map((current, i) => (i === index ? rule : current)),
    });
  const move = (index: number, direction: number) => {
    const rules = [...retention.rules];
    [rules[index], rules[index + direction]] = [rules[index + direction], rules[index]];
    onRetentionChange({ ...retention, rules });
  };
  return (
    <section className="stove-admin-card stove-retention-card">
      <h3>Retention</h3>
      <p>
        Keep passed and failed runs separately per application. Use 0 for unlimited history. Active
        runs are excluded.
      </p>
      <div className="stove-admin-controls">
        <LimitField
          label="Passed runs per app"
          value={retention.passed_runs_per_app}
          onChange={(value) => onRetentionChange({ ...retention, passed_runs_per_app: value })}
        />
        <LimitField
          label="Failed runs per app"
          value={retention.failed_runs_per_app}
          onChange={(value) => onRetentionChange({ ...retention, failed_runs_per_app: value })}
        />
      </div>
      <h4>Metadata overrides</h4>
      <p>
        The first matching rule wins. Each rule keeps its own history per application and outcome.
        Blank limits inherit the defaults above.
      </p>
      {retention.rules.map((rule, index) => (
        <fieldset className="stove-retention-rule" key={rule.id}>
          <legend>Rule {index + 1}</legend>
          <RuleEditor apps={apps} rule={rule} onChange={(value) => updateRule(index, value)} />
          <div className="stove-admin-actions">
            <button type="button" disabled={index === 0} onClick={() => move(index, -1)}>
              Move up
            </button>
            <button
              type="button"
              disabled={index === retention.rules.length - 1}
              onClick={() => move(index, 1)}
            >
              Move down
            </button>
            <button
              type="button"
              onClick={() =>
                onRetentionChange({
                  ...retention,
                  rules: retention.rules.filter((_, i) => i !== index),
                })
              }
            >
              Remove rule
            </button>
          </div>
        </fieldset>
      ))}
      <div className="stove-admin-actions">
        <button
          type="button"
          disabled={retention.rules.length >= 100}
          onClick={() =>
            onRetentionChange({
              ...retention,
              rules: [
                ...retention.rules,
                { ...createRuleDraft(), failed_runs_per_app: retention.failed_runs_per_app },
              ],
            })
          }
        >
          Add metadata rule
        </button>
        <button type="button" disabled={busy || !valid} onClick={onApply}>
          Apply retention
        </button>
      </div>
      {!valid && (
        <p role="status">
          Enter whole numbers of 0 or more. Each rule needs metadata and at least one limit.
        </p>
      )}
      <p>Applying prunes excess completed runs immediately. Saved settings survive restarts.</p>
    </section>
  );
}

function LimitField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="stove-admin-field">
      <span>{label}</span>
      <input
        type="text"
        inputMode="numeric"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function RuleEditor({
  apps,
  rule,
  onChange,
}: {
  apps: AppSummary[];
  rule: RetentionRuleDraft;
  onChange: (rule: RetentionRuleDraft) => void;
}) {
  const appList = useId();
  const key = rule.metadataKey;
  const value = rule.metadataValue;
  const facets = useAdminMetadata(rule.app_name ?? "");
  const addValue = () => {
    if (!key.trim()) return;
    // biome-ignore lint/suspicious/noPrototypeBuiltins: Object.hasOwn requires ES2022; this app targets ES2020.
    const selected = Object.prototype.hasOwnProperty.call(rule.metadata, key)
      ? rule.metadata[key]
      : [];
    onChange({
      ...rule,
      metadata: { ...rule.metadata, [key]: [...new Set([...selected, value])] },
      metadataValue: "",
    });
  };
  const removeValue = (field: string, selected: string) => {
    const metadata = {
      ...rule.metadata,
      [field]: rule.metadata[field].filter((v) => v !== selected),
    };
    if (metadata[field].length === 0) delete metadata[field];
    onChange({ ...rule, metadata });
  };
  return (
    <>
      <label className="stove-admin-field">
        <span>Application (blank for all)</span>
        <input
          list={appList}
          value={rule.app_name ?? ""}
          onChange={(event) => onChange({ ...rule, app_name: event.target.value || null })}
        />
        <datalist id={appList}>
          {apps.map((app) => (
            <option key={app.app_name} value={app.app_name} />
          ))}
        </datalist>
      </label>
      <MetadataFilters
        ariaLabel="Retention metadata filters"
        appName={rule.app_name ?? ""}
        options={facets.data ?? []}
        value={rule.metadata}
        loading={facets.isPending}
        error={facets.error?.message ?? null}
        onRetry={() => void facets.refetch()}
        onChange={(metadata) =>
          onChange({
            ...rule,
            metadata: Object.fromEntries(
              Object.entries(metadata).map(([k, values]) => [k, [...values]]),
            ),
          })
        }
      />
      <p>
        Add exact metadata values, including values that have not been recorded yet. Values within a
        key are alternatives; all keys must match.
      </p>
      <div className="stove-admin-controls">
        <label className="stove-admin-field">
          <span>Metadata key</span>
          <input
            value={key}
            onChange={(event) => onChange({ ...rule, metadataKey: event.target.value })}
          />
        </label>
        <label className="stove-admin-field">
          <span>Metadata value</span>
          <input
            value={value}
            onChange={(event) => onChange({ ...rule, metadataValue: event.target.value })}
          />
        </label>
        <button type="button" disabled={!key.trim()} onClick={addValue}>
          Add value
        </button>
      </div>
      <div className="stove-admin-actions">
        {Object.entries(rule.metadata).flatMap(([field, values]) =>
          values.map((selected) => (
            <button
              type="button"
              key={JSON.stringify([field, selected])}
              aria-label={`Remove ${field}: ${selected}`}
              onClick={() => removeValue(field, selected)}
            >
              {field}: {selected || "(empty value)"} ×
            </button>
          )),
        )}
      </div>
      <div className="stove-admin-controls">
        <LimitField
          label="Passed runs override"
          value={rule.passed_runs_per_app}
          onChange={(passed_runs_per_app) => onChange({ ...rule, passed_runs_per_app })}
        />
        <LimitField
          label="Failed runs override"
          value={rule.failed_runs_per_app}
          onChange={(failed_runs_per_app) => onChange({ ...rule, failed_runs_per_app })}
        />
      </div>
    </>
  );
}
