/* agentd live updates. No HTML is ever built from event JSON: on each
 * SSE event the script fetches server-rendered maud fragments
 * (/tasks/:id/timeline?after=seq, /panel, /cards) and swaps them in. */

(function () {
  "use strict";

  // Disable a form's buttons on submit (double-click guard; the server also
  // refuses double approves via claim_approve).
  document.addEventListener("submit", function (ev) {
    var form = ev.target;
    if (!form || form.tagName !== "FORM") return;
    var btns = form.querySelectorAll("button, input[type=submit]");
    for (var i = 0; i < btns.length; i++) btns[i].disabled = true;
  });

  function nearBottom() {
    var el = document.documentElement;
    return el.scrollHeight - el.scrollTop - el.clientHeight < 240;
  }

  function maxSeqIn(root, attr) {
    var max = null;
    var nodes = root.querySelectorAll("[data-seq]");
    for (var i = 0; i < nodes.length; i++) {
      var s = parseInt(nodes[i].getAttribute("data-seq"), 10);
      if (!isNaN(s) && (max === null || s > max)) max = s;
    }
    return max;
  }

  // ---- task page live timeline ----
  var timeline = document.getElementById("timeline");
  if (timeline && typeof EventSource !== "undefined") {
    var taskId = timeline.getAttribute("data-task-id");
    var lastSeq = parseInt(timeline.getAttribute("data-after") || "-1", 10);
    var panel = document.getElementById("panel");
    var cards = document.getElementById("cards");
    var backoff = 1000;
    var source = null;
    var refreshing = false;

    function refreshFragments() {
      if (refreshing) return;
      refreshing = true;
      var stick = nearBottom();
      var jobs = [];
      jobs.push(
        fetch("/tasks/" + taskId + "/timeline?after=" + lastSeq, { credentials: "same-origin" })
          .then(function (r) { return r.text(); })
          .then(function (html) {
            if (!html) return;
            var tmp = document.createElement("div");
            tmp.innerHTML = html;
            while (tmp.firstChild) timeline.appendChild(tmp.firstChild);
            var m = maxSeqIn(timeline, "data-seq");
            if (m !== null && m > lastSeq) lastSeq = m;
          })
      );
      if (panel) {
        jobs.push(
          fetch("/tasks/" + taskId + "/panel", { credentials: "same-origin" })
            .then(function (r) { return r.text(); })
            .then(function (html) { if (html) panel.innerHTML = html; })
        );
      }
      if (cards) {
        jobs.push(
          fetch("/tasks/" + taskId + "/cards", { credentials: "same-origin" })
            .then(function (r) { return r.text(); })
            .then(function (html) { if (html) cards.innerHTML = html; })
        );
      }
      Promise.all(jobs).then(function () {
        refreshing = false;
        if (stick) window.scrollTo(0, document.documentElement.scrollHeight);
      }).catch(function () {
        refreshing = false;
      });
    }

    function connect() {
      if (source) { try { source.close(); } catch (e) {} }
      source = new EventSource("/tasks/" + taskId + "/events?after=" + lastSeq);
      source.onopen = function () { backoff = 1000; };
      source.onmessage = function () {
        // The event JSON only signals "something new"; the markup always
        // comes from the server-rendered fragments below.
        backoff = 1000;
        refreshFragments();
      };
      source.onerror = function () {
        try { source.close(); } catch (e) {}
        var wait = Math.min(backoff, 30000);
        backoff = Math.min(backoff * 2, 30000);
        setTimeout(connect, wait);
      };
    }
    connect();
  }

  // ---- dashboard live refresh (every 10s) ----
  var dash = document.getElementById("dashboard");
  if (dash) {
    setInterval(function () {
      if (document.hidden) return;
      fetch("/fragments/dashboard", { credentials: "same-origin" })
        .then(function (r) { return r.text(); })
        .then(function (html) {
          if (!html) return;
          var tmp = document.createElement("div");
          tmp.innerHTML = html;
          var title = tmp.querySelector("[data-doc-title]");
          if (title) document.title = title.getAttribute("data-doc-title");
          var body = tmp.querySelector("[data-dash-body]");
          if (body) dash.innerHTML = body.innerHTML;
        })
        .catch(function () {});
    }, 10000);
  }
})();
