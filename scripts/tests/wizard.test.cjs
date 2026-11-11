// Run after `mkdocs build --strict`: node --test scripts/tests/wizard.test.cjs
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const root = resolve(__dirname, '../..');
const data = JSON.parse(readFileSync(resolve(root, 'docs/assets/data/setup.json'), 'utf8'));

function wizard(search = '') {
  const host = { dataset: {} };
  const context = {
    window: {}, URLSearchParams,
    location: { search, origin: 'https://stove.test', pathname: '/wizard/' },
    document: {
      readyState: 'complete',
      getElementById: () => host,
      querySelectorAll: () => [],
    },
  };
  vm.runInNewContext(readFileSync(resolve(root, 'docs/js/wizard.js'), 'utf8'), context);
  const app = context.window.wizardApp();
  app.data = data;
  app.loadFromUrl();
  return app;
}

test('Cucumber glue, feature, suite and task agree on discovery', () => {
  const app = wizard('?test=cucumber&pkg=org.example.inventory');
  const build = app.renderGradle();
  assert.match(build, /testImplementation\("com.trendyol:stove-extensions-cucumber"\)/);
  assert.match(build, /testRuntimeOnly\("org.junit.platform:junit-platform-launcher:/);
  assert.match(build, /sourceSets.create\("test-e2e"\)/);
  assert.match(build, /testClassesDirs = e2eSourceSet.output.classesDirs/);
  assert.match(build, /classpath = e2eSourceSet.runtimeClasspath/);
  assert.match(build, /includeEngines\("junit-platform-suite"\)/);
  assert.doesNotMatch(build, /includeEngines\("cucumber"\)/);
  assert.match(app.renderCucumberFile('suite'), /@SelectPackages\("features"\)/);
  assert.match(app.renderCucumberFile('suite'), /@IncludeEngines\("cucumber"\)/);
  const props = app.renderCucumberFile('properties');
  assert.match(props, /cucumber.glue=org.example.inventory.e2e,com.trendyol.stove.extensions.cucumber/);
  assert.match(props, /cucumber.execution.parallel.enabled=true/);
  assert.match(props, /cucumber.glue.hint.enabled=false/);
  const expression = app.renderSampleTest().match(/Then\("([^"]+)"\)/)[1];
  assert.ok(app.renderCucumberFile('feature').includes(`Then ${expression}`));
  assert.equal(app.renderKotestProps(), null);
  for (const code of [build, app.renderStoveConfig(), app.renderSampleTest(), app.renderCucumberFile('suite')]) {
    assert.doesNotMatch(code, /undefined|\{pkg\}|\{body\}|AbstractProjectConfig|BaseE2ETest/);
  }
});

test('Cucumber reuses system operations across every runtime without a stove wrapper', () => {
  for (const runtime of ['jvm', 'process', 'container', 'provided']) {
    const app = wizard(`?test=cucumber&rt=${runtime}`);
    const config = app.renderStoveConfig();
    const steps = app.renderSampleTest();
    assert.match(config, /@BeforeAll\n  fun start\(\): Unit = runBlocking/);
    assert.match(config, /@AfterAll\n  fun stop\(\) = Stove.stop\(\)/);
    assert.match(steps, /class SampleSteps : StoveSteps\(\{/);
    assert.doesNotMatch(steps, /\bstove\s*\{/);
    assert.match(steps, /import com.trendyol.stove.http.http\n/);
    for (const entry of app.activeEntries().filter(e => e.testDsl)) {
      const unindent = code => code.split('\n').map(line => line.trim()).join('\n');
      assert.ok(unindent(steps).includes(unindent(entry.testDsl)), entry.id);
    }
    if (runtime === 'provided') {
      assert.match(config, /ProvidedApplicationOptions/);
      assert.doesNotMatch(config + steps, /wiremock|bridge\s*\{/);
    }
  }
});

test('framework switching preserves existing templates and removes Cucumber files', () => {
  const app = wizard('?test=cucumber');
  for (const style of ['kotest', 'junit']) {
    app.state.test = style;
    assert.equal(app.renderCucumberFile('feature'), null);
    assert.doesNotMatch(app.renderGradle(), /cucumber|junit-platform-suite/);
    assert.match(app.renderSampleTest(), /\bstove\s*\{/);
    assert.match(app.renderSampleTest(), style === 'kotest' ? /FunSpec/ : /BaseE2ETest/);
  }
});

test('shared links restore Cucumber and selected systems', () => {
  const app = wizard('?test=cucumber&sys=sys.http,sys.redis&pkg=org.example&step=6');
  const restored = wizard(new URL(app.shareUrl()).search);
  assert.equal(restored.state.test, 'cucumber');
  assert.equal(restored.renderSampleTest(), app.renderSampleTest());
  assert.equal(restored.renderCucumberFile('properties'), app.renderCucumberFile('properties'));
  assert.equal(restored.step, 6);
});
