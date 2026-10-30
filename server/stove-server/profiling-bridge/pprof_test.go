package main

import (
	profilev1 "github.com/grafana/pyroscope/api/gen/proto/go/google/v1"
	"testing"
)

func fixture() *profilev1.Profile {
	return &profilev1.Profile{
		StringTable: []string{"", "cpu", "nanoseconds", "app.root", "main.go", "app.inline", "request", "checkout", "size", "bytes"},
		SampleType:  []*profilev1.ValueType{{Type: 1, Unit: 2}},
		Function:    []*profilev1.Function{{Id: 1, Name: 3, Filename: 4}, {Id: 2, Name: 5, Filename: 4}},
		Location:    []*profilev1.Location{{Id: 1, Line: []*profilev1.Line{{FunctionId: 2, Line: 42}, {FunctionId: 1, Line: 10}}}},
		Sample:      []*profilev1.Sample{{LocationId: []uint64{1}, Value: []int64{100}, Label: []*profilev1.Label{{Key: 6, Str: 7}, {Key: 8, Num: 64, NumUnit: 9}}}},
	}
}
func parseFixture(t *testing.T, p *profilev1.Profile, config string) output {
	t.Helper()
	b, err := p.MarshalVT()
	if err != nil {
		t.Fatal(err)
	}
	return convertPprof(b, []byte(config))
}
func TestPprofReconstructsInlineFramesAndLabels(t *testing.T) {
	out := parseFixture(t, fixture(), "")
	if out.Error != "" {
		t.Fatal(out.Error)
	}
	s := out.Profiles[0].Samples[0]
	if out.Profiles[0].Metric != "go:cpu" || s.Value != 100 || len(s.FrameIDs) != 2 {
		t.Fatalf("%+v", out)
	}
	if out.FrameTable[s.FrameIDs[0]].Name != "app.root" || out.FrameTable[s.FrameIDs[1]].Line != 42 {
		t.Fatal(out.FrameTable)
	}
	if s.Labels["request"] != "checkout" || s.Labels["size"] != "64 bytes" {
		t.Fatal(s.Labels)
	}
}
func TestPprofRejectsMalformedReferencesAndSemantics(t *testing.T) {
	cases := map[string]func(*profilev1.Profile){
		"negative":   func(p *profilev1.Profile) { p.Sample[0].Value[0] = -1 },
		"location":   func(p *profilev1.Profile) { p.Sample[0].LocationId[0] = 99 },
		"function":   func(p *profilev1.Profile) { p.Location[0].Line[0].FunctionId = 99 },
		"string":     func(p *profilev1.Profile) { p.Function[0].Name = 99 },
		"unit":       func(p *profilev1.Profile) { p.SampleType[0].Unit = 9 },
		"duplicate":  func(p *profilev1.Profile) { p.Function = append(p.Function, p.Function[0]) },
		"dimensions": func(p *profilev1.Profile) { p.Sample[0].Value = nil },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			p := fixture()
			mutate(p)
			if parseFixture(t, p, "").Error == "" {
				t.Fatal("accepted invalid profile")
			}
		})
	}
	for _, config := range []string{
		`{"cpu":{"cumulative":true}}`, `{"cpu":{"sampled":true}}`, `{"cpu":{"units":"bytes"}}`,
		`{"other":{"display-name":"cpu"}}`, `{"cpu":{"cumualtive":true}}`, `null`, `{} {}`, `{"cpu":null}`,
	} {
		if parseFixture(t, fixture(), config).Error == "" {
			t.Fatalf("accepted %s", config)
		}
	}
}

func TestPprofRejectsUnmarkedContentionAliases(t *testing.T) {
	for _, metric := range []string{"mutex_count", "block_count", "mutex_duration", "block_duration"} {
		p := fixture()
		p.StringTable[1] = metric
		if metric == "mutex_count" || metric == "block_count" {
			p.StringTable[2] = "count"
		}
		if parseFixture(t, p, "").Error == "" {
			t.Fatalf("accepted unmarked delta metric %s", metric)
		}
	}
}

func TestPprofValidatesOriginalStringTableAndInlineDepth(t *testing.T) {
	p := fixture()
	p.SampleType = append(p.SampleType, &profilev1.ValueType{Type: int64(len(p.StringTable)), Unit: 2})
	p.Sample[0].Value = append(p.Sample[0].Value, 10)
	if out := parseFixture(t, p, ""); out.Error != "invalid pprof: invalid pprof string reference" {
		t.Fatalf("reference resolved against mutated string table: %s", out.Error)
	}
	p = fixture()
	for len(p.Location[0].Line) <= maxStackFrames {
		p.Location[0].Line = append(p.Location[0].Line, &profilev1.Line{FunctionId: 1})
	}
	// Even empty samples must not bypass structural limits.
	p.Sample[0].Value[0] = 0
	if parseFixture(t, p, "").Error == "" {
		t.Fatal("accepted oversized inline stack")
	}
}
func TestPprofDeltaAndSnapshotContracts(t *testing.T) {
	for _, tc := range []struct{ name, unit, config, metric string }{
		{"alloc_space", "bytes", `{"alloc_space":{"units":"bytes","cumulative":false}}`, "alloc_space"},
		{"inuse_space", "bytes", `{"inuse_space":{"units":"bytes","aggregation":"average"}}`, "inuse_space"},
		{"goroutine", "count", `{"goroutine":{"units":"goroutines","display-name":"goroutines","aggregation":"average"}}`, "goroutines"},
		{"contentions", "count", `{"contentions":{"units":"lock_samples","display-name":"mutex_count"}}`, "mutex_count"},
		{"delay", "nanoseconds", `{"delay":{"units":"lock_nanoseconds","display-name":"block_duration"}}`, "block_duration"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p := fixture()
			p.StringTable[1] = tc.name
			p.StringTable[2] = tc.unit
			out := parseFixture(t, p, tc.config)
			if out.Error != "" || out.Profiles[0].Metric != "go:"+tc.metric {
				t.Fatalf("%+v", out)
			}
			if tc.name == "alloc_space" && parseFixture(t, p, "").Error == "" {
				t.Fatal("unmarked cumulative heap accepted")
			}
			p.Sample[0].Value[0] = 0
			out = parseFixture(t, p, tc.config)
			if out.Error != "" || len(out.Profiles) != 1 || len(out.Profiles[0].Samples) != 0 {
				t.Fatal("empty snapshot lost")
			}
		})
	}
}
