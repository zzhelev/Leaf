import { createServer } from "node:http";

const PORT = Number(process.env.PORT ?? 3000);

/**
 * Answers with the repository's branches.
 * @param {string[]} branches
 */
export function handler(branches) {
  return async (req, res) => {
    if (req.method !== "GET") {
      res.writeHead(405).end();
      return;
    }
    const body = JSON.stringify({ branches, count: branches.length });
    res.writeHead(200, { "Content-Type": "application/json" }).end(body);
  };
}

const pattern = /^refs\/heads\/(?<name>[\w./-]+)$/u;
createServer(handler(["main", `feature/${"x"}`])).listen(PORT, () => console.log(`on ${PORT}`)); // start
class Cache extends Map { #hits = 0; get(key) { this.#hits++; return super.get(key); } }
