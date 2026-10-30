package main

import (
	"encoding/json"
	"slices"
	"strconv"

	jfr "github.com/grafana/jfr-parser/pprof"
)

const (
	maxStackFrames        = 512
	maxProfileStringBytes = 4096
	maxNormalizedBytes    = 30 * 1024 * 1024
	maxSampleRecords      = 100_000
)

type frame struct {
	Name string `json:"name"`
	File string `json:"file"`
	Line int64  `json:"line"`
}
type sample struct {
	FrameIDs []int             `json:"frame_ids"`
	Value    int64             `json:"value"`
	Labels   map[string]string `json:"labels"`
}
type profile struct {
	Metric  string   `json:"metric"`
	Unit    string   `json:"unit"`
	Samples []sample `json:"samples"`
}
type output struct {
	FrameTable []frame   `json:"frame_table,omitempty"`
	Profiles   []profile `json:"profiles,omitempty"`
	Error      string    `json:"error,omitempty"`
}

func normalize(items []jfr.Profile) (out output) {
	out.Profiles = make([]profile, 0)
	var outputBytes int
	var records int
	frameIDs := make(map[frame]int)
	for _, item := range items {
		p := item.Profile
		str := func(i int64) string {
			if i < 0 || i >= int64(len(p.StringTable)) {
				return ""
			}
			v := p.StringTable[i]
			if len(v) > maxProfileStringBytes {
				panic("profile string exceeds 4096 bytes")
			}
			return v
		}
		locations := make(map[uint64][]frame)
		functions := make(map[uint64]frame)
		for _, f := range p.Function {
			functions[f.Id] = frame{Name: str(f.Name), File: str(f.Filename)}
		}
		for _, l := range p.Location {
			for _, line := range l.Line {
				f := functions[line.FunctionId]
				f.Line = line.Line
				locations[l.Id] = append(locations[l.Id], f)
			}
		}
		firstProfile := len(out.Profiles)
		for _, typ := range p.SampleType {
			out.Profiles = append(out.Profiles, profile{
				Metric: item.Metric + ":" + str(typ.Type), Unit: str(typ.Unit), Samples: make([]sample, 0),
			})
		}
		for _, s := range p.Sample {
			if !slices.ContainsFunc(s.Value, func(v int64) bool { return v > 0 }) {
				continue
			}
			// A pprof sample shares its stack and labels across all sample types.
			// Reconstruct them once, including for Go's four heap metrics.
			frames := make([]frame, 0)
			for _, id := range s.LocationId {
				frames = append(frames, locations[id]...)
				if len(frames) > maxStackFrames {
					return output{Error: "stack exceeds 512 frames"}
				}
			}
			for i, j := 0, len(frames)-1; i < j; i, j = i+1, j-1 {
				frames[i], frames[j] = frames[j], frames[i]
			}
			if len(frames) == 0 {
				frames = append(frames, frame{Name: "[unknown stack]"})
			}
			ls := make(map[string]string)
			for _, l := range s.Label {
				if l.Str != 0 {
					ls[str(l.Key)] = str(l.Str)
				} else if item.Metric == "go" {
					ls[str(l.Key)] = strconv.FormatInt(l.Num, 10)
					if unit := str(l.NumUnit); unit != "" {
						ls[str(l.Key)] += " " + unit
					}
				}
			}
			ids := make([]int, 0, len(frames))
			for _, f := range frames {
				id, exists := frameIDs[f]
				if !exists {
					id = len(out.FrameTable)
					frameIDs[f] = id
					out.FrameTable = append(out.FrameTable, f)
					encoded, _ := json.Marshal(f)
					outputBytes += len(encoded) + 1
				}
				ids = append(ids, id)
			}
			for idx, value := range s.Value {
				if idx >= len(p.SampleType) {
					break
				}
				if value <= 0 {
					continue
				}
				record := sample{FrameIDs: ids, Value: value, Labels: ls}
				encoded, err := json.Marshal(record)
				if err != nil {
					return output{Error: "cannot encode sample"}
				}
				outputBytes += len(encoded)
				records++
				if outputBytes > maxNormalizedBytes || records > maxSampleRecords {
					return output{Error: "normalized profile exceeds conversion budget"}
				}
				out.Profiles[firstProfile+idx].Samples = append(out.Profiles[firstProfile+idx].Samples, record)
			}
		}
	}
	return out
}
