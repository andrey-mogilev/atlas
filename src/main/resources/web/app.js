"use strict";
const $ = (id) => document.getElementById(id);
const token = document.querySelector('meta[name="atlas-token"]').content;
let currentText = "";
let activeJob = null;
let displayVersion = 0;
let similarityModel = null;

const STOP_WORDS = new Set([
  "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "use", "with", "you", "your"
]);

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
function tokens(finding) {
  // Repeat name terms so concise identity words carry more weight than description prose.
  const text = `${finding.name} ${finding.name} ${finding.description}`
    .replace(/([\p{Ll}\p{N}])([\p{Lu}])/gu, "$1 $2").normalize("NFKC").toLowerCase();
  return (text.match(/[\p{L}\p{N}]+/gu) || []).filter(token => token.length > 1 && !STOP_WORDS.has(token));
}
function buildSimilarityModel(findings) {
  const counts = findings.map(finding => {
    const result = new Map();
    for (const token of tokens(finding)) result.set(token, (result.get(token) || 0) + 1);
    return result;
  });
  const documentFrequency = new Map();
  for (const terms of counts) for (const term of terms.keys()) {
    documentFrequency.set(term, (documentFrequency.get(term) || 0) + 1);
  }
  const vectors = counts.map(terms => {
    const vector = new Map();
    let squaredLength = 0;
    for (const [term, count] of terms) {
      const inverseFrequency = Math.log((findings.length + 1) / ((documentFrequency.get(term) || 0) + 1)) + 1;
      const weight = (1 + Math.log(count)) * inverseFrequency;
      vector.set(term, weight);
      squaredLength += weight * weight;
    }
    const length = Math.sqrt(squaredLength);
    if (length) for (const [term, weight] of vector) vector.set(term, weight / length);
    return vector;
  });
  return {findings, vectors};
}
function similarity(left, right) {
  const smaller = left.size <= right.size ? left : right;
  const larger = smaller === left ? right : left;
  let score = 0;
  for (const [term, weight] of smaller) score += weight * (larger.get(term) || 0);
  return Math.round(Math.min(1, Math.max(0, score)) * 100);
}
function selectFinding(selectedIndex) {
  const selected = similarityModel.findings[selectedIndex];
  const matches = similarityModel.findings.map((finding, index) => ({
    finding, index, percent: similarity(similarityModel.vectors[selectedIndex], similarityModel.vectors[index])
  })).filter(match => match.index !== selectedIndex).sort((left, right) =>
    right.percent - left.percent || (left.finding.path < right.finding.path ? -1 : left.finding.path > right.finding.path ? 1 : 0));
  document.querySelectorAll(".finding-select").forEach((button, index) => {
    button.setAttribute("aria-pressed", String(index === selectedIndex));
  });
  $("similarity-name").textContent = selected.name;
  $("similarities").replaceChildren();
  if (!matches.length) {
    const item = element("li", "similarity-empty", "There are no other skills in this scan.");
    $("similarities").append(item);
  }
  for (const match of matches) {
    const item = element("li", "similarity-item", "");
    const button = element("button", "similarity-choice", "");
    button.type = "button";
    button.append(element("span", "similarity-skill-name", match.finding.name),
      element("span", "similarity-path", match.finding.path));
    button.addEventListener("click", () => selectFinding(match.index));
    const score = element("span", "similarity-score", `${match.percent}%`);
    score.setAttribute("aria-label", `${match.percent} percent similar`);
    item.append(button, score);
    $("similarities").append(item);
  }
  $("similarity-panel").hidden = false;
  $("result-explorer").classList.add("similarity-open");
}
function closeSimilarity() {
  $("similarity-panel").hidden = true;
  $("result-explorer").classList.remove("similarity-open");
  document.querySelectorAll(".finding-select").forEach(button => button.setAttribute("aria-pressed", "false"));
}
function showResult(result) {
  $("empty-state").hidden = true;
  $("results").hidden = false;
  $("result-target").textContent = result.target;
  $("result-branch").textContent = result.branch;
  $("result-commit").textContent = result.commit;
  $("result-count").textContent = counts(result.findings.length, result.locationCount);
  $("findings").replaceChildren();
  closeSimilarity();
  similarityModel = buildSimilarityModel(result.findings);
  if (!result.findings.length) $("findings").append(element("p", "no-findings", "No SKILL.md files found in this source."));
  result.findings.forEach((finding, index) => {
    const card = element("article", "finding", "");
    const select = element("button", "finding-select", "");
    select.type = "button";
    select.setAttribute("aria-pressed", "false");
    select.setAttribute("aria-label", `Show skills similar to ${finding.name}`);
    select.append(element("span", "finding-name", finding.name), element("span", "finding-path", finding.path),
      element("span", "finding-description", finding.description.replace(/\s+/g, " ").trim() || "(none)"));
    select.addEventListener("click", () => selectFinding(index));
    card.append(select);
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
  });
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
$("close-similarity").addEventListener("click", closeSimilarity);
history();
try { const saved = sessionStorage.getItem("atlas-job"); if (saved) trackJob(saved); } catch (_) { }
