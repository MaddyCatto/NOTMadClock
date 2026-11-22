"""Query GitHub's Maven advisory database for resolved dependencies; never upload APKs or songs.

Requires gh CLI authentication (GH_TOKEN in CI). Network/API errors fail the check;
an empty response is a database result, not proof of absence of vulnerabilities.
"""
import concurrent.futures
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/reports/security"


def check(entry):
    for attempt in range(3):
        try:
            return query(entry)
        except (subprocess.SubprocessError, ValueError) as error:
            if attempt == 2:
                raise RuntimeError("Advisory API check failed for " + entry["coordinate"]) from error
            time.sleep(attempt + 1)


def query(entry):
    response = subprocess.check_output([
        "gh", "api", "--method", "GET", "/advisories", "--paginate",
        "-f", "ecosystem=maven", "-f", "affects=" + entry["coordinate"], "-f", "per_page=100",
    ], text=True, timeout=90, stderr=subprocess.PIPE)
    # gh versions without --slurp concatenate the JSON arrays returned by each page.
    advisories = []
    remaining = response.lstrip()
    if not remaining:
        raise ValueError("Empty advisory API response")
    while remaining:
        page, end = json.JSONDecoder().raw_decode(remaining)
        if not isinstance(page, list):
            raise ValueError("Unexpected advisory API response")
        advisories.extend(a for a in page if not a.get("withdrawn_at"))
        remaining = remaining[end:].lstrip()
    return dict(entry, advisories=[{k: a.get(k) for k in ("ghsa_id", "cve_id", "severity", "summary", "html_url", "vulnerabilities")}
                                  for a in advisories])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict-build-tools", action="store_true",
                        help="Also fail for the separately reported build-tool classpath; see docs/security.md")
    args = parser.parse_args()
    inventory = json.loads((REPORT / "dependencies.json").read_text())
    if not inventory:
        raise SystemExit("Dependency inventory is empty")
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(check, inventory))
    report = {"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "source": "GitHub global security advisories / Maven", "dependencies": results}
    (REPORT / "advisories.json").write_text(json.dumps(report, indent=2) + "\n")
    findings = [r for r in results if r["advisories"]]
    blocking = [r for r in findings if args.strict_build_tools or any(s != "build-tools" for s in r["scopes"])]
    for result in findings:
        for advisory in result["advisories"]:
            print(result["coordinate"], result["scopes"], advisory["ghsa_id"], advisory["severity"], advisory["html_url"])
    print(f"Checked {len(results)} resolved coordinates; {len(findings)} with published Maven advisories")
    print(f"APK/test coordinates with advisories: {len([r for r in findings if any(s != 'build-tools' for s in r['scopes'])])}")
    if findings and not blocking:
        print("::warning title=Build tool advisories remain::See advisories.json and docs/security.md; these are not bundled in APKs.")
    raise SystemExit(1 if blocking else 0)
