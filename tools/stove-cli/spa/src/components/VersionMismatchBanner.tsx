import {
  buildVersionMismatchBannerModel,
  type VersionMismatchSummary,
} from "../utils/version-mismatch";

interface VersionMismatchBannerProps {
  summary: VersionMismatchSummary;
}

export function VersionMismatchBanner({ summary }: VersionMismatchBannerProps) {
  const model = buildVersionMismatchBannerModel(summary);
  const affectedApps = model.affectedApps.join(", ");

  return (
    <section className="border-b border-amber-500/30 bg-amber-100 text-amber-950 px-4 py-3">
      <div className="flex items-start gap-3">
        <span className="text-lg leading-none" aria-hidden="true">
          !
        </span>
        <div className="min-w-0">
          <p className="text-sm font-semibold">{model.title}</p>
          <p className="mt-1 text-xs leading-5">
            Affected apps: <span className="font-medium">{affectedApps}</span>.
            {model.switchHint ? ` ${model.switchHint}` : null}
          </p>

          {model.selectedAppName ? (
            <div className="mt-3 rounded-md border border-amber-500/40 bg-white/60 px-3 py-3 text-xs leading-5">
              <p className="font-semibold">
                Selected app: <span className="font-mono">{model.selectedAppName}</span>
              </p>
              <p className="mt-1">
                Runtime version:{" "}
                <span className="font-mono">{model.runtimeVersion ?? "not reported"}</span>
                {" · "}
                CLI version: <span className="font-mono">{model.cliVersion}</span>
              </p>
              <div className="mt-2 space-y-1">
                {model.remediationSteps.map((step) => (
                  <p key={step.value}>
                    {step.kind === "command" ? (
                      <code className="font-mono break-all">{step.value}</code>
                    ) : (
                      step.value
                    )}
                  </p>
                ))}
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}
