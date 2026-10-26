import type { Commit } from "./model";
import { mergeBase } from "./git";

export type Mode = "since-split" | "all";

export interface Comparison<T extends Commit = Commit> {
  readonly base: string;
  onlyInA: T[];
  onlyInB: T[];
  mode?: Mode;
}

/** Commits that each side has and the other lacks. */
export async function compare(a: string, b: string, mode: Mode = "since-split"): Promise<Comparison> {
  const base = await mergeBase(a, b);
  const label = `${a.slice(0, 7)}..${b.slice(0, 7)}`;
  if (!base) throw new Error(`No merge base for ${label}`);
  return { base, onlyInA: [], onlyInB: [], mode } satisfies Comparison;
}

enum Relation { Same, Ancestor = 2, Diverged }
@sealed
class Cache<K, V> { private readonly map = new Map<K, V>(); }
export default compare as (a: string, b: string) => Promise<Comparison>;
