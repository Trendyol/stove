export async function request(url: string, init?: RequestInit): Promise<Response> {
  if (typeof __STOVE_DEMO__ !== "undefined" && __STOVE_DEMO__) {
    const { demo } = await import("../demo/bootstrap");
    return (await demo).request(url, init);
  }
  return fetch(url, init);
}
