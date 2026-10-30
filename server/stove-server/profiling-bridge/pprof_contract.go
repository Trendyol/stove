package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
)

type sampleTypeConfig struct {
	Units       string `json:"units"`
	Aggregation string `json:"aggregation"`
	DisplayName string `json:"display-name"`
	Sampled     bool   `json:"sampled"`
	Cumulative  bool   `json:"cumulative"`
}

// This contract follows pyroscope-go's upstream.SampleType metadata. Keep it
// separate from protobuf reference checks so runtime-specific semantics are explicit.
type sampleSemantics uint8

const (
	interval sampleSemantics = iota
	delta
	snapshot
)

type goSampleType struct {
	sourceName  string
	unit        string
	displayUnit string
	semantics   sampleSemantics
}

var goSampleTypes = map[string]goSampleType{
	"cpu":            {unit: "nanoseconds", displayUnit: "nanoseconds", semantics: interval},
	"samples":        {unit: "count", displayUnit: "samples", semantics: interval},
	"alloc_objects":  {unit: "count", displayUnit: "objects", semantics: delta},
	"alloc_space":    {unit: "bytes", displayUnit: "bytes", semantics: delta},
	"inuse_objects":  {unit: "count", displayUnit: "objects", semantics: snapshot},
	"inuse_space":    {unit: "bytes", displayUnit: "bytes", semantics: snapshot},
	"goroutines":     {sourceName: "goroutine", unit: "count", displayUnit: "goroutines", semantics: snapshot},
	"mutex_count":    {sourceName: "contentions", unit: "count", displayUnit: "lock_samples", semantics: delta},
	"block_count":    {sourceName: "contentions", unit: "count", displayUnit: "lock_samples", semantics: delta},
	"mutex_duration": {sourceName: "delay", unit: "nanoseconds", displayUnit: "lock_nanoseconds", semantics: delta},
	"block_duration": {sourceName: "delay", unit: "nanoseconds", displayUnit: "lock_nanoseconds", semantics: delta},
}

func parseSampleTypeConfig(data []byte) (map[string]sampleTypeConfig, error) {
	config := make(map[string]sampleTypeConfig)
	if len(data) == 0 {
		return config, nil
	}
	if len(data) > 64*1024 {
		return nil, errors.New("sample type config exceeds 64 KiB")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var wire map[string]*sampleTypeConfig
	if err := decoder.Decode(&wire); err != nil || wire == nil {
		return nil, errors.New("invalid sample_type_config")
	}
	for name, value := range wire {
		if value == nil {
			return nil, errors.New("null sample_type_config entry")
		}
		config[name] = *value
	}
	if decoder.Decode(new(any)) != io.EOF {
		return nil, errors.New("invalid sample_type_config trailing data")
	}
	return config, nil
}
