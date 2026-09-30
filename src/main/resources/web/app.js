"use strict";
const $ = (id) => document.getElementById(id);
const token = document.querySelector('meta[name="atlas-token"]').content;
let currentText = "";
let activeJob = null;
let displayVersion = 0;

async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {...options.headers, "X-Atlas-Token": token}});
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || "The request failed. Please try again.");
  return data;
}
function message(id, text) { $(id).textContent = text; $(id).hidden = !text; }
function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text;
  return node;
}
function counts(skills, locations) {
  return `${skills} unique ${skills === 1 ? "skill" : "skills"} across ${locations} ${locations === 1 ? "location" : "locations"}`;
}
function sourceLink(location) {
  // Local files and SSH/Git URLs remain text; only web URLs become links.
  const link = element(/^https?:\/\//i.test(location.link) ? "a" : "div", "source-link", location.link);
  if (link.tagName === "A") {
    link.href = location.link;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
  }
  return link;
}
function showResult(result) {
  $("empty-state").hidden = true;
  $("results").hidden = false;
  $("result-target").textContent = result.target;
  $("result-branch").textContent = result.branch;
  $("result-commit").textContent = result.commit;
  $("result-count").textContent = counts(result.findings.length, result.locationCount);
  $("findings").replaceChildren();
  if (!result.findings.length) $("findings").append(element("p", "no-findings", "No SKILL.md files found in this source."));
  for (const finding of result.findings) {
    const card = element("article", "finding", "");
    card.append(element("div", "finding-path", finding.path),
      element("p", "finding-description", finding.description.replace(/\s+/g, " ").trim() || "(none)"));
    if (finding.locations.length === 1) {
      card.append(element("div", "finding-id", finding.id), sourceLink(finding));
    } else {
      const locations = element("details", "locations", "");
      locations.open = finding.locations.length <= 4;
      locations.append(element("summary", "", `${finding.locations.length} locations`));
      const list = element("ul", "location-list", "");
      for (const location of finding.locations) {
        const item = element("li", "", "");
        item.append(element("div", "finding-path", location.path),
          element("div", "finding-id", location.id), sourceLink(location));
        list.append(item);
      }
      locations.append(list);
      card.append(locations);
    }
    $("findings").append(card);
  }
  currentText = result.text;
  $("plain-output").textContent = currentText;
  $("copy-output").textContent = "Copy output";
}
async function history() {
  try {
    const scans = await api("/api/history");
    message("history-error", "");
    $("history").replaceChildren();
    if (!scans.length) $("history").append(element("p", "history-empty", "No saved scans yet. Your first scan will appear here."));
    for (const scan of scans) {
      const button = element("button", "history-item", "");
      button.type = "button";
      button.append(element("strong", "", scan.target), element("span", "", `${scan.branch} · ${counts(scan.skillCount, scan.locationCount)}`),
        element("span", "", new Date(scan.scannedAt).toLocaleString()));
      button.addEventListener("click", async () => {
        const version = ++displayVersion;
        try {
          const result = await api(`/api/history/${scan.id}`);
          if (version !== displayVersion) return;
          showResult(result);
          message("error", "");
          document.querySelectorAll(".history-item").forEach(item => item.removeAttribute("aria-current"));
          button.setAttribute("aria-current", "true");
        } catch (error) { message("error", error.message); }
      });
      $("history").append(button);
    }
  } catch (error) { message("history-error", error.message); }
}
function rememberJob(id) {
  // A browser with storage disabled can still scan; reload recovery is optional.
  try { id ? sessionStorage.setItem("atlas-job", id) : sessionStorage.removeItem("atlas-job"); } catch (_) { }
}
async function poll(id) {
  try {
    const job = await api(`/api/jobs/${id}`);
    if (activeJob !== id) return;
    if (job.status === "running") { setTimeout(() => poll(id), 750); return; }
    activeJob = null;
    rememberJob(null);
    $("scan-button").disabled = false;
    $("scan-button").textContent = "Scan source ↗";
    if (job.status === "completed") {
      ++displayVersion;
      showResult(job.result);
      message("status", "Scan complete. Results saved on this computer.");
      await history();
    } else {
      message("status", "");
      message("error", `Scan failed (code ${job.code}): ${job.message}`);
    }
  } catch (error) {
    activeJob = null;
    rememberJob(null);
    $("scan-button").disabled = false;
    $("scan-button").textContent = "Scan source ↗";
    message("status", "");
    message("error", `${error.message}. Refresh history to check for saved results.`);
  }
}
function trackJob(id) {
  activeJob = id;
  rememberJob(id);
  $("scan-button").disabled = true;
  $("scan-button").textContent = "Scanning…";
  message("status", "Scanning source… This may take a few minutes. You can browse saved scans while you wait.");
  poll(id);
}
$("scan-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  message("error", "");
  message("status", "Starting scan…");
  $("scan-button").disabled = true;
  try {
    const body = new URLSearchParams({target: $("target").value, branch: $("branch").value});
    const job = await api("/api/scans", {method: "POST", body});
    trackJob(job.id);
  } catch (error) {
    $("scan-button").disabled = false;
    message("status", "");
    message("error", error.message);
  }
});
$("copy-output").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText(currentText); $("copy-output").textContent = "Copied"; }
  catch (_) { $("copy-output").textContent = "Select the output below to copy"; }
});
$("refresh-history").addEventListener("click", history);
history();
try { const saved = sessionStorage.getItem("atlas-job"); if (saved) trackJob(saved); } catch (_) { }
