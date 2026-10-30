// Package main exposes Grafana's JFR and pprof conversion through a small C ABI.
// No Go pointers cross the boundary; Rust owns input and frees output with StoveProfileFree.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"time"
	"unsafe"

	jfr "github.com/grafana/jfr-parser/pprof"
)

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
	return normalize(parsed.Profiles)
}

//export StoveProfileConvert
func StoveProfileConvert(data unsafe.Pointer, size C.int, labels unsafe.Pointer, labelSize C.int, from C.longlong, until C.longlong, rate C.longlong) *C.char {
	out := convert(C.GoBytes(data, size), C.GoBytes(labels, labelSize), int64(from), int64(until), int64(rate))
	return encodeOutput(out)
}

// Native Go uploads use the same generated pprof protobuf contract as JFR output.
//
//export StovePprofConvert
func StovePprofConvert(data unsafe.Pointer, size C.int, config unsafe.Pointer, configSize C.int) *C.char {
	out := convertPprof(C.GoBytes(data, size), C.GoBytes(config, configSize))
	return encodeOutput(out)
}

func encodeOutput(out output) *C.char {
	b, err := json.Marshal(out)
	if err != nil {
		return C.CString(`{"error":"cannot encode profiles"}`)
	}
	return C.CString(string(b))
}

//export StoveProfileFree
func StoveProfileFree(p unsafe.Pointer) { C.free(p) }
func main()                             {}
