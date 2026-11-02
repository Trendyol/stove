package main

import (
	"encoding/json"
	"errors"
	"math"
)

// profileAccumulator owns metric identity and sample grouping. The normalizer
// enforces raw conversion budgets before adding each contributing record.
type profileAccumulator struct {
	profiles []profile
	indexes  map[[2]string]int
	groups   []map[string]int
}

func newProfileAccumulator() *profileAccumulator {
	return &profileAccumulator{profiles: make([]profile, 0), indexes: make(map[[2]string]int)}
}

func (a *profileAccumulator) index(metric, unit string) int {
	key := [2]string{metric, unit}
	if id, exists := a.indexes[key]; exists {
		return id
	}
	id := len(a.profiles)
	a.indexes[key] = id
	// Empty profiles are intentional: an empty snapshot supersedes earlier data.
	a.profiles = append(a.profiles, profile{Metric: metric, Unit: unit, Samples: make([]sample, 0)})
	a.groups = append(a.groups, make(map[string]int))
	return id
}

func (a *profileAccumulator) add(target int, key string, record sample) error {
	samples := &a.profiles[target].Samples
	if existing, ok := a.groups[target][key]; ok {
		previous := &(*samples)[existing]
		if previous.Value > math.MaxInt64-record.Value {
			return errors.New("profile value overflow")
		}
		previous.Value += record.Value
		previous.Count = max(1, previous.Count) + 1
	} else {
		a.groups[target][key] = len(*samples)
		*samples = append(*samples, record)
	}
	return nil
}

func sampleKey(frames []int, labels map[string]string) string {
	// JSON sorts map keys and preserves stack order; lengths/escaping make the key unambiguous.
	encoded, _ := json.Marshal(struct {
		Frames []int
		Labels map[string]string
	}{frames, labels})
	return string(encoded)
}
