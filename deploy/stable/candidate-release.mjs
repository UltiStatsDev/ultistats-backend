import { lstatSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  check,
  privateDirectory,
  readPrivateJson,
  writePrivateJson,
  safeReason,
} from "./helper-contract.mjs";
import { validateCandidateRequest } from "./candidate-target.mjs";
import { executeCandidateRequest } from "./backend-release.mjs";
export { executeCandidateRequest };
function fresh(file) {
  try {
    lstatSync(file);
    return false;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    return true;
  }
}
export async function main(argv = process.argv.slice(2)) {
  let request,
    resultPath,
    validated = false;
  try {
    check(argv.length === 5 && argv[1] === "--request" && argv[3] === "--result", "USAGE");
    resultPath = resolve(argv[4]);
    check(resultPath === argv[4] && resolve(argv[2]) === argv[2], "USAGE");
    privateDirectory(dirname(resultPath));
    request = validateCandidateRequest(readPrivateJson(argv[2]));
    const files = [
      argv[2],
      request.installationManifestPath,
      request.targetDescriptorPath,
      ...Object.values(request.candidateStages),
      request.fencePath,
      request.previousManifestPath,
      request.rollbackPlanPath,
    ].filter(Boolean);
    check(
      request.operation === argv[0] &&
        !files.includes(resultPath) &&
        fresh(resultPath) &&
        new Set(files).size === files.length,
      "USAGE",
    );
    validated = true;
    const result = await executeCandidateRequest(request);
    writePrivateJson(resultPath, result);
    return 0;
  } catch (error) {
    const reason = safeReason(error);
    if (validated) {
      const maintenance = !["inspect", "fence-acquire", "fence-recheck"].includes(request.operation);
      try {
        writePrivateJson(resultPath, {
          protocolVersion: 2,
          operation: request.operation,
          transactionId: request.transactionId,
          disposition: maintenance ? "maintenance_required" : "blocked",
          reason,
          evidence: {},
        });
      } catch {}
      return reason === "HELPER_FAILED" ? 70 : maintenance ? 3 : 2;
    }
    process.stderr.write(`${reason}\n`);
    return ["USAGE", "INVALID_REQUEST", "INVALID_CANDIDATE"].includes(reason) ? 64 : 70;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  process.exitCode = await main();
