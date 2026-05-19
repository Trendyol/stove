/* Stove Flow — interactive 3-actor sequence widget.
 *
 * Usage in any MD page:
 *
 *   <div class="stove-flow" data-scenario="shouldBeConsumed"></div>
 *
 * Currently shipped scenarios:
 *   - shouldBePublished
 *   - shouldBeConsumed
 *
 * Each scenario is a list of steps. Each step animates one "packet"
 * sliding between actors and highlights the active step text.
 */
(function () {
  const SCENARIOS = {
    shouldBePublished: {
      title: "kafka { shouldBePublished<OrderCreated> { ... } }",
      caption:
        "Your app publishes to Kafka during a request. Stove sees it through the bridge interceptor and matches the predicate.",
      steps: [
        {
          from: "stove",
          to: "app",
          label: "test → app",
          payload: "POST /orders",
          tone: "request",
          note: "Test calls http { postAndExpectBody(...) }",
        },
        {
          from: "app",
          to: "kafka",
          label: "app → kafka",
          payload: "publish OrderCreated",
          tone: "produce",
          note: "Your producer publishes to order.created.v1",
        },
        {
          from: "app",
          to: "stove",
          label: "bridge → test",
          payload: "ReportPublished",
          tone: "bridge",
          note: "stove-kafka interceptor reports the publish over gRPC",
        },
        {
          from: "stove",
          to: "stove",
          label: "assertion",
          payload: "shouldBePublished<OrderCreated> ✓",
          tone: "match",
          note: "Stove matches against the predicate, test passes",
        },
      ],
    },
    shouldBeConsumed: {
      title: "kafka { shouldBeConsumed<OrderInput> { ... } }",
      caption:
        "Test publishes a message to Kafka. Your app's consumer reads it. Stove sees the consume via the bridge interceptor and the assertion passes.",
      steps: [
        {
          from: "stove",
          to: "kafka",
          label: "test → kafka",
          payload: "publish OrderInput",
          tone: "produce",
          note: "Test calls kafka { publish(\"order.input\", payload) }",
        },
        {
          from: "kafka",
          to: "app",
          label: "kafka → app",
          payload: "deliver OrderInput",
          tone: "deliver",
          note: "Your consumer reads the message from order.input",
        },
        {
          from: "app",
          to: "stove",
          label: "bridge → test",
          payload: "ReportConsumed",
          tone: "bridge",
          note: "stove-kafka interceptor reports the consume over gRPC",
        },
        {
          from: "stove",
          to: "stove",
          label: "assertion",
          payload: "shouldBeConsumed<OrderInput> ✓",
          tone: "match",
          note: "Stove matches against the predicate, test passes",
        },
      ],
    },
  };

  const STEP_MS = 1400;

  function esc(s) {
    return String(s).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
  }

  function buildShell(host, scenario) {
    host.innerHTML = `
      <div class="sf-frame">
        <div class="sf-head">
          <code class="sf-title">${esc(scenario.title)}</code>
          <div class="sf-controls">
            <button class="sf-btn sf-play" type="button">▶ Play</button>
            <button class="sf-btn sf-reset" type="button">↻ Reset</button>
            <label class="sf-loop"><input type="checkbox" class="sf-loop-toggle"> loop</label>
          </div>
        </div>
        <p class="sf-caption">${esc(scenario.caption)}</p>
        <div class="sf-stage">
          <div class="sf-actor sf-stove"><span class="sf-actor-name">Stove test</span><span class="sf-actor-sub">JVM</span></div>
          <div class="sf-actor sf-kafka"><span class="sf-actor-name">Kafka</span><span class="sf-actor-sub">broker</span></div>
          <div class="sf-actor sf-app"><span class="sf-actor-name">Your app</span><span class="sf-actor-sub">producer + consumer + bridge</span></div>
          <div class="sf-packet" aria-hidden="true"></div>
        </div>
        <ol class="sf-steps">
          ${scenario.steps
            .map(
              (s, i) =>
                `<li data-step="${i}" data-tone="${s.tone}"><span class="sf-step-num">${i + 1}</span><span class="sf-step-text"><strong>${esc(s.label)}</strong> — ${esc(s.note)}</span></li>`
            )
            .join("")}
        </ol>
      </div>
    `;
  }

  function positionPacket(packet, stage, fromActor, toActor, progress) {
    // progress 0..1, packet moves from center of `from` to center of `to`
    const stageRect = stage.getBoundingClientRect();
    const fromRect = stage.querySelector(".sf-" + fromActor).getBoundingClientRect();
    const toRect = stage.querySelector(".sf-" + toActor).getBoundingClientRect();
    const fx = fromRect.left + fromRect.width / 2 - stageRect.left;
    const fy = fromRect.top + fromRect.height / 2 - stageRect.top;
    const tx = toRect.left + toRect.width / 2 - stageRect.left;
    const ty = toRect.top + toRect.height / 2 - stageRect.top;
    const x = fx + (tx - fx) * progress;
    const y = fy + (ty - fy) * progress;
    packet.style.left = x + "px";
    packet.style.top = y + "px";
  }

  function mountOne(host) {
    if (host.dataset.sfMounted === "1") return;
    host.dataset.sfMounted = "1";

    const name = host.dataset.scenario;
    const scenario = SCENARIOS[name];
    if (!scenario) {
      host.innerHTML = `<div class="sf-error">Unknown scenario "${esc(name)}"</div>`;
      return;
    }

    buildShell(host, scenario);
    const stage = host.querySelector(".sf-stage");
    const packet = host.querySelector(".sf-packet");
    const steps = host.querySelectorAll(".sf-steps li");
    const playBtn = host.querySelector(".sf-play");
    const resetBtn = host.querySelector(".sf-reset");
    const loopToggle = host.querySelector(".sf-loop-toggle");

    let timer = null;
    let raf = null;
    let stepIndex = -1;
    let playing = false;

    function clear() {
      if (timer) clearTimeout(timer);
      if (raf) cancelAnimationFrame(raf);
      timer = null;
      raf = null;
    }

    function clearActiveStep() {
      steps.forEach((li) => li.classList.remove("active"));
      stage.querySelectorAll(".sf-actor").forEach((a) => a.classList.remove("active"));
    }

    function runStep(idx, done) {
      if (idx >= scenario.steps.length) return done();
      stepIndex = idx;
      clearActiveStep();
      steps[idx].classList.add("active");
      const step = scenario.steps[idx];
      stage.querySelector(".sf-" + step.from).classList.add("active");
      if (step.to !== step.from) stage.querySelector(".sf-" + step.to).classList.add("active");
      packet.textContent = step.payload;
      packet.dataset.tone = step.tone;
      packet.classList.add("visible");

      if (step.from === step.to) {
        positionPacket(packet, stage, step.from, step.to, 0);
        timer = setTimeout(() => {
          packet.classList.remove("visible");
          done();
        }, STEP_MS * 0.9);
        return;
      }

      const start = performance.now();
      function frame(now) {
        const p = Math.min(1, (now - start) / STEP_MS);
        positionPacket(packet, stage, step.from, step.to, p);
        if (p < 1) {
          raf = requestAnimationFrame(frame);
        } else {
          packet.classList.remove("visible");
          done();
        }
      }
      raf = requestAnimationFrame(frame);
    }

    function play(fromStart) {
      if (playing && !fromStart) return;
      playing = true;
      playBtn.textContent = "⏸ Pause";
      let i = fromStart ? 0 : Math.max(0, stepIndex + 1);
      if (fromStart) clearActiveStep();

      function next() {
        if (!playing) return;
        if (i >= scenario.steps.length) {
          playing = false;
          playBtn.textContent = "▶ Play";
          if (loopToggle.checked) {
            timer = setTimeout(() => play(true), 700);
          }
          return;
        }
        runStep(i++, () => {
          timer = setTimeout(next, 250);
        });
      }
      next();
    }

    function pause() {
      playing = false;
      clear();
      packet.classList.remove("visible");
      playBtn.textContent = "▶ Play";
    }

    function reset() {
      playing = false;
      clear();
      packet.classList.remove("visible");
      clearActiveStep();
      stepIndex = -1;
      playBtn.textContent = "▶ Play";
    }

    playBtn.addEventListener("click", () => {
      if (playing) pause();
      else if (stepIndex >= scenario.steps.length - 1) play(true);
      else play(false);
    });
    resetBtn.addEventListener("click", reset);

    // Auto-play once when widget scrolls into view
    if ("IntersectionObserver" in window) {
      const io = new IntersectionObserver(
        (entries) => {
          entries.forEach((e) => {
            if (e.isIntersecting && stepIndex === -1 && !playing) {
              play(true);
              io.unobserve(host);
            }
          });
        },
        { threshold: 0.4 }
      );
      io.observe(host);
    }
  }

  function mountAll() {
    document.querySelectorAll(".stove-flow").forEach(mountOne);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mountAll);
  } else {
    mountAll();
  }
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => {
      // Reset mount flag on instant nav
      document.querySelectorAll(".stove-flow").forEach((el) => (el.dataset.sfMounted = ""));
      mountAll();
    });
  }
})();
