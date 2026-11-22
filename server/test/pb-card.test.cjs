const { JSDOM } = require("jsdom");
const fs = require("fs");
const KT = process.argv[2];
const src = fs.readFileSync(KT, "utf8").split("\n").filter((l) => /^\s*sb\.append\(".*\\n"\)$/.test(l));
const start = src.findIndex((l) => l.includes("window.__pbc=window.__pbc||{};"));
const js = src.slice(start).map((l) => l.trim().slice(11, -4).replace(/\\(\\|"|\$)/g, "$1")).join("\n");
const CARD = `<div class='pbcard pbidle'><div class='pbhead'><span class='pbglobe'>🌐</span><b class='pbtitle'>Browser</b><span class='pblive'></span><span class='pbstage'></span><span class='pbchev'>⌃</span></div><div class='pbbody'><div class='pbsteps'></div><div class='pbnote'></div><div class='pbfoot'><img class='pbthumb'><span class='pbfm'><b class='pbfootmsg'></b><span class='pburl'></span></span><a class='pbopen' href="terminus://browser">Open view ↗</a></div></div></div>`;
let fails = 0; const ok = (c, m) => { if (!c) { fails++; console.log("FAIL", m); } else console.log("ok  ", m); };
function page(store, nCards = 1) {
  const dom = new JSDOM(`<body>${CARD.repeat(nCards)}</body>`, { runScripts: "outside-only", url: "https://x.test/" });
  const w = dom.window;
  if (store === "throw") Object.defineProperty(w, "sessionStorage", { get() { throw new Error("SecurityError"); } });
  else if (store) w.sessionStorage.setItem("pbui", JSON.stringify(store));
  w.eval(js);
  return w;
}
const shot = (at, t = "shot") => ({ img: "AAAA" + at, t, at });
const st = (id, s, t, sh = [], extra = {}) => ({ id, s, t, ms: 1500, d: "", at: id, sh, ...extra });
const state = (steps, o = {}) => ({ status: "Working", live: true, url: "https://a.com/x", thumb: "", n: steps.length, note: "", steps, ...o });

// 1. identity + no image reassign + scroll preserved
let w = page(null), d = () => w.document.querySelector(".pbsteps");
w.__pb(state([st(0, "o", "Browser ready"), st(1, "r", "Click", [shot(10)])]));
const rows1 = [...d().children], img1 = w.document.querySelector("figure img");
let srcSets = 0; const desc = Object.getOwnPropertyDescriptor(w.HTMLImageElement.prototype, "src");
Object.defineProperty(img1, "src", { set(v) { srcSets++; desc.set.call(this, v); }, get() { return desc.get.call(this, v); } });
const film = w.document.querySelector(".pbfilm"); film.scrollLeft = 77;
w.__pb(state([st(0, "o", "Browser ready"), st(1, "o", "Click", [shot(10)]), st(2, "r", "Type", [])]));
const rows2 = [...d().children];
ok(rows2[0] === rows1[0] && rows2[1] === rows1[1], "existing step nodes are reused, not rebuilt");
ok(rows2.length === 3, "new step appended");
ok(w.document.querySelector("figure img") === img1 && srcSets === 0, "filmstrip img kept, src not reassigned");
ok(w.document.querySelector(".pbfilm") === film && film.scrollLeft === 77, "filmstrip scroll position preserved");
ok(rows2[1].querySelector(".pbi").className === "pbi o", "step state updated in place");
// new shot added: old figure kept, scroll kept
w.__pb(state([st(0, "o", "Browser ready"), st(1, "o", "Click", [shot(10), shot(20)]), st(2, "r", "Type", [])]));
ok(w.document.querySelectorAll("figure").length === 2 && w.document.querySelectorAll("figure img")[0] === img1 && film.scrollLeft === 77, "adding a shot keeps old figure + scroll");
// identical push = no DOM mutation
let muts = 0; const mo = new w.MutationObserver((r) => muts += r.length); mo.observe(w.document.body, { subtree: true, childList: true, attributes: true, characterData: true });
w.__pb(state([st(0, "o", "Browser ready"), st(1, "o", "Click", [shot(10), shot(20)]), st(2, "r", "Type", [])]));
mo.takeRecords();
ok(mo.takeRecords().length === 0 && muts === 0, "identical state causes zero DOM mutations");
// 2. step order / windowing / more-marker
w.__pb(state([st(5, "o", "E"), st(6, "o", "F")], { n: 6 }));
ok([...d().children].map((e) => e.className).join() === "pbmore,pbstep,pbstep" && d().querySelector(".pbmore").textContent === "+4 earlier steps", "window slide: marker first, old steps removed");
ok(d().querySelector(".pbnum").textContent === "5", "numbering uses base offset");
// 3. expand/collapse: default, toggle persists across pushes
w = page(null);
w.__pb(state([st(1, "o", "A", [shot(1)]), st(2, "o", "B", [shot(2)])]));
let rr = [...w.document.querySelectorAll(".pbstep")];
ok(!rr[0].classList.contains("pbo") && rr[1].classList.contains("pbo"), "default: only latest shot step open");
rr[0].querySelector(".pbrow").onclick();
w.__pb(state([st(1, "o", "A", [shot(1)]), st(2, "o", "B", [shot(2)])]));
rr = [...w.document.querySelectorAll(".pbstep")];
ok(rr[0].classList.contains("pbo"), "user-expanded step stays expanded after next push");
w.document.querySelector(".pbhead").click();
ok(w.document.querySelector(".pbcard").classList.contains("pbc"), "card collapses on header click");
w.__pb(state([st(1, "o", "A", [shot(1)]), st(2, "o", "B", [shot(2)])]));
ok(w.document.querySelector(".pbcard").classList.contains("pbc"), "collapsed card stays collapsed after push");
const saved = JSON.parse(w.sessionStorage.getItem("pbui"));
// 4. page reload: state restored from sessionStorage
const w2 = page(saved);
w2.__pb(state([st(1, "o", "A", [shot(1)]), st(2, "o", "B", [shot(2)])]));
ok(w2.document.querySelector(".pbcard").classList.contains("pbc"), "RELOAD: collapsed card restored");
ok(w2.document.querySelectorAll(".pbstep")[0].classList.contains("pbo"), "RELOAD: expanded step restored");
// 5. storage unavailable: still works
const w3 = page("throw");
w3.__pb(state([st(1, "o", "A", [shot(1)])]));
w3.document.querySelector(".pbhead").click();
ok(w3.document.querySelector(".pbcard").classList.contains("pbc"), "no storage: toggling still works in memory");
// 6. stale cards: older card collapses/ends, newest live; null clears
const w4 = page(null, 2);
w4.__pb(state([st(0, "o", "Browser ready")]));
const [c1, c2] = w4.document.querySelectorAll(".pbcard");
ok(c1.classList.contains("pbidle") && c1.classList.contains("pbc") && c1.querySelector(".pbstage").textContent === "Ended", "older card is ended + collapsed (Open view hidden by pbc)");
ok(!c2.classList.contains("pbidle") && c2.querySelector(".pbstage").textContent === "In progress · 1 step" && c2.querySelector(".pblive").textContent === "Live", "newest card live");
w4.__pb(state([st(0, "o", "Browser ready")], { live: false, status: "Done" }));
ok(c2.querySelector(".pbfootmsg").textContent === "Session ended" && c2.querySelector(".pbstage").textContent === "Done · 1 step", "ended session text");
w4.__pb(null);
ok(c2.classList.contains("pbidle") && c2.querySelector(".pbsteps").children.length === 0, "null state idles the last card");
// 7. thumb src only changes when data changes
const w5 = page(null); let ts = 0; const th = w5.document.querySelector(".pbthumb");
const d2 = Object.getOwnPropertyDescriptor(w5.HTMLImageElement.prototype, "src"); Object.defineProperty(th, "src", { set(v) { ts++; d2.set.call(this, v); }, get() { return d2.get.call(this, v); } });
w5.__pb(state([st(0, "o", "x")], { thumb: "T1" })); w5.__pb(state([st(0, "o", "x")], { thumb: "T1" })); w5.__pb(state([st(0, "o", "x")], { thumb: "T2" }));
ok(ts === 2, "thumb set twice for two distinct images (" + ts + ")");
// 8. detail/ms add and remove
const w6 = page(null);
w6.__pb(state([st(1, "x", "Click", [], { d: "STALE_REF", ms: 0 })]));
ok(w6.document.querySelector(".pbtx small").textContent === "STALE_REF" && !w6.document.querySelector(".pbrow em"), "detail shown, no ms");
w6.__pb(state([st(1, "o", "Click", [], { d: "", ms: 2500 })]));
ok(!w6.document.querySelector(".pbtx small") && w6.document.querySelector(".pbrow em").textContent === "2.5s", "detail removed, ms shown");
console.log(fails ? `${fails} FAILED` : "ALL PASSED");
