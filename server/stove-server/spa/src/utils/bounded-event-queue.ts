/** Overflow discards the whole incremental batch and requests one recovery on drain. */
export class BoundedEventQueue<T> {
  private items: T[] = [];
  private bytes = 0;
  private overflowed = false;

  constructor(
    private readonly maxEvents: number,
    private readonly maxBytes: number,
  ) {}

  push(item: T, bytes: number): void {
    if (this.overflowed) return;
    if (this.items.length >= this.maxEvents || this.bytes + bytes > this.maxBytes) {
      this.clear();
      this.overflowed = true;
      return;
    }
    this.items.push(item);
    this.bytes += bytes;
  }

  drain(): { items: T[]; overflowed: boolean } {
    const batch = { items: this.items, overflowed: this.overflowed };
    this.clear();
    return batch;
  }

  clear(): void {
    this.items = [];
    this.bytes = 0;
    this.overflowed = false;
  }
}
