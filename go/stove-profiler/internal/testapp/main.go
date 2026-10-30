// Small real-SDK workload used by the server's profiling contract test.
package main

import (
	"fmt"
	"runtime"
	"time"

	stoveprofiler "github.com/trendyol/stove/go/stove-profiler"
)

var retained [][]byte

func main() {
	profiler, err := stoveprofiler.StartFromEnv()
	if err != nil {
		panic(err)
	}
	defer profiler.Close()
	deadline := time.Now().Add(350 * time.Millisecond)
	var value uint64 = 7
	for time.Now().Before(deadline) {
		for range 10000 {
			value = value*6364136223846793005 + 1
		}
		retained = append(retained, make([]byte, 8192))
		if len(retained) > 2048 {
			retained = retained[:512]
		}
	}
	runtime.GC() // Test workload: make a heap sample available without library-forced GC.
	fmt.Println(value)
}
