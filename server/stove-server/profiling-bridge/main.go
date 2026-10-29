// Package main exposes Grafana's JFR converter through a small C ABI.
// No Go pointers cross the boundary; Rust owns input and frees output with StoveProfileFree.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"fmt"
	jfr "github.com/grafana/jfr-parser/pprof"
	"time"
	"unsafe"
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

func convert(data, labels []byte, from, until, rate int64) (out output) {
	defer func() {
		if e := recover(); e != nil {
			out = output{Error: fmt.Sprintf("invalid JFR: %v", e)}
		}
	}()
	snapshot := new(jfr.LabelsSnapshot)
	if len(labels) > 0 {
		if err := snapshot.UnmarshalVT(labels); err != nil {
			return output{Error: err.Error()}
		}
	}
	parsed, err := jfr.ParseJFR(data, &jfr.ParseInput{StartTime: time.Unix(from, 0), EndTime: time.Unix(until, 0), SampleRate: rate}, snapshot)
	if err != nil {
		return output{Error: err.Error()}
	}
	out.Profiles = make([]profile, 0)
	var outputBytes int
	var records int
	frameIDs := make(map[frame]int)
	for _, item := range parsed.Profiles {
		p := item.Profile
		str := func(i int64) string {
			if i < 0 || i >= int64(len(p.StringTable)) {
				return ""
			}
			v := p.StringTable[i]
			if len(v) > 4096 {
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
		for idx, typ := range p.SampleType {
			result := profile{Metric: item.Metric + ":" + str(typ.Type), Unit: str(typ.Unit), Samples: make([]sample, 0)}
			for _, s := range p.Sample {
				if idx >= len(s.Value) || s.Value[idx] <= 0 {
					continue
				}
				frames := make([]frame, 0)
				for _, id := range s.LocationId {
					frames = append(frames, locations[id]...)
					if len(frames) > 512 {
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
				record := sample{FrameIDs: ids, Value: s.Value[idx], Labels: ls}
				encoded, err := json.Marshal(record)
				if err != nil {
					return output{Error: "cannot encode sample"}
				}
				outputBytes += len(encoded)
				records++
				if outputBytes > 30*1024*1024 || records > 100000 {
					return output{Error: "normalized profile exceeds conversion budget"}
				}
				result.Samples = append(result.Samples, record)
			}
			out.Profiles = append(out.Profiles, result)
		}
	}
	return out
}

//export StoveProfileConvert
func StoveProfileConvert(data unsafe.Pointer, size C.int, labels unsafe.Pointer, labelSize C.int, from C.longlong, until C.longlong, rate C.longlong) *C.char {
	out := convert(C.GoBytes(data, size), C.GoBytes(labels, labelSize), int64(from), int64(until), int64(rate))
	b, err := json.Marshal(out)
	if err != nil {
		return C.CString(`{"error":"cannot encode profiles"}`)
	}
	return C.CString(string(b))
}

//export StoveProfileFree
func StoveProfileFree(p unsafe.Pointer) { C.free(p) }
func main()                             {}
