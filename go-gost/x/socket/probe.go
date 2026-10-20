package socket

import (
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"time"
)

// 延迟监测:节点自己定时测,结果夹在下一次系统信息里带回面板。
//
// 为什么不让面板定时发 TcpPing 命令:节点处理命令是【串行】的(读循环里同步执行),
// 一次 TcpPing 测不通的目标要卡好几秒,这期间车主在面板上改转发、建协议,
// 命令排在后面,很容易撞上面板 10 秒超时。而且每条命令执行完都会重写一次 gost.json。
// 定时监测必须完全在旁路跑,不能占命令通道。

type ProbeTarget struct {
	ID   int    `json:"id"`
	Host string `json:"host"`
	Port int    `json:"port"`
}

type ProbeConfig struct {
	Targets  []ProbeTarget `json:"targets"`
	Interval int           `json:"interval"` // 秒
	Count    int           `json:"count"`
	Timeout  int           `json:"timeout"` // 毫秒
}

type ProbeResult struct {
	ID   int     `json:"id"`
	Avg  float64 `json:"avg"`  // 毫秒,全丢时为 -1
	Loss float64 `json:"loss"` // 百分比
}

type ProbeReport struct {
	Ts      int64         `json:"ts"` // 这一轮开始的时间(毫秒)
	Results []ProbeResult `json:"results"`
}

var (
	probeMu      sync.Mutex
	probeCfg     ProbeConfig
	probeGen     int // 每次换配置 +1,旧的循环看到不一致就退出
	probePending *ProbeReport
)

// handleSetProbes 只存配置、起循环,立刻返回,不在命令通道里做任何网络操作
func (w *WebSocketReporter) handleSetProbes(data interface{}) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化监测配置失败: %v", err)
	}
	var cfg ProbeConfig
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return fmt.Errorf("解析监测配置失败: %v", err)
	}
	if cfg.Interval < 60 {
		cfg.Interval = 60
	}
	if cfg.Count <= 0 || cfg.Count > 10 {
		cfg.Count = 3
	}
	if cfg.Timeout <= 0 || cfg.Timeout > 5000 {
		cfg.Timeout = 2000
	}

	probeMu.Lock()
	probeCfg = cfg
	probeGen++
	gen := probeGen
	probeMu.Unlock()

	if len(cfg.Targets) > 0 {
		go probeLoop(gen)
	}
	return nil
}

func probeLoop(gen int) {
	// 错开一点再测,别和刚连上时的其它初始化挤在一起
	time.Sleep(5 * time.Second)
	for {
		probeMu.Lock()
		if gen != probeGen {
			probeMu.Unlock()
			return
		}
		cfg := probeCfg
		probeMu.Unlock()

		start := time.Now()
		report := &ProbeReport{Ts: start.UnixMilli()}
		var wg sync.WaitGroup
		var mu sync.Mutex
		for _, t := range cfg.Targets {
			wg.Add(1)
			go func(t ProbeTarget) {
				defer wg.Done()
				avg, loss := probeOnce(t.Host, t.Port, cfg.Count, cfg.Timeout)
				mu.Lock()
				report.Results = append(report.Results, ProbeResult{ID: t.ID, Avg: avg, Loss: loss})
				mu.Unlock()
			}(t)
		}
		wg.Wait()

		probeMu.Lock()
		if gen == probeGen {
			probePending = report
		}
		probeMu.Unlock()

		sleep := time.Duration(cfg.Interval)*time.Second - time.Since(start)
		if sleep > 0 {
			time.Sleep(sleep)
		}
	}
}

// takeProbeReport 取走最近一轮还没报过的结果(报一次就清掉)
func takeProbeReport() *ProbeReport {
	probeMu.Lock()
	defer probeMu.Unlock()
	r := probePending
	probePending = nil
	return r
}

// probeOnce 跟 tcpPingHost 一样测 TCP 建连耗时,但不打日志
// (每 5 分钟一轮,打日志会把 journal 刷满)
func probeOnce(host string, port int, count int, timeoutMs int) (float64, float64) {
	addr := host
	if net.ParseIP(host) == nil {
		addrs, err := net.LookupHost(host)
		if err != nil || len(addrs) == 0 {
			return -1, 100
		}
		addr = addrs[0]
	}
	target := net.JoinHostPort(addr, fmt.Sprintf("%d", port))
	timeout := time.Duration(timeoutMs) * time.Millisecond

	var total float64
	ok := 0
	for i := 0; i < count; i++ {
		start := time.Now()
		conn, err := net.DialTimeout("tcp", target, timeout)
		if err == nil {
			total += float64(time.Since(start).Microseconds()) / 1000
			ok++
			conn.Close()
		}
		if i < count-1 {
			time.Sleep(200 * time.Millisecond)
		}
	}
	loss := float64(count-ok) / float64(count) * 100
	if ok == 0 {
		return -1, loss
	}
	return total / float64(ok), loss
}
