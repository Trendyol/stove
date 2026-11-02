package main

import (
	"encoding/json"
	"math"
	"strings"
	"testing"

	jfr "github.com/grafana/jfr-parser/pprof"
	profilev1 "github.com/grafana/pyroscope/api/gen/proto/go/google/v1"
)

func TestAggregationPreservesStackOrderLabelsAndMetricCounts(t *testing.T) {
	p := fixture()
	p.SampleType = append(p.SampleType, &profilev1.ValueType{Type: 8, Unit: 9})
	p.Sample[0].Value = []int64{100, 7}
	duplicate := p.Sample[0].CloneVT()
	// Label input order does not affect identity.
	duplicate.Label[0], duplicate.Label[1] = duplicate.Label[1], duplicate.Label[0]
	distinct := p.Sample[0].CloneVT()
	distinct.Label[0].Str = 3
	p.Location = append(p.Location, &profilev1.Location{Id: 2, Line: []*profilev1.Line{{FunctionId: 1, Line: 10}, {FunctionId: 2, Line: 42}}})
	distinctStack := p.Sample[0].CloneVT()
	distinctStack.LocationId = []uint64{2}
	p.Sample = append(p.Sample, duplicate, distinct, distinctStack)
	out := normalize([]jfr.Profile{{Metric: "go", Profile: p}})
	if out.Error != "" || len(out.Profiles) != 2 {
		t.Fatalf("%+v", out)
	}
	for i, expected := range []int64{200, 14} {
		samples := out.Profiles[i].Samples
		if len(samples) != 3 || samples[0].Value != expected || samples[0].Count != 2 || samples[1].Count != 0 {
			t.Fatalf("%+v", samples)
		}
		encoded, _ := json.Marshal(samples[1])
		if strings.Contains(string(encoded), "count") {
			t.Fatal("unique record has count overhead")
		}
	}
	// Separate pprof items with the same metric/unit must aggregate too.
	out = normalize([]jfr.Profile{{Metric: "go", Profile: p}, {Metric: "go", Profile: p}})
	if len(out.Profiles) != 2 || out.Profiles[0].Samples[0].Count != 4 {
		t.Fatalf("%+v", out)
	}
}

func TestAggregationRejectsOverflowAndStillEnforcesRawRecordBudget(t *testing.T) {
	p := fixture()
	p.Sample[0].Value[0] = math.MaxInt64
	p.Sample = append(p.Sample, p.Sample[0].CloneVT())
	if out := normalize([]jfr.Profile{{Metric: "go", Profile: p}}); out.Error != "profile value overflow" {
		t.Fatalf("%+v", out)
	}
	p = fixture()
	record := p.Sample[0]
	p.Sample = make([]*profilev1.Sample, maxSampleRecords+1)
	for i := range p.Sample {
		p.Sample[i] = record
	}
	if out := normalize([]jfr.Profile{{Metric: "go", Profile: p}}); out.Error != "normalized profile exceeds conversion budget" {
		t.Fatal(out.Error)
	}
}
