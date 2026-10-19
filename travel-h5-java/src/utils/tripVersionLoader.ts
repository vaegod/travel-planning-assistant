/** Apply only the latest requested trip version; reads may finish in any order. */
export function createTripVersionLoader<T>(
  read: (id: string, version: number) => Promise<T>,
  loaded: (value: T, id: string, version: number) => void | Promise<void>,
  failed: (error: unknown) => void,
  loading: (value: boolean) => void,
) {
  let generation = 0;
  let disposed = false;
  return {
    async load(id: string, version: number) {
      if (disposed) return;
      const current = ++generation;
      loading(true);
      try {
        const result = await read(id, version);
        if (disposed || current !== generation) return;
        await loaded(result, id, version);
      } catch (error) {
        if (!disposed && current === generation) failed(error);
      } finally {
        if (!disposed && current === generation) loading(false);
      }
    },
    invalidate() { generation++; },
    stop() { disposed = true; generation++; },
  };
}
