/** Keeps one active calculation and replaces only the pending input. */
export class LatestTask<Input, Output> {
  private active: { scope: string; input: Input } | undefined;
  private pending: { scope: string; input: Input } | undefined;
  private scope = "";
  private disposed = false;

  constructor(
    private readonly run: (input: Input) => void,
    private readonly publish: (output: Output, input: Input) => void,
  ) {}

  submit(scope: string, input: Input) {
    if (this.disposed) return;
    this.scope = scope;
    const task = { scope, input };
    if (this.active) {
      this.pending = task;
    } else {
      this.active = task;
      this.run(input);
    }
  }

  complete(output: Output) {
    if (this.disposed || !this.active) return;
    const completed = this.active;
    if (completed.scope === this.scope) this.publish(output, completed.input);
    this.active = undefined;
    const next = this.pending;
    this.pending = undefined;
    if (next) {
      this.active = next;
      this.run(next.input);
    }
  }

  dispose() {
    this.disposed = true;
    this.active = undefined;
    this.pending = undefined;
  }
}
