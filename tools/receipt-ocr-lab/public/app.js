const modelSelect = document.getElementById("model-select");
const fileInput = document.getElementById("file-input");
const dropzone = document.getElementById("dropzone");
const grid = document.getElementById("grid");
const cardTemplate = document.getElementById("card-template");
const summaryEl = document.getElementById("summary");
const summaryCount = document.getElementById("summary-count");
const summaryCost = document.getElementById("summary-cost");
const summaryTime = document.getElementById("summary-time");

let batch = null; // { total, done, costUsd, maxLatencyMs }

async function loadProviders() {
  const res = await fetch("/api/providers");
  const providers = await res.json();
  modelSelect.innerHTML = providers.map((p) => `<option value="${p.id}">${p.label}</option>`).join("");
}

function formatUsd(n) {
  if (n == null) return "—";
  return n < 0.01 ? `$${n.toFixed(4)}` : `$${n.toFixed(3)}`;
}

function formatMs(ms) {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function centsToDisplay(subunits, currency) {
  if (subunits == null) return "—";
  const amount = (subunits / 100).toFixed(2);
  return `${currency ?? ""} ${amount}`.trim();
}

function buildExtractTable(receipt) {
  const { currency, items, tax_subunits, gratuity_subunits, tip_subunits, discount_subunits, detected_total_subunits } = receipt;

  const itemRows = (items ?? [])
    .map(
      (it) => `<tr>
        <td class="qty">${it.quantity}×</td>
        <td>${escapeHtml(it.label)}</td>
        <td class="amt">${centsToDisplay(it.line_total_subunits, currency)}</td>
      </tr>`
    )
    .join("");

  const extras = [
    ["Tax", tax_subunits],
    ["Gratuity", gratuity_subunits],
    ["Tip", tip_subunits],
    ["Discount", discount_subunits ? -discount_subunits : 0],
  ]
    .filter(([, v]) => v)
    .map(
      ([label, v]) => `<tr class="extras"><td></td><td>${label}</td><td class="amt">${centsToDisplay(v, currency)}</td></tr>`
    )
    .join("");

  const itemSum = (items ?? []).reduce((s, it) => s + (it.line_total_subunits ?? 0), 0);
  const computedTotal =
    itemSum + (tax_subunits ?? 0) + (gratuity_subunits ?? 0) + (tip_subunits ?? 0) - (discount_subunits ?? 0);
  const detected = detected_total_subunits ?? 0;
  const diff = Math.abs(computedTotal - detected);
  const reconcileLine =
    diff <= 1
      ? `<div class="reconcile-ok">✓ Reconciles with printed total ${centsToDisplay(detected, currency)}</div>`
      : `<div class="reconcile-warn">⚠ Computed ${centsToDisplay(computedTotal, currency)} vs printed ${centsToDisplay(detected, currency)} (off by ${centsToDisplay(diff, currency)})</div>`;

  return `<table>${itemRows}${extras}<tr class="total"><td></td><td>Total</td><td class="amt">${centsToDisplay(computedTotal, currency)}</td></tr></table>${reconcileLine}`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function updateSummary() {
  if (!batch) return;
  summaryEl.hidden = false;
  summaryCount.textContent = `${batch.done}/${batch.total} processed`;
  summaryCost.textContent = `total cost ${formatUsd(batch.costUsd)}`;
  summaryTime.textContent = batch.done === batch.total ? `slowest ${formatMs(batch.maxLatencyMs)}` : "running…";
}

async function processFile(file, providerId) {
  const card = cardTemplate.content.firstElementChild.cloneNode(true);
  grid.appendChild(card);

  const img = card.querySelector(".card-image");
  const objectUrl = URL.createObjectURL(file);
  img.src = objectUrl;

  const statusEl = card.querySelector(".card-status");
  const statusText = card.querySelector(".status-text");
  const bodyEl = card.querySelector(".card-body");
  const errorEl = card.querySelector(".card-error");

  statusText.textContent = "Processing…";

  const form = new FormData();
  form.append("file", file);
  form.append("provider", providerId);

  const clientStart = performance.now();
  try {
    const res = await fetch("/api/extract", { method: "POST", body: form });
    const result = await res.json();
    const clientLatency = Math.round(performance.now() - clientStart);
    const latencyMs = result.latencyMs ?? clientLatency;

    batch.done += 1;
    batch.maxLatencyMs = Math.max(batch.maxLatencyMs, latencyMs);
    if (result.cost) batch.costUsd += result.cost;
    updateSummary();

    statusEl.classList.add("hidden");

    if (!result.ok) {
      errorEl.hidden = false;
      errorEl.textContent = result.error ?? "extraction failed";
      return;
    }

    bodyEl.hidden = false;
    card.querySelector(".metric.time").textContent = formatMs(latencyMs);
    card.querySelector(".metric.cost").textContent = formatUsd(result.cost);
    card.querySelector(".card-extract").innerHTML = buildExtractTable(result.receipt);
  } catch (e) {
    batch.done += 1;
    updateSummary();
    statusEl.classList.add("hidden");
    errorEl.hidden = false;
    errorEl.textContent = e.message ?? String(e);
  }
}

function handleFiles(fileList) {
  const files = Array.from(fileList);
  if (!files.length) return;

  grid.innerHTML = "";
  batch = { total: files.length, done: 0, costUsd: 0, maxLatencyMs: 0 };
  updateSummary();

  const providerId = modelSelect.value;
  // Fire every extraction in parallel — the browser's own connection limit
  // caps real concurrency, and each card updates independently as its own
  // request resolves, so slow images never block fast ones.
  files.forEach((file) => processFile(file, providerId));
}

fileInput.addEventListener("change", (e) => handleFiles(e.target.files));

dropzone.addEventListener("dragover", (e) => {
  e.preventDefault();
  dropzone.classList.add("dragover");
});
dropzone.addEventListener("dragleave", () => dropzone.classList.remove("dragover"));
dropzone.addEventListener("drop", (e) => {
  e.preventDefault();
  dropzone.classList.remove("dragover");
  handleFiles(e.dataTransfer.files);
});

loadProviders();
