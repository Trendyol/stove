import {
  buildVersionMismatchWarningModel,
  type VersionMismatchSummary,
} from "../utils/version-mismatch";

interface VersionMismatchWarningProps {
  summary: VersionMismatchSummary;
}

export function VersionMismatchWarning({ summary }: VersionMismatchWarningProps) {
  const model = buildVersionMismatchWarningModel(summary);
  const mismatchLabel = model.mismatchCount === 1 ? "version mismatch" : "version mismatches";

  return (
    <div className="stove-version-warning">
      <button
        type="button"
        className="stove-version-warning-trigger stove-focus-ring"
        aria-describedby="stove-version-warning-details"
        aria-label={`${model.mismatchCount} ${mismatchLabel} detected`}
      >
        <WarningIcon />
        <span className="stove-version-warning-label">Version mismatch</span>
        <span className="stove-version-warning-count">{model.mismatchCount}</span>
      </button>

      <div
        id="stove-version-warning-details"
        className="stove-version-warning-popover"
        role="tooltip"
      >
        <div className="stove-version-warning-heading">
          <span className="stove-version-warning-heading-icon">
            <WarningIcon />
          </span>
          <div>
            <strong>{model.title}</strong>
            <p>
              Dashboard CLI <code>v{model.cliVersion}</code> · {model.mismatchCount} affected
              {model.mismatchCount === 1 ? " app" : " apps"}
            </p>
          </div>
        </div>

        <div className="stove-version-warning-list">
          {model.details.map((detail) => (
            <article
              key={detail.appName}
              className={`stove-version-warning-detail ${detail.selected ? "is-selected" : ""}`}
            >
              <div className="stove-version-warning-detail-heading">
                <strong>{detail.appName}</strong>
                {detail.selected ? <span>Selected app</span> : null}
              </div>
              <p className="stove-version-warning-problem">{detail.problem}</p>
              <dl className="stove-version-warning-versions">
                <div>
                  <dt>Runtime</dt>
                  <dd>{detail.runtimeVersion ? `v${detail.runtimeVersion}` : "Not reported"}</dd>
                </div>
                <div>
                  <dt>CLI</dt>
                  <dd>v{detail.cliVersion}</dd>
                </div>
              </dl>
              <div className="stove-version-warning-fix">
                <span>Recommended fix</span>
                {detail.remediationSteps.map((step) =>
                  step.kind === "command" ? (
                    <code key={`${step.kind}-${step.value}`}>{step.value}</code>
                  ) : (
                    <p key={`${step.kind}-${step.value}`}>{step.value}</p>
                  ),
                )}
              </div>
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}

function WarningIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 0 20 20" fill="currentColor">
      <path
        fillRule="evenodd"
        d="M8.485 3.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.88c.674 1.167-.168 2.625-1.514 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625l6.28-10.88ZM10 7a.75.75 0 0 1 .75.75v3.5a.75.75 0 0 1-1.5 0v-3.5A.75.75 0 0 1 10 7Zm0 7.25a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z"
        clipRule="evenodd"
      />
    </svg>
  );
}
