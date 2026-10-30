package main

import (
	"errors"
	"fmt"

	jfr "github.com/grafana/jfr-parser/pprof"
	profilev1 "github.com/grafana/pyroscope/api/gen/proto/go/google/v1"
)

func convertPprof(data, config []byte) (out output) {
	defer func() {
		if e := recover(); e != nil {
			out = output{Error: fmt.Sprintf("invalid pprof: %v", e)}
		}
	}()
	var p profilev1.Profile
	if err := p.UnmarshalVT(data); err != nil {
		return output{Error: err.Error()}
	}
	if len(p.SampleType) == 0 || len(p.SampleType) > 16 || len(p.Sample) > 100000 || len(p.Location) > 200000 || len(p.Function) > 200000 {
		return output{Error: "invalid pprof dimensions"}
	}
	strings := p.StringTable
	str := func(i int64) string {
		if i < 0 || i >= int64(len(strings)) || len(strings[i]) > 4096 {
			panic("invalid pprof string reference")
		}
		return strings[i]
	}
	if str(0) != "" {
		return output{Error: "pprof string table must start with empty string"}
	}
	cfg, err := parseSampleTypeConfig(config)
	if err != nil {
		return output{Error: err.Error()}
	}
	if err := validatePprofReferences(&p, str); err != nil {
		return output{Error: err.Error()}
	}
	if err := normalizeSampleTypes(&p, cfg, str); err != nil {
		return output{Error: err.Error()}
	}
	return normalize([]jfr.Profile{{Profile: &p, Metric: "go"}})
}

func validatePprofReferences(p *profilev1.Profile, str func(int64) string) error {
	functions := map[uint64]bool{}
	locations := map[uint64]bool{}
	for _, f := range p.Function {
		if f.Id == 0 || functions[f.Id] {
			return errors.New("duplicate or zero function ID")
		}
		functions[f.Id] = true
		str(f.Name)
		str(f.Filename)
	}
	for _, l := range p.Location {
		if l.Id == 0 || locations[l.Id] {
			return errors.New("duplicate or zero location ID")
		}
		if len(l.Line) > maxStackFrames {
			return errors.New("stack exceeds 512 frames")
		}
		locations[l.Id] = true
		for _, line := range l.Line {
			if !functions[line.FunctionId] {
				return errors.New("unknown function ID")
			}
		}
	}
	for _, s := range p.Sample {
		if len(s.Value) != len(p.SampleType) || len(s.LocationId) > maxStackFrames || len(s.Label) > 64 {
			return errors.New("invalid pprof sample dimensions")
		}
		for _, id := range s.LocationId {
			if !locations[id] {
				return errors.New("unknown location ID")
			}
		}
		for _, v := range s.Value {
			if v < 0 {
				return errors.New("negative pprof sample")
			}
		}
		for _, l := range s.Label {
			str(l.Key)
			str(l.Str)
			str(l.NumUnit)
		}
	}
	return nil
}

func normalizeSampleTypes(p *profilev1.Profile, cfg map[string]sampleTypeConfig, str func(int64) string) error {
	// Support the SDK's known delta/snapshot types; reject cumulative uploads instead
	// of silently counting process-lifetime totals once per recording.
	seen := map[string]bool{}
	originalTypes := map[string]bool{}
	for _, typ := range p.SampleType {
		name, unit := str(typ.Type), str(typ.Unit)
		c, configured := cfg[name]
		if c.Cumulative || c.Sampled {
			return errors.New("cumulative or rescaling sample config is unsupported")
		}
		metric := name
		if c.DisplayName != "" {
			metric = c.DisplayName
		}
		spec, ok := goSampleTypes[metric]
		if !ok {
			return fmt.Errorf("unsupported Go sample type: %q", metric)
		}
		if metric != name && spec.sourceName != name {
			return errors.New("unsupported sample display name")
		}
		if spec.semantics == delta && !configured {
			return errors.New("delta sample_type_config is required")
		}
		if c.Units != "" && c.Units != spec.displayUnit {
			return errors.New("invalid configured units")
		}
		if unit != spec.unit || seen[metric] || originalTypes[name] {
			return errors.New("invalid or duplicate Go metric")
		}
		if c.Aggregation != "" && c.Aggregation != "sum" && !(spec.semantics == snapshot && c.Aggregation == "average") {
			return errors.New("unsupported sample aggregation")
		}
		originalTypes[name] = true
		seen[metric] = true
		p.StringTable = append(p.StringTable, metric)
		typ.Type = int64(len(p.StringTable) - 1)
	}
	for name := range cfg {
		if !originalTypes[name] {
			return errors.New("unknown configured sample type")
		}
	}

	return nil
}
