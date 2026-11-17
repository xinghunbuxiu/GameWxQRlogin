import { readFile } from "node:fs/promises";
import pg from "pg";

const connectionString = process.env.DATABASE_URL;
if (!connectionString) throw new Error("DATABASE_URL is required for database invariant tests");

const pool = new pg.Pool({ connectionString });
try {
  const schema = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
  const invariants = await readFile(new URL("./session-invariants.sql", import.meta.url), "utf8");
  await pool.query(schema);
  await pool.query(invariants);
  process.stdout.write("PostgreSQL session invariants passed.\\n");
} finally {
  await pool.end();
}
