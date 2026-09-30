"use strict";
const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="atlas-token"]').content;
const isScansPage = location.pathname === "/scans";
let repositories = [], activeFindings = [], similarityModel = null, selectedFinding = null, activeJob = null, currentText = "";
const STOP_WORDS = new Set(["a","an","and","are","as","at","be","by","for","from","in","is","it","of","on","or","that","the","this","to","use","with","you","your"]);

async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {...options.headers, "X-Atlas-Token": token}});
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || "The request failed. Please try again.");
  return data;
}
function message(id, text) { $(id).textContent = text; $(id).hidden = !text; }
function element(tag, className = "", text = "") { const node = document.createElement(tag); node.className = className; node.textContent = text; return node; }
function counts(skills, locations) { return `${skills} unique ${skills === 1 ? "skill" : "skills"} across ${locations} ${locations === 1 ? "location" : "locations"}`; }
function sourceLink(location) {
  const node = element(/^https?:\/\//i.test(location.link) ? "a" : "div", "source-link", location.link);
  if (node.tagName === "A") { node.href = location.link; node.target = "_blank"; node.rel = "noopener noreferrer"; }
  return node;
}
function matchesFilter(finding, query) { const q = query.trim().toLocaleLowerCase(); return !q || finding.name.toLocaleLowerCase().includes(q) || finding.description.toLocaleLowerCase().includes(q); }
function tokens(finding) {
  const text = `${finding.name} ${finding.name} ${finding.description}`.replace(/([\p{Ll}\p{N}])([\p{Lu}])/gu, "$1 $2").normalize("NFKC").toLowerCase();
  return (text.match(/[\p{L}\p{N}]+/gu) || []).filter(word => word.length > 1 && !STOP_WORDS.has(word));
}
function buildSimilarityModel(findings) {
  const termCounts = findings.map(finding => { const map = new Map(); for (const word of tokens(finding)) map.set(word, (map.get(word) || 0) + 1); return map; });
  const frequency = new Map(); for (const terms of termCounts) for (const term of terms.keys()) frequency.set(term, (frequency.get(term) || 0) + 1);
  const vectors = termCounts.map(terms => { const vector = new Map(); let squared = 0; for (const [term, count] of terms) { const weight = (1 + Math.log(count)) * (Math.log((findings.length + 1) / ((frequency.get(term) || 0) + 1)) + 1); vector.set(term, weight); squared += weight * weight; } const length = Math.sqrt(squared); if (length) for (const [term, weight] of vector) vector.set(term, weight / length); return vector; });
  return {findings, vectors};
}
function similarity(left, right) { const small = left.size <= right.size ? left : right, large = small === left ? right : left; let score = 0; for (const [term, weight] of small) score += weight * (large.get(term) || 0); return Math.round(Math.min(1, Math.max(0, score)) * 100); }
function findingKey(finding) { return `${finding.repositoryId}:${finding.id}`; }

function closeSimilarity() { selectedFinding = null; $("similarity-panel").hidden = true; $("result-explorer").classList.remove("similarity-open"); document.querySelectorAll(".finding-select").forEach(button => button.setAttribute("aria-pressed", "false")); }
function selectFinding(index) {
  const selected = similarityModel.findings[index]; selectedFinding = findingKey(selected);
  if (!matchesFilter(selected, $("skill-filter").value)) { $("skill-filter").value = ""; renderFindings(); }
  const matches = similarityModel.findings.map((finding, candidate) => ({finding, candidate, percent: similarity(similarityModel.vectors[index], similarityModel.vectors[candidate])})).filter(match => match.candidate !== index).sort((a, b) => b.percent - a.percent || a.candidate - b.candidate);
  document.querySelectorAll(".finding-select").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.findingKey === selectedFinding)));
  $("similarity-name").textContent = selected.name; $("similarities").replaceChildren();
  if (!matches.length) $("similarities").append(element("li", "similarity-empty", "There are no other skills in enabled repositories."));
  for (const match of matches) { const item = element("li", "similarity-item"), button = element("button", "similarity-choice"); button.type = "button"; button.append(element("span", "similarity-skill-name", match.finding.name), element("span", "similarity-repository", match.finding.repositoryLabel), element("span", "similarity-path", match.finding.path)); button.addEventListener("click", () => selectFinding(match.candidate)); const score = element("span", "similarity-score", `${match.percent}%`); score.setAttribute("aria-label", `${match.percent} percent similar`); item.append(button, score); $("similarities").append(item); }
  $("similarity-panel").hidden = false; $("result-explorer").classList.add("similarity-open");
}
function findingCard(finding, index, selectable = true) {
  const card = element("article", "finding"), heading = element(selectable ? "button" : "div", selectable ? "finding-select" : "finding-heading");
  if (selectable) { heading.type = "button"; heading.dataset.findingKey = findingKey(finding); heading.setAttribute("aria-pressed", String(selectedFinding === findingKey(finding))); heading.setAttribute("aria-label", `Show skills similar to ${finding.name} in ${finding.repositoryLabel}`); heading.addEventListener("click", () => selectFinding(index)); }
  heading.append(element("span", "finding-name", finding.name), element("span", "finding-repository", finding.repositoryLabel), element("span", "finding-path", finding.path), element("span", "finding-description", finding.description.replace(/\s+/g, " ").trim() || "(none)")); card.append(heading);
  if (finding.locations.length === 1) card.append(element("div", "finding-id", finding.id), sourceLink(finding)); else { const details = element("details", "locations"); details.open = finding.locations.length <= 4; details.append(element("summary", "", `${finding.locations.length} locations`)); const list = element("ul", "location-list"); for (const location of finding.locations) { const item = element("li"); item.append(element("div", "finding-path", location.path), element("div", "finding-id", location.id), sourceLink(location)); list.append(item); } details.append(list); card.append(details); }
  return card;
}
function renderFindings() {
  const query = $("skill-filter").value, visible = activeFindings.map((finding, index) => ({finding, index})).filter(item => matchesFilter(item.finding, query));
  const locations = visible.reduce((sum, item) => sum + item.finding.locations.length, 0), enabled = repositories.filter(repo => repo.enabled).length;
  $("result-count").textContent = `${enabled} ${enabled === 1 ? "repository" : "repositories"} · ${counts(visible.length, locations)}`;
  $("filter-summary").textContent = query.trim() && activeFindings.length ? `Showing ${visible.length} of ${activeFindings.length} ${activeFindings.length === 1 ? "skill" : "skills"}.` : "";
  $("findings").replaceChildren();
  if (!enabled) { $("findings").append(element("p", "no-findings", "No repositories selected.")); return; }
  if (!activeFindings.length) { $("findings").append(element("p", "no-findings", "No SKILL.md files found in the selected repositories.")); return; }
  if (!visible.length) { $("findings").append(element("p", "no-findings", "No skills match this filter.")); return; }
  for (const item of visible) $("findings").append(findingCard(item.finding, item.index));
}
function storedSelection() { try { const value = JSON.parse(localStorage.getItem("atlas-enabled-repositories") || "{}"); return value && typeof value === "object" ? value : {}; } catch (_) { return {}; } }
function persistSelection() { try { localStorage.setItem("atlas-enabled-repositories", JSON.stringify(Object.fromEntries(repositories.map(repo => [repo.canonicalUrl, repo.enabled])))); } catch (_) {} }
function updateAllCheckbox() { const all = $("all-repositories"), enabled = repositories.filter(repo => repo.enabled).length; all.checked = repositories.length > 0 && enabled === repositories.length; all.indeterminate = enabled > 0 && enabled < repositories.length; }
async function refreshResults() {
  const previous = selectedFinding, ids = repositories.filter(repo => repo.enabled).map(repo => repo.id);
  try { const data = await api(`/api/repository-results?ids=${ids.join(",")}`); activeFindings = data.repositories.flatMap(repo => repo.result.findings.map(finding => ({...finding, repositoryId: repo.id, repositoryLabel: repo.label, repositoryUrl: repo.canonicalUrl}))); similarityModel = buildSimilarityModel(activeFindings); if (previous) { const index = activeFindings.findIndex(finding => findingKey(finding) === previous); if (index >= 0) selectFinding(index); else closeSimilarity(); } else closeSimilarity(); renderFindings(); } catch (error) { message("repositories-error", error.message); }
}
function repositoryRow(repo, duplicateLabels) {
  const row = element("div", "repository-row"), label = element("label", "repository-check"), checkbox = document.createElement("input"); checkbox.type = "checkbox"; checkbox.checked = repo.enabled; checkbox.addEventListener("change", async () => { repo.enabled = checkbox.checked; persistSelection(); updateAllCheckbox(); await refreshResults(); });
  const text = element("span", "repository-info"); text.append(element("strong", "", repo.label), element("span", "", `${repo.branch} · ${new Date(repo.scannedAt).toLocaleString()}`), element("span", "", counts(repo.skillCount, repo.locationCount))); if (duplicateLabels.has(repo.label)) text.append(element("span", "repository-url", repo.canonicalUrl)); label.append(checkbox, text); row.append(label); return row;
}
async function loadRepositories() {
  try { const data = await api("/api/repositories"), saved = storedSelection(), known = new Set(Object.keys(saved)); repositories = data.map(repo => ({...repo, enabled: known.has(repo.canonicalUrl) ? saved[repo.canonicalUrl] !== false : true})); persistSelection(); message("repositories-error", ""); $("repository-selector").replaceChildren();
    if (!repositories.length) { $("skills-empty").hidden = false; $("results").hidden = true; $("repository-selector").append(element("p", "history-empty", "No repositories have saved scans.")); $("result-count").textContent = ""; return; }
    $("skills-empty").hidden = true; $("results").hidden = false; const allLabel = element("label", "repository-check all-repositories"), all = document.createElement("input"); all.id = "all-repositories"; all.type = "checkbox"; all.addEventListener("change", async () => { repositories.forEach(repo => repo.enabled = all.checked); document.querySelectorAll(".repository-row input").forEach(input => input.checked = all.checked); persistSelection(); updateAllCheckbox(); await refreshResults(); }); allLabel.append(all, element("strong", "", "All repositories")); $("repository-selector").append(allLabel); const duplicateLabels = new Set(repositories.filter((repo, i) => repositories.some((other, j) => i !== j && repo.label === other.label)).map(repo => repo.label)); repositories.forEach(repo => $("repository-selector").append(repositoryRow(repo, duplicateLabels))); updateAllCheckbox(); await refreshResults();
  } catch (error) { message("repositories-error", error.message); }
}

function rememberJob(id) { try { id ? sessionStorage.setItem("atlas-job", id) : sessionStorage.removeItem("atlas-job"); } catch (_) {} }
async function inspectScan(scan, button) { try { const result = await api(`/api/history/${scan.id}`); document.querySelectorAll(".history-item").forEach(item => item.removeAttribute("aria-current")); button.setAttribute("aria-current", "true"); $("detail-empty").hidden = true; $("detail").hidden = false; $("detail-target").textContent = result.target; $("detail-branch").textContent = result.branch; $("detail-commit").textContent = result.commit; $("detail-count").textContent = counts(result.findings.length, result.locationCount); $("detail-findings").replaceChildren(); result.findings.forEach((finding, index) => $("detail-findings").append(findingCard({...finding, repositoryId: scan.repositoryId, repositoryLabel: scan.label}, index, false))); currentText = result.text; $("plain-output").textContent = currentText; $("copy-output").textContent = "Copy output"; message("error", ""); } catch (error) { message("error", error.message); } }
function scanButton(scan, label) { const button = element("button", "history-item"); button.type = "button"; button.append(element("strong", "", label), element("span", "", `${scan.branch} · ${counts(scan.skillCount, scan.locationCount)}`), element("span", "", new Date(scan.scannedAt).toLocaleString())); button.addEventListener("click", () => inspectScan(scan, button)); return button; }
async function history() { try { const [scans, repos] = await Promise.all([api("/api/history"), api("/api/repositories")]), labels = new Map(repos.map(repo => [repo.id, repo.label])); message("history-error", ""); $("history").replaceChildren(); if (!scans.length) { $("history").append(element("p", "history-empty", "No saved scans yet. Your first scan will appear here.")); return; } const groups = new Map(); for (const scan of scans) { scan.label = labels.get(scan.repositoryId) || scan.canonicalUrl; if (!groups.has(scan.repositoryId)) groups.set(scan.repositoryId, []); groups.get(scan.repositoryId).push(scan); } for (const repo of repos) { const group = groups.get(repo.id); if (!group) continue; const section = element("section", "history-group"), heading = element("h3", "", group[0].label); section.append(heading, scanButton(group[0], "Latest scan")); if (group.length > 1) { const older = element("details", "older-scans"); older.append(element("summary", "", `${group.length - 1} older ${group.length === 2 ? "scan" : "scans"}`)); group.slice(1).forEach(scan => older.append(scanButton(scan, scan.branch))); section.append(older); } $("history").append(section); } } catch (error) { message("history-error", error.message); } }
async function poll(id) { try { const job = await api(`/api/jobs/${id}`); if (activeJob !== id) return; if (job.status === "running") { setTimeout(() => poll(id), 750); return; } activeJob = null; rememberJob(null); $("scan-button").disabled = false; $("scan-button").textContent = "Scan source ↗"; if (job.status === "completed") { message("status", "Scan complete. Results saved on this computer."); await history(); await loadRepositories(); } else { message("status", ""); message("error", `Scan failed (code ${job.code}): ${job.message}`); } } catch (error) { activeJob = null; rememberJob(null); $("scan-button").disabled = false; $("scan-button").textContent = "Scan source ↗"; message("status", ""); message("error", `${error.message}. Refresh saved scans to check for results.`); } }
function trackJob(id) { activeJob = id; rememberJob(id); $("scan-button").disabled = true; $("scan-button").textContent = "Scanning…"; message("status", "Scanning source… You can browse saved scans while you wait."); poll(id); }

$("skills-page").hidden = isScansPage; $("scans-page").hidden = !isScansPage; $(isScansPage ? "scans-nav" : "skills-nav").setAttribute("aria-current", "page");
if (isScansPage) { $("page-heading").textContent = "Scan and inspect repositories."; $("page-intro").textContent = "Create scans and browse saved results by repository."; $("scan-form").addEventListener("submit", async event => { event.preventDefault(); message("error", ""); message("status", "Starting scan…"); $("scan-button").disabled = true; try { const body = new URLSearchParams({target: $("target").value, branch: $("branch").value}), job = await api("/api/scans", {method: "POST", body}); trackJob(job.id); } catch (error) { $("scan-button").disabled = false; message("status", ""); message("error", error.message); } }); $("copy-output").addEventListener("click", async () => { try { await navigator.clipboard.writeText(currentText); $("copy-output").textContent = "Copied"; } catch (_) { $("copy-output").textContent = "Select the output below to copy"; } }); $("refresh-history").addEventListener("click", history); history(); try { const saved = sessionStorage.getItem("atlas-job"); if (saved) trackJob(saved); } catch (_) {} }
else { $("close-similarity").addEventListener("click", closeSimilarity); $("skill-filter").addEventListener("input", renderFindings); $("refresh-repositories").addEventListener("click", loadRepositories); loadRepositories(); }
